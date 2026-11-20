package com.muffuq.os.airplay

import android.content.Context
import android.media.AudioManager
import android.util.Log
import kotlin.math.roundToInt

/**
 * Lets the iPhone's volume buttons drive the TV's own speaker volume (with the TV's volume bar), the way they drive
 * an Apple TV, instead of only attenuating the mirrored audio under whatever level the TV happens to be at.
 *
 * AirPlay volume is the sender's slider position in dB: -30 (bottom) .. 0 (top), -144 = muted. The TV level is
 * reported in /info and GET_PARAMETER so the slider starts where the TV is.
 */
class TvVolume(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    /** TV volume steps the sender's whole slider covers: [STEPS_PER_PRESS] for each of its button presses. */
    private val range = minOf(max, STEPS_PER_PRESS * SENDER_PRESSES)

    /** False on fixed-volume outputs (e.g. TV audio passed through to an amplifier): only the stream can be scaled. */
    val controlsTv: Boolean get() = range > 0 && !audio.isVolumeFixed

    /** The TV's current volume as AirPlay dB; anything above [range] shows as the slider's top. */
    fun currentDb(): Float {
        val index = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (index <= 0 || range <= 0) MUTED_DB else MIN_DB * (1f - minOf(index, range).toFloat() / range)
    }

    /**
     * One sender's slider. Followed relatively: each move shifts the TV by the same share of [range], so if the TV
     * remote changed the volume in between, the next press continues from there instead of jumping back.
     */
    inner class Link {
        private var slider: Float? = null
        private var carry = 0f

        fun onSenderVolume(db: Float) {
            val target = sliderOf(db)
            val previous = slider
            slider = target
            val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (previous == null) {
                // The first value of a session is the sender saying where its slider is (normally the level we
                // reported). Take it as the starting point, never as a reason to jump the TV somewhere else.
                Log.i(TAG, "sender volume %.2f dB, TV at %d/%d".format(db, current, max))
                return
            }
            val index = if (db <= MUTED_DB) {
                carry = 0f
                0
            } else {
                val wanted = current + (target - previous) * range + carry
                wanted.roundToInt().coerceIn(0, max).also { carry = (wanted - it).coerceIn(-0.5f, 0.5f) }
            }
            Log.i(TAG, "sender volume %.2f dB: TV %d -> %d/%d".format(db, current, index, max))
            if (index != current) audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, AudioManager.FLAG_SHOW_UI)
        }
    }

    companion object {
        private const val TAG = "MuffuqAirPlay"
        const val MUTED_DB = -144f
        private const val MIN_DB = -30f

        /** iOS volume buttons go from silent to full in 16 presses. */
        private const val SENDER_PRESSES = 16
        private const val STEPS_PER_PRESS = 3

        /** Slider position 0..1 (UxPlay's linear mapping). */
        fun sliderOf(db: Float): Float = if (db <= MUTED_DB) 0f else ((db - MIN_DB) / -MIN_DB).coerceIn(0f, 1f)
    }
}
