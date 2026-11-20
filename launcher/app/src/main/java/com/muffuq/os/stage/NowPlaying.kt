package com.muffuq.os.stage

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Only here so the system lets us read other apps' media sessions; it never looks at a notification.
 * Enabled by tools/muffuq.ps1 (`cmd notification allow_listener`), since Android TV has no settings screen for it.
 */
class MediaListener : NotificationListenerService()

data class Track(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val art: Bitmap?,
) {
    /** Identifies the song for lyrics and colours; the artwork may arrive a moment after the rest. */
    val key: String get() = "$artist\u0000$title\u0000$album\u0000${durationMs / 1000}"

    /**
     * The song's name without what streaming services hang on it: "All The Stars (with SZA) - From "Black
     * Panther"" -> "All The Stars". For showing, and for searching lyrics.
     */
    val shortTitle: String get() = shorten(title)

    companion object {
        private val EXTRAS = Regex("""\s*[(\[][^)\]]*[)\]]""")
        private val SUFFIX = Regex("""\s+[-–—]\s+.*$""")
        private val FEAT = Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE)

        fun shorten(title: String): String {
            val short = title.replace(EXTRAS, "").replace(SUFFIX, "").replace(FEAT, "").trim()
            return short.ifEmpty { title }
        }
    }
}

data class Playback(val positionMs: Long, val updatedAtMs: Long, val speed: Float, val playing: Boolean) {
    /** Where the song is now, extrapolated from the last report (the player only reports on changes). */
    fun positionAt(nowMs: Long = SystemClock.elapsedRealtime()): Long =
        if (playing) positionMs + ((nowMs - updatedAtMs) * speed).toLong() else positionMs
}

data class NowPlaying(val packageName: String, val track: Track, val playback: Playback)

sealed interface MediaState {
    /** The notification-listener grant is missing, so other apps' sessions can't be read. */
    data object NoAccess : MediaState
    data object Idle : MediaState
    data class Active(val nowPlaying: NowPlaying) : MediaState
}

/**
 * Follows whatever music app is playing on the TV (Spotify, also when it's driven over Spotify Connect from a
 * phone or PC) through Android's media sessions. One shared instance per process; listening stops a few seconds
 * after the last screen stops watching.
 */
class NowPlayingSource private constructor(context: Context) {
    private val app = context.applicationContext
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(app, MediaListener::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var controller: MediaController? = null

    val state: StateFlow<MediaState> = callbackFlow {
        var current: MediaController? = null
        var artKey: String? = null
        var art: Bitmap? = null

        fun publish() {
            val c = current
            val metadata = c?.metadata
            val playbackState = c?.playbackState
            if (c == null || metadata == null || metadata.title().isEmpty()) {
                trySend(MediaState.Idle)
                return
            }
            val embedded = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            val track = Track(
                title = metadata.title(),
                artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION),
                art = embedded ?: art.takeIf { artKey == metadata.artUri() },
            )
            if (embedded == null) {
                val uri = metadata.artUri()
                if (uri != null && uri != artKey) {
                    artKey = uri
                    art = null
                    scope.launch {
                        val loaded = loadArt(uri)
                        if (artKey == uri) {
                            art = loaded
                            publish()
                        }
                    }
                }
            }
            trySend(MediaState.Active(NowPlaying(c.packageName, track, playbackState.toPlayback())))
        }

        // Every active session is watched, not just the one shown: when another app starts playing (Spotify
        // resuming while something else was on), it takes over without waiting for the session list to change.
        val watched = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

        fun pick() {
            val all = watched.values.map { it.first }
            fun playing(c: MediaController?) = c?.playbackState?.state == PlaybackState.STATE_PLAYING
            // Keep the current one while it plays (no flapping between two players); else a playing one;
            // else stay on the current one if it's still around; else the most recent with something to show.
            val best = current?.takeIf { playing(it) && it.sessionToken in watched }
                ?: all.firstOrNull { playing(it) }
                ?: current?.takeIf { it.sessionToken in watched && it.metadata != null }
                ?: all.firstOrNull { it.metadata != null }
            current = best
            controller = best
            publish()
        }

        fun choose(list: List<MediaController>?) {
            val tokens = list.orEmpty().map { it.sessionToken }.toSet()
            watched.keys.filter { it !in tokens }.forEach { token ->
                watched.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) }
            }
            // The list is ordered by priority (most recent first); keep that order for picking.
            val ordered = LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
            for (c in list.orEmpty()) {
                ordered[c.sessionToken] = watched[c.sessionToken] ?: (c to object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = pick()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = pick()
                    override fun onSessionDestroyed() {
                        watched.remove(c.sessionToken)
                        pick()
                    }
                }).also { (controller, cb) -> controller.registerCallback(cb, main) }
            }
            watched.clear()
            watched.putAll(ordered)
            pick()
        }

        val changes = MediaSessionManager.OnActiveSessionsChangedListener { choose(it) }
        try {
            sessions.addOnActiveSessionsChangedListener(changes, listener, main)
            choose(sessions.getActiveSessions(listener))
        } catch (e: SecurityException) {
            Log.w(TAG, "notification listener not enabled, can't see media sessions")
            trySend(MediaState.NoAccess)
        }
        awaitClose {
            runCatching { sessions.removeOnActiveSessionsChangedListener(changes) }
            watched.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
            watched.clear()
            controller = null
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), MediaState.Idle)

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()

    // --- Home pause guard ---
    // Pressing Home on this TV stops the music playing in the background: the firmware sends "pause", and
    // Spotify also stops itself a second or two after its screen is left. Stock Android TV keeps music going.
    // Spotify's session doesn't report either reliably (it keeps saying "playing"), so the guard watches the sound
    // itself: which session was playing, and whether the sound stops within a few seconds of Home.

    private var musicSession: MediaController? = null
    private var musicStoppedAt = 0L
    private var musicPlaying = false
    private var homeAt = 0L
    private var guarding = false
    private var audio: android.media.AudioManager? = null
    private val recheck = Runnable { evaluateMusic() }

    /** Starts watching playback for [undoHomePause]. Cheap; called once by the always-running AirPlay service. */
    fun guardHome() {
        if (guarding) return
        guarding = true
        val manager = app.getSystemService(android.media.AudioManager::class.java)
        audio = manager
        musicPlaying = manager.isMusicActive
        manager.registerAudioPlaybackCallback(object : android.media.AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) {
                evaluateMusic()
                // The "music active" flag trails the players by a moment; look again once it has settled.
                main.removeCallbacks(recheck)
                main.postDelayed(recheck, 450)
            }
        }, main)
    }

    private fun evaluateMusic() {
        // Player states aren't public, but whether music is sounding right now is.
        val playing = audio?.isMusicActive ?: return
        val now = SystemClock.elapsedRealtime()
        if (playing && !musicPlaying) {
            // Remember whose music this is while it plays (once stopped it may leave the active list).
            musicSession = runCatching {
                val all = sessions.getActiveSessions(listener)
                all.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: all.firstOrNull()
            }.getOrNull() ?: musicSession
        }
        if (!playing && musicPlaying) {
            musicStoppedAt = now
            if (now - homeAt <= HOME_PAUSE_WINDOW_MS) resumeMusic()
        }
        musicPlaying = playing
    }

    /** Called as Home brings the launcher up: music stopping around now is put straight back to playing. */
    fun undoHomePause() {
        val now = SystemClock.elapsedRealtime()
        homeAt = now
        // The firmware's pause lands a moment before the launcher hears about Home. A stop any earlier than that
        // was the user pausing before leaving: left alone.
        if (!musicPlaying && now - musicStoppedAt <= 600) resumeMusic()
    }

    private fun resumeMusic() {
        val session = musicSession ?: return
        Log.i(TAG, "music stopped by Home in ${session.packageName}; resuming")
        runCatching { session.transportControls.play() }
    }

    fun previous() = controller?.transportControls?.skipToPrevious()

    private suspend fun loadArt(uri: String): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val stream = if (uri.startsWith("http")) {
                (URL(uri).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5_000
                    readTimeout = 5_000
                }.inputStream
            } else {
                app.contentResolver.openInputStream(android.net.Uri.parse(uri))
            }
            stream?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "MuffuqStage"
        private const val HOME_PAUSE_WINDOW_MS = 4_000L

        @Volatile private var instance: NowPlayingSource? = null

        fun get(context: Context): NowPlayingSource =
            instance ?: synchronized(this) { instance ?: NowPlayingSource(context).also { instance = it } }

        private fun MediaMetadata.title(): String =
            getString(MediaMetadata.METADATA_KEY_TITLE) ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty()

        private fun MediaMetadata.artUri(): String? =
            getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: getString(MediaMetadata.METADATA_KEY_ART_URI)

        private fun PlaybackState?.toPlayback(): Playback {
            if (this == null) return Playback(0, SystemClock.elapsedRealtime(), 1f, false)
            val playing = state == PlaybackState.STATE_PLAYING
            // Some players never fill in the update time; counting from boot would put the song hours ahead.
            val updated = lastPositionUpdateTime.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
            return Playback(position, updated, if (playing) playbackSpeed.takeIf { it > 0f } ?: 1f else 0f, playing)
        }
    }
}
