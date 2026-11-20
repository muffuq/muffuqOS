package com.muffuq.os.airplay

import java.nio.ByteBuffer

/**
 * Apple Lossless decoder (Apple's open-source codec, compiled in src/main/cpp/alac). AirPlay audio from the Music
 * app and friends arrives as ALAC; Android has no ALAC decoder of its own.
 */
internal class AlacDecoder(samplesPerFrame: Int, sampleRate: Int) : AutoCloseable {
    private var handle = nativeCreate(config(samplesPerFrame, sampleRate))
    val pcm = ByteArray(samplesPerFrame * CHANNELS * 2)

    init {
        check(handle != 0L) { "ALAC decoder rejected its configuration" }
    }

    /** Decodes [frame] into [pcm]; returns the number of PCM bytes, or -1 for a broken frame. */
    fun decode(frame: ByteArray): Int = nativeDecode(handle, frame, frame.size, pcm)

    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private companion object {
        const val CHANNELS = 2

        init {
            System.loadLibrary("muffuq_native")
        }

        /** ALACSpecificConfig, the "magic cookie": AirPlay always uses these values (16-bit stereo). */
        fun config(samplesPerFrame: Int, sampleRate: Int): ByteArray = ByteBuffer.allocate(24)
            .putInt(samplesPerFrame)
            .put(0) // compatible version
            .put(16) // bit depth
            .put(40) // pb
            .put(10) // mb
            .put(14) // kb
            .put(CHANNELS.toByte())
            .putShort(255) // max run
            .putInt(0) // max frame bytes (unknown)
            .putInt(0) // average bit rate (unknown)
            .putInt(sampleRate)
            .array()

        @JvmStatic external fun nativeCreate(config: ByteArray): Long
        @JvmStatic external fun nativeDecode(handle: Long, frame: ByteArray, length: Int, pcm: ByteArray): Int
        @JvmStatic external fun nativeDestroy(handle: Long)
    }
}
