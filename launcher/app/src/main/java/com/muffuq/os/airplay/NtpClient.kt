package com.muffuq.os.airplay

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * AirPlay's NTP-like timing exchange: the receiver asks the sender for its clock every few seconds on the sender's
 * timing port. We only need it to keep the sender happy (mirroring stalls without it); answers aren't used yet.
 * Requests the sender makes to us are answered as well.
 */
class NtpClient(private val remote: InetAddress, private val remotePort: Int) {
    private val socket = DatagramSocket(0).apply { soTimeout = 1000 }
    val localPort: Int get() = socket.localPort
    @Volatile private var running = true

    private val worker = thread(name = "airplay-ntp", isDaemon = true) { loop() }

    private fun loop() {
        val request = ByteArray(32).apply {
            this[0] = 0x80.toByte()
            this[1] = 0xd2.toByte()
            this[3] = 0x07
        }
        val buffer = ByteArray(128)
        var nextRequest = 0L
        while (running) {
            try {
                val now = System.currentTimeMillis()
                if (remotePort > 0 && now >= nextRequest) {
                    putNtpTime(request, 24, now)
                    socket.send(DatagramPacket(request, request.size, remote, remotePort))
                    nextRequest = now + INTERVAL_MS
                }
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                if (packet.length >= 32 && (buffer[1].toInt() and 0x7F) == 0x52) answer(packet)
            } catch (_: SocketTimeoutException) {
            } catch (e: SocketException) {
                if (running) Log.w(TAG, "timing socket closed", e)
                return
            } catch (e: Exception) {
                Log.w(TAG, "timing error", e)
            }
        }
    }

    /** The sender asked for our time: reply 0xd3 with its send time as origin and ours as receive/transmit. */
    private fun answer(request: DatagramPacket) {
        val reply = ByteArray(32)
        reply[0] = 0x80.toByte()
        reply[1] = 0xd3.toByte()
        reply[3] = 0x07
        System.arraycopy(request.data, 24, reply, 8, 8)
        val now = System.currentTimeMillis()
        putNtpTime(reply, 16, now)
        putNtpTime(reply, 24, now)
        socket.send(DatagramPacket(reply, reply.size, request.address, request.port))
    }

    fun close() {
        running = false
        socket.close()
    }

    private companion object {
        const val TAG = "MuffuqAirPlay"
        const val INTERVAL_MS = 3000L
        const val SECONDS_1900_TO_1970 = 2208988800L

        fun putNtpTime(buffer: ByteArray, at: Int, millis: Long) {
            val seconds = millis / 1000 + SECONDS_1900_TO_1970
            val fraction = ((millis % 1000) shl 32) / 1000
            for (i in 0 until 4) buffer[at + i] = (seconds ushr (24 - 8 * i)).toByte()
            for (i in 0 until 4) buffer[at + 4 + i] = (fraction ushr (24 - 8 * i)).toByte()
        }
    }
}
