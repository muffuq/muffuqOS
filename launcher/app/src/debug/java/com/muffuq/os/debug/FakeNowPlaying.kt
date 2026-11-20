package com.muffuq.os.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

/**
 * Debug builds only: a silent media session that looks like Spotify playing a song, to test the Stage without
 * sound or a Spotify account.
 *
 *   adb shell am broadcast -n com.muffuq.os/.debug.FakeNowPlaying --es title Nightcall --es artist Kavinsky \
 *       --el duration 258000 --el position 20000
 *   adb shell am broadcast -n com.muffuq.os/.debug.FakeNowPlaying --ez pause true
 *   adb shell am broadcast -n com.muffuq.os/.debug.FakeNowPlaying --ez stop true
 */
class FakeNowPlaying : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("stop", false)) {
            session?.release()
            session = null
            Log.i(TAG, "fake session stopped")
            return
        }
        val s = session ?: MediaSession(context.applicationContext, "muffuq-fake").also { session = it }
        intent.getStringExtra("title")?.let { title ->
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, intent.getStringExtra("artist").orEmpty())
                    .putString(MediaMetadata.METADATA_KEY_ALBUM, intent.getStringExtra("album").orEmpty())
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, intent.getLongExtra("duration", 0))
                    .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, intent.getStringExtra("art"))
                    .build(),
            )
        }
        val paused = intent.getBooleanExtra("pause", false)
        val position = if (intent.hasExtra("position")) intent.getLongExtra("position", 0) else lastPosition()
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, position, if (paused) 0f else 1f, SystemClock.elapsedRealtime())
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .build(),
        )
        s.isActive = true
        Log.i(TAG, "fake session: ${if (paused) "paused" else "playing"} at ${position / 1000}s")
    }

    private fun lastPosition(): Long {
        val state = session?.controller?.playbackState ?: return 0
        if (state.state != PlaybackState.STATE_PLAYING) return state.position
        return state.position + (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime)
    }

    private companion object {
        const val TAG = "MuffuqStage"
        var session: MediaSession? = null
    }
}
