package com.muffuq.os.stage

import android.media.audiofx.Visualizer
import android.os.SystemClock
import android.util.Log
import kotlin.math.hypot
import kotlin.math.max

/**
 * Beats from whatever the TV is playing, via the system visualizer on the output mix (session 0): Spotify's
 * audio itself is out of reach, but its sound is not. Kick-drum energy (≈40–160 Hz) jumping well above its
 * recent average counts as a beat. Needs RECORD_AUDIO (the visualizer API's rule, the microphone is never used).
 *
 * Read [beatAgeMs] / [beatStrength] / [level] from the draw loop; they are plain fields, written ~20×/s.
 */
class AudioPulse {
    private var visualizer: Visualizer? = null
    private var average = 0f

    @Volatile var lastBeatAtMs = 0L
        private set
    @Volatile var beatStrength = 0f
        private set
    /** Smoothed loudness 0..1, for a glow that breathes with the music between beats. */
    @Volatile var level = 0f
        private set
    @Volatile var working = false
        private set

    fun beatAgeMs(now: Long = SystemClock.elapsedRealtime()): Long = if (lastBeatAtMs == 0L) Long.MAX_VALUE else now - lastBeatAtMs

    fun start(): Boolean {
        if (visualizer != null) return working
        return try {
            val v = Visualizer(0)
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(visualizer: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                override fun onFftDataCapture(visualizer: Visualizer, fft: ByteArray, samplingRate: Int) = analyse(fft, samplingRate)
            }, Visualizer.getMaxCaptureRate(), false, true)
            v.enabled = true
            visualizer = v
            working = true
            Log.i(TAG, "visualizer on output mix: ${v.captureSize} samples, ${Visualizer.getMaxCaptureRate() / 1000} captures/s")
            true
        } catch (e: Exception) {
            // No RECORD_AUDIO grant, or the vendor doesn't allow capturing the output mix.
            Log.w(TAG, "visualizer unavailable: ${e.message}")
            working = false
            false
        }
    }

    fun stop() {
        visualizer?.run {
            runCatching { enabled = false }
            release()
        }
        visualizer = null
        working = false
    }

    /** [fft]: [Re0, Re(n/2), Re1, Im1, Re2, Im2, …]; [samplingRate] in milliHertz. */
    private fun analyse(fft: ByteArray, samplingRate: Int) {
        val bins = fft.size / 2
        val hzPerBin = samplingRate / 1000f / 2f / bins
        val low = max(1, (40f / hzPerBin).toInt())
        val high = max(low + 1, (160f / hzPerBin).toInt()).coerceAtMost(bins - 1)
        var bass = 0f
        for (k in low..high) bass += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
        bass /= (high - low + 1)
        var all = 0f
        for (k in 1 until bins) all += hypot(fft[2 * k].toFloat(), fft[2 * k + 1].toFloat())
        all /= (bins - 1)

        level = level * 0.7f + (all / 24f).coerceIn(0f, 1f) * 0.3f
        val now = SystemClock.elapsedRealtime()
        // Silence (paused, or the visualizer sees nothing): let the glow rest, don't invent beats.
        if (bass < 2f) {
            average *= 0.9f
            return
        }
        if (bass > average * 1.35f && bass > average + 3f && now - lastBeatAtMs > MIN_BEAT_GAP_MS) {
            lastBeatAtMs = now
            beatStrength = ((bass - average) / (average + 1f)).coerceIn(0.25f, 1f)
        }
        average = average * 0.88f + bass * 0.12f
    }

    private companion object {
        const val TAG = "MuffuqStage"
        /** ~220 BPM at most; also keeps one kick from counting twice across captures. */
        const val MIN_BEAT_GAP_MS = 270L
    }
}
