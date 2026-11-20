package com.muffuq.os.airplay

import android.util.Log
import com.muffuq.os.BuildConfig
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale
import kotlin.concurrent.thread

/**
 * AirPlay receiver control channel: RTSP on TCP (port 7000 by default), the same protocol UxPlay implements.
 * One mirroring session at a time; a new sender that gets as far as SETUP takes over from the current one.
 */
class AirPlayServer(
    private val identity: DeviceIdentity,
    private val name: String,
    private val volume: TvVolume,
    private val events: Events,
) {
    interface Events {
        fun onMirroringStarted()
        fun onSessionEnded()
    }

    private var serverSocket: ServerSocket? = null
    val port: Int get() = serverSocket?.localPort ?: DEFAULT_PORT

    @Volatile private var running = false
    @Volatile private var active: Connection? = null

    fun start() {
        serverSocket = bind()
        running = true
        thread(name = "airplay-rtsp", isDaemon = true) { acceptLoop() }
        Log.i(TAG, "AirPlay receiver '$name' listening on $port")
    }

    fun stop() {
        running = false
        serverSocket?.close()
        active?.close()
    }

    /** Ends the current mirroring session (Back on the mirroring screen). */
    fun disconnectActive() {
        active?.close()
    }

    private fun bind(): ServerSocket {
        for (candidate in DEFAULT_PORT..DEFAULT_PORT + 10) {
            try {
                return ServerSocket(candidate)
            } catch (_: BindException) {
            }
        }
        return ServerSocket(0)
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket!!.accept()
                val connection = Connection(socket)
                thread(name = "airplay-conn", isDaemon = true) { connection.run() }
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed", e)
            }
        }
    }

    private class Request(
        val method: String,
        val uri: String,
        val protocol: String,
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String) = headers[name.lowercase()]
        val isPlist get() = header("content-type")?.contains("apple-binary-plist") == true
    }

    private class Response(var status: Int = 200, var reason: String = "OK") {
        val headers = LinkedHashMap<String, String>()
        var body = ByteArray(0)
        var closeAfter = false

        fun plist(value: Any?) {
            headers["Content-Type"] = "application/x-apple-binary-plist"
            body = BPlist.encode(value)
        }

        fun binary(bytes: ByteArray) {
            headers["Content-Type"] = "application/octet-stream"
            body = bytes
        }

        fun fail(status: Int, reason: String) {
            this.status = status
            this.reason = reason
        }
    }

    private inner class Connection(private val socket: Socket) {
        private val fairPlay = FairPlay()
        private val pairing = PairingSession(identity)
        private var session: MirrorSession? = null
        private val remote: InetAddress = socket.inetAddress

        fun run() {
            try {
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (running) {
                    val request = readRequest(input) ?: break
                    val response = Response()
                    try {
                        handle(request, response)
                    } catch (e: Exception) {
                        Log.e(TAG, "error handling ${request.method} ${request.uri}", e)
                        response.fail(500, "Internal Server Error")
                    }
                    writeResponse(output, request, response)
                    if (response.closeAfter) break
                }
            } catch (_: SocketException) {
            } catch (e: IOException) {
                Log.i(TAG, "connection from $remote ended: ${e.message}")
            } finally {
                close()
            }
        }

        fun close() {
            endSession()
            runCatching { socket.close() }
        }

        private fun handle(request: Request, response: Response) {
            if (BuildConfig.DEBUG) Log.d(TAG, "${request.method} ${request.uri} (${request.body.size} bytes)")
            when (request.method) {
                "GET" -> if (request.uri.startsWith("/info")) info(request, response) else response.fail(404, "Not Found")
                "POST" -> when (request.uri) {
                    "/pair-setup" -> pairing.setup(request.body)?.let(response::binary)
                        ?: response.fail(470, "Connection Authorization Required")
                    "/pair-verify" -> when (val result = pairing.verify(request.body)) {
                        is PairingSession.VerifyResult.Reply -> response.binary(result.body)
                        else -> {
                            response.fail(470, "Connection Authorization Required")
                            response.closeAfter = true
                        }
                    }
                    "/fp-setup" -> fpSetup(request, response)
                    // "/feedback" is a 2-second heartbeat; "/audioMode" needs no action.
                    else -> Unit
                }
                "OPTIONS" -> response.headers["Public"] =
                    "SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"
                "SETUP" -> setup(request, response)
                "RECORD" -> {
                    response.headers["Audio-Latency"] = "0"
                    response.headers["Audio-Jack-Status"] = "connected; type=analog"
                }
                "GET_PARAMETER" -> {
                    response.headers["Content-Type"] = "text/parameters"
                    response.body = "volume: %.6f\r\n".format(Locale.US, volume.currentDb()).toByteArray()
                }
                "SET_PARAMETER" -> setParameter(request)
                "FLUSH" -> Unit
                "TEARDOWN" -> teardown(request, response)
                else -> response.fail(501, "Not Implemented")
            }
        }

        private fun info(request: Request, response: Response) {
            val qualifier = if (request.isPlist) {
                ((BPlist.decode(request.body) as? Map<*, *>)?.get("qualifier") as? List<*>)?.firstOrNull() as? String
            } else {
                null
            }
            when (qualifier) {
                "txtAirPlay" -> response.plist(mapOf("txtAirPlay" to Advertiser.txtRecord(Advertiser.airPlayTxt(identity))))
                "txtRAOP" -> response.plist(mapOf("txtRAOP" to Advertiser.txtRecord(Advertiser.raopTxt(identity))))
                else -> response.plist(deviceInfo())
            }
        }

        private fun deviceInfo(): Map<String, Any?> = linkedMapOf(
            "deviceID" to identity.deviceIdColons,
            "macAddress" to identity.deviceIdColons,
            "pk" to identity.publicKey,
            "features" to Advertiser.FEATURES,
            "name" to name,
            "pi" to identity.pairingId,
            "vv" to 2,
            "statusFlags" to 68,
            "keepAliveLowPower" to true,
            "sourceVersion" to Advertiser.SOURCE_VERSION,
            "keepAliveSendStatsAsBody" to true,
            "model" to Advertiser.MODEL,
            // Where the sender's volume slider starts: the TV's own level.
            "initialVolume" to volume.currentDb().toDouble(),
            "audioLatencies" to listOf(
                linkedMapOf("type" to 100, "inputLatencyMicros" to 0, "audioType" to "default", "outputLatencyMicros" to false),
                linkedMapOf("type" to 101, "audioType" to "default", "inputLatencyMicros" to 0, "outputLatencyMicros" to false),
            ),
            "audioFormats" to listOf(
                linkedMapOf("audioOutputFormats" to 0x3fffffc, "type" to 100, "audioInputFormats" to 0x3fffffc),
                linkedMapOf("audioOutputFormats" to 0x3fffffc, "type" to 101, "audioInputFormats" to 0x3fffffc),
            ),
            "displays" to listOf(
                linkedMapOf(
                    "uuid" to DISPLAY_UUID,
                    "widthPhysical" to 0,
                    "heightPhysical" to 0,
                    "width" to DISPLAY_WIDTH,
                    "height" to DISPLAY_HEIGHT,
                    "widthPixels" to DISPLAY_WIDTH,
                    "heightPixels" to DISPLAY_HEIGHT,
                    "rotation" to false,
                    "refreshRate" to 1.0 / REFRESH_RATE,
                    "maxFPS" to MAX_FPS,
                    "overscanned" to false,
                    "features" to 14,
                ),
            ),
        )

        private fun fpSetup(request: Request, response: Response) {
            val reply = when (request.body.size) {
                16 -> fairPlay.setup(request.body)
                164 -> fairPlay.handshake(request.body)
                else -> null
            }
            if (reply != null) response.binary(reply) else response.fail(501, "Not Implemented")
        }

        @Suppress("UNCHECKED_CAST")
        private fun setup(request: Request, response: Response) {
            val body = BPlist.decode(request.body) as? Map<String, Any?> ?: emptyMap()
            val result = LinkedHashMap<String, Any?>()

            val ekey = body["ekey"] as? ByteArray
            val eiv = body["eiv"] as? ByteArray
            if (ekey != null && eiv != null) {
                val unwrapped = fairPlay.decryptKey(ekey)
                if (unwrapped == null) {
                    response.fail(400, "Bad Request")
                    return
                }
                // With legacy pairing the key is mixed with the pair-verify secret; without it, used as is.
                val key = pairing.sharedSecret?.let { sha512(unwrapped, it).copyOf(16) } ?: unwrapped
                if (BuildConfig.DEBUG) Log.d(TAG, "session key ${key.toHex()} iv ${eiv.toHex()}")

                val client = (body["name"] as? String)?.takeIf { it.isNotBlank() } ?: "iPhone"
                val timingPort = (body["timingPort"] as? Long)?.toInt() ?: 0
                takeOver()
                val newSession = MirrorSession(client, key, eiv, remote, timingPort, volume)
                session = newSession
                active = this
                result["timingPort"] = newSession.timingPort
                result["eventPort"] = 0
            }

            val streams = body["streams"] as? List<Map<String, Any?>>
            if (streams != null) {
                val current = session
                if (current == null) {
                    response.fail(455, "Method Not Valid In This State")
                    return
                }
                result["streams"] = streams.mapNotNull { stream ->
                    when ((stream["type"] as? Long)?.toInt()) {
                        STREAM_VIDEO -> {
                            val id = stream["streamConnectionID"] as? Long ?: 0L
                            val port = current.startVideo(id)
                            // Only screen mirroring gets the full-screen view; audio-only AirPlay plays in the background.
                            events.onMirroringStarted()
                            linkedMapOf("dataPort" to port, "type" to STREAM_VIDEO)
                        }
                        STREAM_AUDIO -> {
                            val ct = (stream["ct"] as? Long)?.toInt() ?: AudioPlayer.CT_AAC_ELD
                            val spf = (stream["spf"] as? Long)?.toInt() ?: if (ct == AudioPlayer.CT_ALAC) 352 else 480
                            val (data, control) = current.startAudio(ct, spf)
                            linkedMapOf("dataPort" to data, "controlPort" to control, "type" to STREAM_AUDIO)
                        }
                        else -> null
                    }
                }
            }
            response.plist(result)
        }

        /** A second sender is setting up: end whatever is mirroring now. */
        private fun takeOver() {
            val previous = active
            if (previous != null && previous !== this) {
                Log.i(TAG, "new sender takes over from the current session")
                previous.close()
            }
            endSession()
        }

        private fun setParameter(request: Request) {
            if (!request.header("content-type").orEmpty().startsWith("text/parameters")) return
            val text = String(request.body)
            if (text.startsWith("volume:")) {
                text.removePrefix("volume:").trim().toFloatOrNull()?.let { session?.setVolume(it) }
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun teardown(request: Request, response: Response) {
            val body = if (request.body.isNotEmpty()) BPlist.decode(request.body) as? Map<String, Any?> else null
            val types = (body?.get("streams") as? List<Map<String, Any?>>)?.mapNotNull { (it["type"] as? Long)?.toInt() }
            if (types.isNullOrEmpty()) {
                response.headers["Connection"] = "close"
                response.closeAfter = true
                return
            }
            val current = session ?: return
            if (STREAM_VIDEO in types) current.stopVideo()
            if (STREAM_AUDIO in types) current.stopAudio()
            // iOS may stop every stream but keep the control connection open: the session is over all the same,
            // and the mirroring screen must not sit on a frozen frame.
            if (current.isIdle) endSession()
        }

        private fun endSession() {
            val current = session ?: return
            session = null
            current.close()
            if (active === this) {
                active = null
                events.onSessionEnded()
            }
        }

        private fun readRequest(input: InputStream): Request? {
            var line: String
            do {
                line = readLine(input) ?: return null
            } while (line.isEmpty())
            val parts = line.split(' ')
            if (parts.size < 3) throw IOException("bad request line: $line")
            val headers = HashMap<String, String>()
            while (true) {
                val header = readLine(input) ?: return null
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length !in 0..MAX_BODY) throw IOException("bad content length $length")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) return null
                read += n
            }
            return Request(parts[0], parts[1], parts[2], headers, body)
        }

        private fun writeResponse(output: OutputStream, request: Request, response: Response) {
            val cseq = request.header("cseq")
            val head = StringBuilder()
            head.append(request.protocol).append(' ').append(response.status).append(' ').append(response.reason).append("\r\n")
            for ((k, v) in response.headers) head.append(k).append(": ").append(v).append("\r\n")
            if (cseq != null && request.method != "RECORD" && "Audio-Jack-Status" !in response.headers) {
                head.append("Audio-Jack-Status: connected; type=digital\r\n")
            }
            head.append("Server: AirTunes/").append(Advertiser.SOURCE_VERSION).append("\r\n")
            if (cseq != null) head.append("CSeq: ").append(cseq).append("\r\n")
            head.append("Content-Length: ").append(response.body.size).append("\r\n\r\n")
            val bytes = ByteArrayOutputStream(head.length + response.body.size)
            bytes.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            bytes.write(response.body)
            output.write(bytes.toByteArray())
            output.flush()
        }
    }

    private companion object {
        const val TAG = "MuffuqAirPlay"
        const val DEFAULT_PORT = 7000
        const val MAX_BODY = 1024 * 1024
        const val STREAM_VIDEO = 110
        const val STREAM_AUDIO = 96

        // What we tell the sender about our "display". 1080p30 is what this class of TV decodes comfortably.
        const val DISPLAY_UUID = "e0ff8a27-6738-3d56-8a16-cc53aacee925"
        const val DISPLAY_WIDTH = 1920
        const val DISPLAY_HEIGHT = 1080
        const val REFRESH_RATE = 60.0
        const val MAX_FPS = 30

        fun readLine(input: InputStream): String? {
            val bytes = ByteArrayOutputStream(128)
            while (true) {
                val b = input.read()
                if (b < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
                if (b == '\n'.code) break
                if (b != '\r'.code) bytes.write(b)
                if (bytes.size() > 8192) throw IOException("header line too long")
            }
            return bytes.toString(Charsets.ISO_8859_1.name())
        }
    }
}
