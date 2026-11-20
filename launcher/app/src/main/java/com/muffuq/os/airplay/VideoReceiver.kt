package com.muffuq.os.airplay

import android.util.Log
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The mirroring video channel (stream type 110): one TCP connection from the sender, a 128-byte header per packet.
 *
 *   header[0..3]  payload size (LE)       header[4]  0 = video, 1 = codec config (SPS/PPS), 2/5 = heartbeat/report
 *   header[6]     0x56 = sender paused (screen locked), 0x16 = running
 *   header[8..15] NTP timestamp (LE)      header[56..63] video width/height as LE floats (config packets)
 *
 * Video payloads are AES-128-CTR encrypted with one continuous keystream per stream and carry NAL units with
 * 4-byte length prefixes, rewritten here to Annex-B start codes for MediaCodec.
 */
class VideoReceiver(sessionKey: ByteArray, streamConnectionId: Long, private val player: VideoPlayer) {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort

    private val cipher: Cipher = run {
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = sha512("AirPlayStreamKey$id".toByteArray(), sessionKey).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray(), sessionKey).copyOf(16)
        Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    @Volatile private var running = true
    @Volatile private var client: Socket? = null
    private var dropped = 0

    private val worker = thread(name = "airplay-video", isDaemon = true) { receive() }

    private fun receive() {
        try {
            val socket = server.accept().also { client = it }
            server.close()
            socket.keepAlive = true
            socket.receiveBufferSize = 512 * 1024
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
            val header = ByteArray(HEADER_SIZE)
            val le = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            while (running) {
                input.readFully(header)
                val size = le.getInt(0)
                if (size !in 0..MAX_PAYLOAD) throw IOException("bad video payload size $size")
                val payload = ByteArray(size)
                input.readFully(payload)
                when (header[4].toInt()) {
                    0 -> onVideo(payload, le.getLong(8))
                    1 -> onConfig(header, le, payload)
                }
            }
        } catch (e: IOException) {
            if (running) Log.i(TAG, "video stream ended: ${e.message}")
        } finally {
            client?.close()
            server.close()
        }
    }

    private fun onVideo(payload: ByteArray, ntpTimestamp: Long) {
        val decrypted = ByteArray(payload.size)
        val produced = cipher.update(payload, 0, payload.size, decrypted, 0)
        if (produced != payload.size) Log.w(TAG, "CTR produced $produced of ${payload.size} bytes")
        if (!lengthPrefixedToAnnexB(decrypted)) {
            // A wrong key makes every packet fail: say so once in a while, not 30 times a second.
            if (dropped++ % 300 == 0) Log.w(TAG, "dropping undecodable video packets ($dropped so far)")
            return
        }
        player.onFrame(decrypted, ntpToMicros(ntpTimestamp))
    }

    private fun onConfig(header: ByteArray, le: ByteBuffer, payload: ByteArray) {
        player.onPaused(header[6].toInt() == 0x56)
        if (payload.size < 11) return
        // avcC: ... [6..7] SPS length, SPS, [1] PPS count, [2] PPS length, PPS
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val ppsAt = 8 + spsSize
        if (ppsAt + 3 > payload.size) return
        val ppsSize = ((payload[ppsAt + 1].toInt() and 0xFF) shl 8) or (payload[ppsAt + 2].toInt() and 0xFF)
        if (ppsAt + 3 + ppsSize > payload.size) return
        val sps = START_CODE + payload.copyOfRange(8, ppsAt)
        val pps = START_CODE + payload.copyOfRange(ppsAt + 3, ppsAt + 3 + ppsSize)
        player.onConfig(sps, pps, le.getFloat(56).toInt(), le.getFloat(60).toInt())
    }

    fun close() {
        running = false
        server.close()
        client?.close()
    }

    companion object {
        private const val TAG = "MuffuqAirPlay"
        private const val HEADER_SIZE = 128
        private const val MAX_PAYLOAD = 8 * 1024 * 1024
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /** Rewrites [len][NAL][len][NAL]… into [00 00 00 01][NAL]… in place. False if the lengths don't add up. */
        fun lengthPrefixedToAnnexB(data: ByteArray): Boolean {
            var pos = 0
            while (pos + 4 <= data.size) {
                val len = ((data[pos].toInt() and 0xFF) shl 24) or ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or (data[pos + 3].toInt() and 0xFF)
                if (len <= 0 || len > data.size - pos - 4) return false
                data[pos] = 0
                data[pos + 1] = 0
                data[pos + 2] = 0
                data[pos + 3] = 1
                if (data[pos + 4].toInt() and 0x80 != 0) return false // forbidden_zero_bit: wrong key
                pos += 4 + len
            }
            return pos == data.size
        }

        /** 32.32 fixed point seconds -> microseconds. */
        fun ntpToMicros(ntp: Long): Long {
            val seconds = ntp ushr 32
            val fraction = ntp and 0xFFFFFFFFL
            return seconds * 1_000_000 + ((fraction * 1_000_000) ushr 32)
        }
    }
}
