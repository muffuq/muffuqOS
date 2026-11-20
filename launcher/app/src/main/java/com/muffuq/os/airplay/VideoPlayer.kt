package com.muffuq.os.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * H.264 decoding straight onto the mirroring screen's Surface with the TV's hardware decoder.
 *
 * Frames can arrive before the screen exists (the activity is still starting), so they're queued from the last
 * IDR on: anything older can't be decoded without it anyway. All codec work happens on one handler thread.
 */
class VideoPlayer(
    private val onSize: (width: Int, height: Int) -> Unit,
    private val onPause: (Boolean) -> Unit,
    private val onFirstFrame: () -> Unit,
) {
    private class Frame(val data: ByteArray, val ptsUs: Long)

    private val thread = HandlerThread("airplay-decoder").apply { start() }
    private val handler = Handler(thread.looper)

    // Only touched on [handler]'s thread.
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var width = 1920
    private var height = 1080
    private val queue = ArrayDeque<Frame>()
    private var queuedBytes = 0
    private val freeInputs = ArrayDeque<Int>()
    private var released = false
    private var rendered = false

    fun setSurface(surface: Surface?) = post {
        this.surface = surface
        restartCodec()
    }

    fun onConfig(sps: ByteArray, pps: ByteArray, width: Int, height: Int) = post {
        if (width > 0 && height > 0) {
            this.width = width
            this.height = height
            onSize(width, height)
        }
        if (!sps.contentEquals(this.sps) || !pps.contentEquals(this.pps)) {
            this.sps = sps
            this.pps = pps
            restartCodec()
        }
    }

    fun onPaused(paused: Boolean) = onPause(paused)

    fun onFrame(data: ByteArray, ptsUs: Long) = post {
        if (containsIdr(data)) {
            queue.clear()
            queuedBytes = 0
        }
        if (queuedBytes + data.size > MAX_QUEUED_BYTES) {
            Log.w(TAG, "decoder not keeping up, dropping a frame")
            return@post
        }
        queue.addLast(Frame(data, ptsUs))
        queuedBytes += data.size
        feed()
    }

    fun release() = post {
        released = true
        stopCodec()
        queue.clear()
        thread.quitSafely()
    }

    private fun post(block: () -> Unit) {
        handler.post { if (!released) block() }
    }

    private fun restartCodec() {
        stopCodec()
        val surface = surface ?: return
        val sps = sps ?: return
        val pps = pps ?: return
        if (!surface.isValid) return
        try {
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_FRAME_BYTES)
                // Only when advertised: some TV decoders (MStar) fail configure() outright on unknown keys.
                if (supportsLowLatency(decoder)) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            codec = decoder.apply {
                setCallback(callback, handler)
                configure(format, surface, null, 0)
                start()
            }
            Log.i(TAG, "decoder ${codec?.name} started at ${width}x$height")
        } catch (e: Exception) {
            Log.e(TAG, "could not start video decoder", e)
            stopCodec()
        }
    }

    private fun stopCodec() {
        freeInputs.clear()
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
    }

    private fun feed() {
        val codec = codec ?: return
        while (freeInputs.isNotEmpty() && queue.isNotEmpty()) {
            val frame = queue.removeFirst()
            queuedBytes -= frame.data.size
            val index = freeInputs.removeFirst()
            try {
                val buffer = codec.getInputBuffer(index) ?: continue
                if (frame.data.size > buffer.capacity()) {
                    freeInputs.addFirst(index)
                    continue
                }
                buffer.clear()
                buffer.put(frame.data)
                codec.queueInputBuffer(index, 0, frame.data.size, frame.ptsUs, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "decoder rejected input", e)
                return
            }
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(mc: MediaCodec, index: Int) {
            if (mc !== codec) return
            freeInputs.addLast(index)
            feed()
        }

        override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (mc !== codec) return
            // Mirroring is live: show every frame as soon as it's decoded.
            runCatching { mc.releaseOutputBuffer(index, true) }
            if (!rendered && info.size > 0) {
                rendered = true
                onFirstFrame()
            }
        }

        override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
            if (mc !== codec) return
            Log.e(TAG, "video decoder error, restarting", e)
            restartCodec()
        }

        override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val cropW = if (format.containsKey("crop-right")) format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else w
            val cropH = if (format.containsKey("crop-bottom")) format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else h
            onSize(cropW, cropH)
        }
    }

    private companion object {
        const val TAG = "MuffuqAirPlay"

        fun supportsLowLatency(codec: MediaCodec): Boolean = Build.VERSION.SDK_INT >= 30 &&
            runCatching {
                codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
            }.getOrDefault(false)
        const val MAX_FRAME_BYTES = 2 * 1024 * 1024
        const val MAX_QUEUED_BYTES = 16 * 1024 * 1024

        fun containsIdr(annexB: ByteArray): Boolean {
            var i = 0
            while (i + 4 < annexB.size) {
                if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1) {
                    if (annexB[i + 4].toInt() and 0x1F == 5) return true
                    i += 4
                } else {
                    i++
                }
            }
            return false
        }
    }
}
