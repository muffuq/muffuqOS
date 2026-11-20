package com.muffuq.os.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.muffuq.os.BuildConfig
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.pow

/**
 * Decodes and plays AirPlay audio with low latency: AAC-ELD (screen mirroring), ALAC (music sent to the TV as a
 * speaker) or AAC-LC. For AAC the software decoder is chosen on purpose: TV hardware AAC decoders can't do ELD.
 */
class AudioPlayer(private val compressionType: Int, private val samplesPerFrame: Int) {
    private val frames = LinkedBlockingQueue<ByteArray>(MAX_QUEUED)
    @Volatile private var running = true
    @Volatile private var gain = 1f
    @Volatile private var track: AudioTrack? = null

    private val csd: ByteArray = when (compressionType) {
        // ELD, 44.1 kHz, stereo; frameLengthFlag picks 480 (what iOS sends) or 512 samples per frame.
        CT_AAC_ELD -> byteArrayOf(0xF8.toByte(), 0xE8.toByte(), if (samplesPerFrame == 512) 0x40 else 0x50, 0x00)
        else -> byteArrayOf(0x12, 0x10) // AAC-LC, 44.1 kHz, stereo
    }

    private val worker = thread(name = "airplay-audio-out", isDaemon = true) { play() }

    fun enqueue(frame: ByteArray) {
        if (!frames.offer(frame)) {
            frames.poll() // fell behind: drop the oldest rather than add latency
            frames.offer(frame)
        }
    }

    /** AirPlay volume: 0 dB = full, -30 dB = quietest step, -144 = mute. */
    fun setVolume(db: Float) {
        gain = if (db <= -144f) 0f else 10f.pow(db.coerceIn(-30f, 0f) / 20f)
        track?.setVolume(gain)
    }

    private fun play() {
        var audioTrack: AudioTrack? = null
        try {
            audioTrack = createTrack()
            audioTrack.setVolume(gain)
            audioTrack.play()
            track = audioTrack
            if (compressionType == CT_ALAC) playAlac(audioTrack) else playAac(audioTrack)
        } catch (e: Exception) {
            if (running) Log.e(TAG, "audio playback failed", e)
        } finally {
            track = null
            runCatching { audioTrack?.stop() }
            audioTrack?.release()
        }
    }

    private fun playAlac(out: AudioTrack) {
        AlacDecoder(samplesPerFrame, SAMPLE_RATE).use { alac ->
            Log.i(TAG, "audio decoder ALAC")
            while (running) {
                val frame = frames.poll(20, TimeUnit.MILLISECONDS) ?: continue
                val size = alac.decode(frame)
                if (size <= 0) continue
                out.write(alac.pcm, 0, size)
                if (BuildConfig.DEBUG) logLevel(alac.pcm, size)
            }
        }
    }

    private fun playAac(out: AudioTrack) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 2).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        val codec = MediaCodec.createByCodecName(pickAacDecoder())
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            Log.i(TAG, "audio decoder ${codec.name}")
            val info = MediaCodec.BufferInfo()
            var pcm = ByteArray(0)
            while (running) {
                val frame = frames.poll(20, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        codec.getInputBuffer(index)!!.apply {
                            clear()
                            put(frame)
                        }
                        codec.queueInputBuffer(index, 0, frame.size, 0, 0)
                    }
                }
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 0)
                    if (index < 0) break
                    if (info.size > 0) {
                        if (pcm.size < info.size) pcm = ByteArray(info.size)
                        codec.getOutputBuffer(index)!!.apply {
                            position(info.offset)
                            get(pcm, 0, info.size)
                        }
                        out.write(pcm, 0, info.size)
                        if (BuildConfig.DEBUG) logLevel(pcm, info.size)
                    }
                    codec.releaseOutputBuffer(index, false)
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    private fun createTrack(): AudioTrack {
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE * 4 / 10))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun close() {
        running = false
    }

    private var debugFrames = 0
    private var debugSquares = 0.0
    private var debugSamples = 0

    /** Debug builds: proves real audio is being decoded even when the stream is muted. */
    private fun logLevel(pcm: ByteArray, size: Int) {
        for (i in 0 until size / 2) {
            val sample = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
            debugSquares += sample * sample
        }
        debugSamples += size / 2
        if (++debugFrames % 100 == 0) {
            Log.d(TAG, "audio: $debugFrames frames decoded, rms %.0f".format(kotlin.math.sqrt(debugSquares / debugSamples)))
            debugSquares = 0.0
            debugSamples = 0
        }
    }

    companion object {
        private const val TAG = "MuffuqAirPlay"
        private const val SAMPLE_RATE = 44100
        private const val MAX_QUEUED = 64
        const val CT_ALAC = 2
        const val CT_AAC_ELD = 8

        /** Prefer the platform's software AAC decoder (Fraunhofer FDK): it handles ELD everywhere. */
        private fun pickAacDecoder(): String {
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) }
            }
            return (decoders.firstOrNull { it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.") } ?: decoders.first()).name
        }
    }
}
