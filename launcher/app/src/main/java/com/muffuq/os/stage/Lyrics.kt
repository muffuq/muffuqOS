package com.muffuq.os.stage

/**
 * Time-coded lyrics. Every line carries word timings: real ones when the source has them ([wordTimed]),
 * otherwise spread over the line by word length, which still reads as karaoke.
 */
class Lyrics(val lines: List<LyricLine>, val wordTimed: Boolean, val source: String) {
    private val starts = LongArray(lines.size) { lines[it].startMs }

    /** The line being sung at [positionMs] (or the last one started); -1 before the first. */
    fun lineAt(positionMs: Long): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        /** A line without real word timings is sung over at most this much per word, plus a little. */
        private const val EST_MS_PER_WORD = 560L
        private const val EST_EXTRA_MS = 900L
        private const val MAX_LINE_MS = 9_000L
        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
        private val WORD_TAG = Regex("""<(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)>""")
        private val OFFSET_TAG = Regex("""^\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
        private val YRC_LINE = Regex("""^\[(\d+),(\d+)](.*)$""")
        private val YRC_WORD = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

        /** Credits some sources put in the first lines ("作词 : …", "Composer: …"). */
        private val CREDIT = Regex("""^\s*(作词|作曲|编曲|制作|混音|母带|监制|出品|录音|和声|吉他|贝斯|鼓|弦乐|lyrics?|composer|producer|arranger|written by)\s*[:：]""", RegexOption.IGNORE_CASE)

        /** Plain or enhanced LRC ([mm:ss.xx]line, optionally <mm:ss.xx>word timings). */
        fun parseLrc(text: String, source: String): Lyrics? {
            var offset = 0L
            val raw = ArrayList<Triple<Long, String, List<Pair<Long, String>>?>>()
            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                OFFSET_TAG.find(line)?.let { offset = it.groupValues[1].toLong() }
                // A line may repeat: [00:12.00][01:40.00]chorus
                val times = ArrayList<Long>()
                var body = line
                while (true) {
                    val tag = TIME_TAG.find(body)?.takeIf { it.range.first == 0 } ?: break
                    times += toMs(tag.groupValues[1], tag.groupValues[2]) - offset
                    body = body.substring(tag.range.last + 1)
                }
                if (times.isEmpty()) continue
                val words = if (WORD_TAG.containsMatchIn(body)) {
                    WORD_TAG.findAll(body).mapNotNull { m ->
                        val end = WORD_TAG.find(body, m.range.last + 1)?.range?.first ?: body.length
                        val word = body.substring(m.range.last + 1, end).trim()
                        if (word.isEmpty()) null else toMs(m.groupValues[1], m.groupValues[2]) - offset to word
                    }.toList()
                } else {
                    null
                }
                val plain = WORD_TAG.replace(body, "").trim()
                for (time in times) raw += Triple(time, plain, words)
            }
            raw.sortBy { it.first }
            val lines = ArrayList<LyricLine>()
            var wordTimed = true
            raw.forEachIndexed { i, (start, text, words) ->
                if (text.isEmpty() || (start < 15_000 && CREDIT.containsMatchIn(text))) return@forEachIndexed
                val next = raw.getOrNull(i + 1)?.first ?: (start + MAX_LINE_MS)
                if (words != null && words.isNotEmpty()) {
                    val timed = words.mapIndexed { w, (ws, word) ->
                        val we = words.getOrNull(w + 1)?.first ?: minOf(next, ws + 1_200)
                        LyricWord(word, ws, maxOf(we, ws + 80))
                    }
                    lines += LyricLine(start, maxOf(timed.last().endMs, start), timed)
                } else {
                    wordTimed = false
                    lines += estimated(start, next, text)
                }
            }
            return if (lines.isEmpty()) null else Lyrics(lines, wordTimed, source)
        }

        /** NetEase "yrc": [lineStart,lineDuration](wordStart,wordDuration,0)word(…)word… in milliseconds. */
        fun parseYrc(text: String, source: String): Lyrics? {
            val lines = ArrayList<LyricLine>()
            for (rawLine in text.lineSequence()) {
                val m = YRC_LINE.find(rawLine.trim()) ?: continue
                val start = m.groupValues[1].toLong()
                val duration = m.groupValues[2].toLong()
                // Syllables carry their own trailing space; glue the pieces of each word back together.
                val words = ArrayList<LyricWord>()
                var pending = StringBuilder()
                var pendingStart = -1L
                var pendingEnd = 0L
                for (w in YRC_WORD.findAll(m.groupValues[3])) {
                    val ws = w.groupValues[1].toLong()
                    val we = ws + w.groupValues[2].toLong()
                    val piece = w.groupValues[3]
                    if (pendingStart < 0) pendingStart = ws
                    pending.append(piece.trimEnd())
                    pendingEnd = we
                    if (piece.endsWith(' ') || piece.isBlank()) {
                        if (pending.isNotBlank()) words += LyricWord(pending.toString().trim(), pendingStart, pendingEnd)
                        pending = StringBuilder()
                        pendingStart = -1
                    }
                }
                if (pending.isNotBlank()) words += LyricWord(pending.toString().trim(), pendingStart, pendingEnd)
                val joined = words.joinToString(" ") { it.text }
                if (words.isEmpty() || (start < 15_000 && CREDIT.containsMatchIn(joined))) continue
                lines += LyricLine(start, maxOf(start + duration, words.last().endMs), words)
            }
            lines.sortBy { it.startMs }
            return if (lines.isEmpty()) null else Lyrics(lines, true, source)
        }

        private fun estimated(start: Long, next: Long, text: String): LyricLine {
            val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val span = (next - start).coerceAtMost(MAX_LINE_MS)
            val active = minOf((span * 0.92).toLong(), parts.size * EST_MS_PER_WORD + EST_EXTRA_MS).coerceAtLeast(300)
            val weights = parts.map { p -> p.count { it.isLetterOrDigit() } + 2 }
            val total = weights.sum().toFloat()
            var t = start.toFloat()
            val words = parts.mapIndexed { i, p ->
                val d = active * weights[i] / total
                LyricWord(p, t.toLong(), (t + d).toLong()).also { t += d }
            }
            return LyricLine(start, start + active, words)
        }

        private fun toMs(min: String, sec: String): Long {
            val s = sec.replace(':', '.')
            val whole = s.substringBefore('.').toLong()
            val frac = s.substringAfter('.', "").padEnd(3, '0').take(3).toLong()
            return (min.toLong() * 60 + whole) * 1000 + frac
        }
    }
}

class LyricLine(val startMs: Long, val endMs: Long, val words: List<LyricWord>)

class LyricWord(val text: String, val startMs: Long, val endMs: Long)
