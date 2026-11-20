package com.muffuq.os.airplay

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * The mirroring audio channel (stream type 96): RTP over UDP.
 *
 *   packet[2..3] sequence number (BE)   packet[4..7] RTP timestamp (BE)   packet[12..] payload
 *
 * Payloads are AES-128-CBC encrypted per packet (whole 16-byte blocks only; the tail is plain). For AAC-ELD every
 * packet is sent three times (0, 0 1, 0 1 2, 1 2 3 …), so frames are de-duplicated and put back in order here.
 */
class AudioReceiver(private val key: ByteArray, private val iv: ByteArray, private val player: AudioPlayer) {
    private val dataSocket = DatagramSocket(0)
    private val controlSocket = DatagramSocket(0)
    val dataPort: Int get() = dataSocket.localPort
    val controlPort: Int get() = controlSocket.localPort

    @Volatile private var running = true
    private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    private val secretKey = SecretKeySpec(key, "AES")

    private var nextSeq = -1L
    private val pending = TreeMap<Long, ByteArray>()

    private val worker = thread(name = "airplay-audio", isDaemon = true) { receive() }
    private val controlWorker = thread(name = "airplay-audio-ctl", isDaemon = true) { drainControl() }

    private fun receive() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                packet.length = buffer.size
                dataSocket.receive(packet)
                if (packet.length < 12) continue
                val payloadSize = packet.length - 12
                // Silence placeholder sent before real audio starts.
                if (payloadSize == 4 && buffer[12].toInt() == 0x00 && buffer[13].toInt() == 0x68 &&
                    buffer[14].toInt() == 0x34 && buffer[15].toInt() == 0x00
                ) continue
                val seq = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
                accept(seq, buffer, payloadSize)
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "audio socket closed", e)
                return
            } catch (e: Exception) {
                Log.w(TAG, "audio packet error", e)
            }
        }
    }

    private fun accept(seq: Int, packet: ByteArray, payloadSize: Int) {
        val extended: Long
        if (nextSeq < 0) {
            extended = seq.toLong()
            nextSeq = extended
        } else {
            // Place the 16-bit sequence number relative to where we are; anything behind us is a duplicate.
            val diff = (seq - (nextSeq and 0xFFFF).toInt()) and 0xFFFF
            if (diff >= 0x8000) return
            extended = nextSeq + diff
        }
        if (pending.containsKey(extended)) return
        pending[extended] = decrypt(packet, payloadSize)

        while (true) {
            val frame = pending.remove(nextSeq)
            if (frame != null) {
                player.enqueue(frame)
                nextSeq++
                continue
            }
            // A frame is really lost (all three copies): skip ahead rather than stall.
            if (pending.size > MAX_GAP) {
                nextSeq = pending.firstKey()
                continue
            }
            break
        }
    }

    private fun decrypt(packet: ByteArray, payloadSize: Int): ByteArray {
        val out = ByteArray(payloadSize)
        val encrypted = payloadSize / 16 * 16
        if (encrypted > 0) {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            cipher.doFinal(packet, 12, encrypted, out, 0)
        }
        System.arraycopy(packet, 12 + encrypted, out, encrypted, payloadSize - encrypted)
        return out
    }

    /** Sync and retransmit packets arrive here; we don't need them, but the socket must exist and be read. */
    private fun drainControl() {
        val buffer = ByteArray(1024)
        val packet = DatagramPacket(buffer, buffer.size)
        while (running) {
            try {
                packet.length = buffer.size
                controlSocket.receive(packet)
            } catch (_: SocketException) {
                return
            }
        }
    }

    fun close() {
        running = false
        dataSocket.close()
        controlSocket.close()
    }

    private companion object {
        const val TAG = "MuffuqAirPlay"
        const val MAX_GAP = 6
    }
}
