package com.muffuq.os.stage

import android.content.Context
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

sealed interface LyricsResult {
    data class Found(val lyrics: Lyrics) : LyricsResult
    data object Instrumental : LyricsResult
    data object NotFound : LyricsResult
    /** A source couldn't be reached: nothing is cached, the next play of the song tries again. */
    data object Unavailable : LyricsResult
}

/**
 * Synced lyrics from two free sources, asked in parallel:
 * - NetEase: real word timings ("yrc") for much international music, plain line timings otherwise.
 * - LRCLIB: community line timings, the better one for Turkish songs.
 * Word timings win; then LRCLIB. Matches must agree on title, artist and (when known) duration, so a
 * same-named song by someone else never shows up.
 */
class LyricsRepository(context: Context) {
    private val dir = File(context.cacheDir, "lyrics").apply { mkdirs() }
    private val memory = LruCache<String, LyricsResult>(8)

    suspend fun load(track: Track): LyricsResult = withContext(Dispatchers.IO) {
        val key = cacheKey(track)
        memory.get(key)?.let { return@withContext it }
        readCache(key)?.let { return@withContext it.also { r -> memory.put(key, r) } }

        val (netease, lrclib) = coroutineScope {
            val a = async { runCatching { NetEase.find(track) } }
            val b = async { runCatching { Lrclib.find(track) } }
            a.await() to b.await()
        }
        netease.exceptionOrNull()?.let { Log.w(TAG, "NetEase: ${it.message}") }
        lrclib.exceptionOrNull()?.let { Log.w(TAG, "LRCLIB: ${it.message}") }

        val candidates = listOfNotNull(lrclib.getOrNull(), netease.getOrNull()).filterIsInstance<Source.Text>()
        val parsed = candidates.mapNotNull { c -> c.parse()?.let { c to it } }
        val best = parsed.firstOrNull { it.second.wordTimed } ?: parsed.firstOrNull()
        val result = when {
            best != null -> LyricsResult.Found(best.second)
            lrclib.getOrNull() == Source.Instrumental -> LyricsResult.Instrumental
            netease.isSuccess && lrclib.isSuccess -> LyricsResult.NotFound
            else -> LyricsResult.Unavailable
        }
        Log.i(TAG, "lyrics for '${track.title}' by ${track.artist}: " +
            (best?.let { "${it.first.source}, ${it.second.lines.size} lines, word timed ${it.second.wordTimed}" } ?: result.toString()))
        if (result != LyricsResult.Unavailable) {
            memory.put(key, result)
            writeCache(key, best?.first ?: if (result == LyricsResult.Instrumental) Source.Instrumental else Source.None)
        }
        result
    }

    // --- sources ---

    private sealed interface Source {
        data class Text(val format: String, val text: String, val source: String) : Source {
            fun parse(): Lyrics? = if (format == "yrc") Lyrics.parseYrc(text, source) else Lyrics.parseLrc(text, source)
        }
        data object Instrumental : Source
        data object None : Source
    }

    private object NetEase {
        private val headers = mapOf("User-Agent" to "Mozilla/5.0", "Referer" to "https://music.163.com/")

        fun find(track: Track): Source {
            // The plain name: "(with SZA) - From …" in the query makes NetEase match some other song.
            val query = URLEncoder.encode("${track.shortTitle} ${primaryArtist(track.artist)}", "UTF-8")
            val search = JSONObject(get("https://music.163.com/api/search/get?s=$query&type=1&limit=10", headers) ?: return Source.None)
            val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return Source.None
            val song = songs.objects().firstOrNull { s ->
                titleMatches(track.title, s.optString("name")) &&
                    artistMatches(track.artist, s.optJSONArray("artists").objects().map { it.optString("name") }) &&
                    durationMatches(track.durationMs, s.optLong("duration"))
            } ?: return Source.None
            val lyric = JSONObject(
                get("https://music.163.com/api/song/lyric/v1?id=${song.optLong("id")}&lv=0&yv=0&tv=0&rv=0&kv=0", headers)
                    ?: return Source.None,
            )
            val yrc = lyric.optJSONObject("yrc")?.optString("lyric").orEmpty()
            if (yrc.isNotBlank()) return Source.Text("yrc", yrc, "NetEase")
            val lrc = lyric.optJSONObject("lrc")?.optString("lyric").orEmpty()
            return if (TIMED.containsMatchIn(lrc)) Source.Text("lrc", lrc, "NetEase") else Source.None
        }
    }

    private object Lrclib {
        private val headers = mapOf("User-Agent" to "muffuqOS/1.0 (Android TV launcher)")

        fun find(track: Track): Source {
            val params = buildString {
                append("track_name=").append(enc(track.title))
                append("&artist_name=").append(enc(primaryArtist(track.artist)))
                if (track.album.isNotBlank()) append("&album_name=").append(enc(track.album))
                if (track.durationMs > 0) append("&duration=").append(track.durationMs / 1000)
            }
            get("https://lrclib.net/api/get?$params", headers)?.let { body -> pick(JSONObject(body))?.let { return it } }
            // The exact lookup is strict about album and duration; search is looser, then we check ourselves.
            val results = JSONArray(
                get("https://lrclib.net/api/search?track_name=${enc(track.shortTitle)}&artist_name=${enc(primaryArtist(track.artist))}", headers)
                    ?: return Source.None,
            )
            return results.objects().firstOrNull { r ->
                titleMatches(track.title, r.optString("trackName")) &&
                    artistMatches(track.artist, listOf(r.optString("artistName"))) &&
                    durationMatches(track.durationMs, (r.optDouble("duration", 0.0) * 1000).toLong()) &&
                    (r.optBoolean("instrumental") || r.optString("syncedLyrics").isNotBlank())
            }?.let(::pick) ?: Source.None
        }

        private fun pick(r: JSONObject): Source? = when {
            r.optBoolean("instrumental") -> Source.Instrumental
            r.optString("syncedLyrics").isNotBlank() && r.optString("syncedLyrics") != "null" ->
                Source.Text("lrc", r.optString("syncedLyrics"), "LRCLIB")
            else -> null
        }
    }

    // --- cache ---

    private fun cacheKey(track: Track): String {
        val raw = "${norm(track.artist)}|${norm(track.title)}|${track.durationMs / 2000}"
        return MessageDigest.getInstance("SHA-1").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): LyricsResult? = runCatching {
        val file = File(dir, "$key.json")
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        when (json.getString("f")) {
            "none" -> if (System.currentTimeMillis() - json.getLong("at") < NOT_FOUND_TTL_MS) LyricsResult.NotFound else null
            "instrumental" -> LyricsResult.Instrumental
            else -> Source.Text(json.getString("f"), json.getString("t"), json.getString("s")).parse()?.let { LyricsResult.Found(it) }
        }
    }.getOrNull()

    private fun writeCache(key: String, source: Source) {
        runCatching {
            val json = JSONObject().put("at", System.currentTimeMillis())
            when (source) {
                is Source.Text -> json.put("f", source.format).put("t", source.text).put("s", source.source)
                Source.Instrumental -> json.put("f", "instrumental")
                Source.None -> json.put("f", "none")
            }
            File(dir, "$key.json").writeText(json.toString())
        }
    }

    companion object {
        private const val TAG = "MuffuqStage"
        private const val NOT_FOUND_TTL_MS = 3L * 24 * 60 * 60 * 1000
        private const val DURATION_TOLERANCE_MS = 4_000
        private val TIMED = Regex("""\[\d{1,3}:\d{1,2}""")

        private fun get(url: String, headers: Map<String, String>): String? {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 7_000
                connection.readTimeout = 7_000
                headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                return when (val code = connection.responseCode) {
                    200 -> connection.inputStream.bufferedReader().use { it.readText() }
                    404 -> null
                    else -> throw IOException("HTTP $code for ${url.substringBefore('?')}")
                }
            } finally {
                connection.disconnect()
            }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        /** "KÖFN, Salman Tin" -> "KÖFN": sources list featured artists differently, the first one is reliable. */
        private fun primaryArtist(artist: String) = artist.split(',', '&', ';', '/').first().trim()

        /** Lowercase, accents and Turkish letters folded, "(feat. …)" / "- Remastered" dropped, punctuation gone. */
        internal fun norm(s: String, dropSuffix: Boolean = true): String {
            var t = s.lowercase(Locale.ROOT)
            t = t.replace(Regex("""\(.*?\)|\[.*?]"""), " ")
            if (dropSuffix) t = t.substringBefore(" - ")
            t = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('ı', 'i')
            return t.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        }

        private fun titleMatches(wanted: String, candidate: String): Boolean {
            val a = norm(wanted)
            val b = norm(candidate)
            return a.isNotEmpty() && (a == b || (minOf(a.length, b.length) >= 4 && (a.contains(b) || b.contains(a))))
        }

        private fun artistMatches(wanted: String, candidates: List<String>): Boolean {
            val want = wanted.split(',', '&', ';', '/').map { norm(it, false) }.filter { it.isNotEmpty() }
            val have = candidates.flatMap { it.split(',', '&', ';', '/') }.map { norm(it, false) }.filter { it.isNotEmpty() }
            return want.any { w -> have.any { h -> h == w || (minOf(h.length, w.length) >= 4 && (h.contains(w) || w.contains(h))) } }
        }

        private fun durationMatches(wantedMs: Long, candidateMs: Long) =
            wantedMs <= 0 || candidateMs <= 0 || abs(wantedMs - candidateMs) <= DURATION_TOLERANCE_MS
    }
}
