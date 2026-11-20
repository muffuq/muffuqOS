package com.muffuq.os.stage

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Wave ("Dalga"): liquid, letter-level typography. The line being sung is a ribbon of letters riding a living
 * curve across the screen.
 *
 * Over a line:
 * - Every line gets its own curve — a swell, an S, a rising arc, a tight ripple, a valley or a cascade, never the
 *   same as the line before — built from a tilt, a bow and three sines. The curve is alive: its phases drift,
 *   its amplitude breathes with the loudness and kicks on the beat.
 * - The line pours in word by word: each letter sets off from beyond the right edge, slides along the curve to
 *   its slot (hopping like a droplet, stretched by its speed, a faint copy trailing) and lands with a small bob
 *   just before its word is sung. Letters sit by arc length, their baselines turned to the curve's tangent, and
 *   all words share one letter height so the line reads as one ribbon.
 * - Karaoke runs letter by letter: as the voice crosses a word its letters light from a dim tint to near white,
 *   lift off the curve and grow a little; the newest glow in the album colour. A soft swell rides the ribbon
 *   under the voice. A held note rings like a drop in water — its letters rock and the ripple spreads to the
 *   neighbours — and throws sparks once. When the line is done, one crest rolls through it.
 * - When the next line takes over, the old ribbon keeps flowing off to the left and dissolves letter by letter
 *   (each one drifting up or down, turning, cooling to the album colour) while the new one pours in.
 * - Intro and long instrumental breaks: the empty curve the next line will ride is a string of glowing beads,
 *   light running through it, undulating to the beat; the letters pour onto it when the singing returns.
 * The camera rolls with the slope under the voice, yaws and pans gently after it.
 */
class WaveScene : Scene {

    private enum class Curve { Swell, SCurve, RisingArc, Ripple, Valley, Cascade }

    /**
     * A line's curve: base height, tilt, bow (arch or valley) and three sines, in shares of the screen height.
     * Rebuilt every frame from the shared wave time, then sampled so letters can be placed by arc length.
     */
    private class WavePath {
        private var base = 0.5f
        private var tilt = 0f
        private var bow = 0f
        private val amp = FloatArray(3)
        private val freq = FloatArray(3)
        private val phase0 = FloatArray(3)
        /** Steady drift (rad/s) and a slow back-and-forth slosh (rad), per sine. */
        private val speed = FloatArray(3)
        private val sway = FloatArray(3)
        private val phase = FloatArray(3)
        private val xs = FloatArray(SAMPLES)
        private val arcs = FloatArray(SAMPLES)
        private var w = 1f
        private var h = 1f
        private var gain = 1f
        private var step = 1f

        fun shape(line: Int, curve: Curve) {
            val flip = if (rand(line, 80) < 0.5f) 1f else -1f
            base = 0.5f + (rand(line, 81) - 0.5f) * 0.08f
            tilt = 0f
            bow = 0f
            for (i in 0..2) {
                amp[i] = 0f
                freq[i] = 1f
                speed[i] = 0f
                sway[i] = 0f
                phase0[i] = rand(line, 82 + i) * TAU
            }
            val r1 = rand(line, 85)
            val r2 = rand(line, 86)
            val r3 = rand(line, 87)
            when (curve) {
                // One long, low swell with small chop on it.
                Curve.Swell -> {
                    wave(0, 0.08f + 0.025f * r1, 0.55f + 0.25f * r2, 0.1f, 0.5f)
                    wave(1, 0.014f, 2.1f + 0.6f * r3, 0.7f * flip)
                    wave(2, 0.006f, 4.3f, -1.1f * flip)
                    tilt = 0.06f * flip
                }
                // A full period centred on the screen: down then up (or the mirror). It sloshes, never drifts away.
                Curve.SCurve -> {
                    wave(0, 0.09f + 0.025f * r1, 1f, 0f, 0.35f)
                    phase0[0] = if (flip > 0f) 0f else PI_F
                    wave(1, 0.012f, 2.7f + 0.5f * r2, 0.8f * flip)
                }
                // Climbing to the right over an arch.
                Curve.RisingArc -> {
                    tilt = -(0.3f + 0.1f * r1)
                    bow = 0.055f + 0.025f * r2
                    wave(0, 0.018f, 1.5f + 0.4f * r3, 0.6f)
                    wave(1, 0.007f, 3.4f, -0.9f)
                }
                // Tight ripples flowing left, on a slow underlying roll.
                Curve.Ripple -> {
                    wave(0, 0.018f + 0.006f * r1, 3.4f + 0.8f * r2, 1.3f + 0.5f * r3)
                    wave(1, 0.03f, 0.45f, 0.15f, 0.4f)
                    tilt = 0.05f * flip
                }
                // A smile: the ends raised, the middle low.
                Curve.Valley -> {
                    bow = -(0.075f + 0.025f * r1)
                    wave(0, 0.02f, 1.7f + 0.4f * r2, 0.7f)
                    wave(1, 0.008f, 3.6f, -1f)
                }
                // Falling to the right in steps.
                Curve.Cascade -> {
                    tilt = 0.28f + 0.1f * r1
                    wave(0, 0.04f + 0.012f * r2, 1.2f + 0.3f * r3, 0.45f, 0.3f)
                    wave(1, 0.01f, 3.1f, -0.9f)
                }
            }
        }

        private fun wave(i: Int, a: Float, f: Float, drift: Float, slosh: Float = 0f) {
            amp[i] = a
            freq[i] = f
            speed[i] = drift
            sway[i] = slosh
        }

        /** This frame's curve; [ampMul] scales the sines only (breath and beat), not the tilt or bow. */
        fun build(width: Float, height: Float, time: Float, ampMul: Float) {
            w = width
            h = height
            gain = ampMul
            for (i in 0..2) phase[i] = phase0[i] + speed[i] * time + sway[i] * sin(0.45f * time + phase0[i])
            var px = 0f
            var py = 0f
            var acc = 0f
            for (i in 0 until SAMPLES) {
                val x = (U0 + (U1 - U0) * i / (SAMPLES - 1)) * w
                val y = y(x)
                if (i > 0) {
                    val dx = x - px
                    val dy = y - py
                    acc += sqrt(dx * dx + dy * dy)
                }
                xs[i] = x
                arcs[i] = acc
                px = x
                py = y
            }
            step = xs[1] - xs[0]
        }

        fun y(x: Float): Float {
            val u = x / w
            val c = 2f * u - 1f
            var v = base + tilt * (u - 0.5f) + bow * (c * c - 0.3333f)
            for (i in 0..2) if (amp[i] != 0f) v += gain * amp[i] * sin(TAU * freq[i] * u + phase[i])
            return v * h
        }

        /** dy/dx, exact (not from the samples), so letters turn smoothly. */
        fun slope(x: Float): Float {
            val u = x / w
            var d = tilt + 4f * bow * (2f * u - 1f)
            for (i in 0..2) if (amp[i] != 0f) d += gain * amp[i] * TAU * freq[i] * cos(TAU * freq[i] * u + phase[i])
            return d * h / w
        }

        /** Arc length from the curve's left end to [x]; straight beyond the sampled ends. */
        fun arcAt(x: Float): Float {
            val last = SAMPLES - 1
            val f = (x - xs[0]) / step
            if (f <= 0f) return f * arcs[1]
            if (f >= last) return arcs[last] + (f - last) * (arcs[last] - arcs[last - 1])
            val i = f.toInt()
            return arcs[i] + (arcs[i + 1] - arcs[i]) * (f - i)
        }

        /** The x at arc length [s]: the inverse of [arcAt]. */
        fun xAt(s: Float): Float {
            val last = SAMPLES - 1
            if (s <= 0f) return xs[0] + s / arcs[1] * step
            if (s >= arcs[last]) return xs[last] + (s - arcs[last]) / (arcs[last] - arcs[last - 1]) * step
            var lo = 0
            var hi = last
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (arcs[mid] <= s) lo = mid else hi = mid
            }
            return xs[lo] + step * (s - arcs[lo]) / (arcs[hi] - arcs[lo])
        }
    }

    /**
     * One line laid out as a ribbon: each letter's slot along it (arc px from the ribbon's start) and when it
     * pours in. Holds its word sprites, so a leaving line still draws after the cache has dropped it.
     */
    private class Ribbon(val line: Int, words: Int, curve: Curve) {
        val path = WavePath().apply { shape(line, curve) }
        val sprites = arrayOfNulls<WordSprite>(words)
        val wordStart = FloatArray(words)
        val wordWidth = FloatArray(words)
        var ready = false
        /** Letter height on screen, the stretch of curve the line was fitted into, and the ribbon's own length. */
        var h = 0f
        var span = 0f
        var length = 0f
        var count = 0
        var word = IntArray(0)
        var glyph = IntArray(0)
        /** A letter's place among its word's visible letters, and how many there are. */
        var rank = IntArray(0)
        var ranks = IntArray(0)
        var slot = FloatArray(0)
        /** Song ms: leaves the right edge, lands in its slot, fully lit. */
        var depart = LongArray(0)
        var arrive = LongArray(0)
        var fullAt = LongArray(0)
        /** Each letter's own exit: how far (signed, in letter heights) it drifts and how far it turns. */
        var drift = FloatArray(0)
        var spin = FloatArray(0)
        /** The end-of-line crest rolls left to right, or back. */
        val crestFromLeft = rand(line, 91) < 0.5f
        val scroll = Spring(4.5f)
        val front = Spring(14f)
        val activity = Spring(7f)
        var leaveAt = 0L
        var leavePos = 0L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val burstColors = IntArray(3)

    private var lyrics: Lyrics? = null
    private var ribbon: Ribbon? = null
    /** Lines flowing away; two slots so quick lines don't cut each other's exit short. */
    private val leaving = arrayOfNulls<Ribbon>(2)
    private val idle = WavePath()
    private var idleLine = Int.MIN_VALUE
    private var idleAlpha = -1f
    private var lastPos = 0L
    private var lastClock = 0L
    /** Seconds of wave motion: runs with the clock (so it lives while paused), slower when paused. */
    private var waveT = 0f
    private val drift = Spring(2f)
    private val breath = Spring(3f)
    /** Unsung letters: the second colour lifted toward white so it reads on any backdrop. */
    private var dim = 0

    private val camRotX = Spring(2.4f)
    private val camRotY = Spring(2.2f)
    private val camRotZ = Spring(2.6f)
    private val camZoom = Spring(5f)
    private val camShiftX = Spring(2.4f)
    private val camShiftY = Spring(2.4f)
    /** Where the voice is on screen this frame, for the camera. */
    private var hasFront = false
    private var frontX = 0f
    private var frontY = 0f
    private var frontDeg = 0f
    private var slopeDeg = 0f

    // Letters of the ribbon being drawn: placed in a first pass, drawn in a second (so glows sit beneath).
    private val lx = FloatArray(MAX_LETTERS)
    private val ly = FloatArray(MAX_LETTERS)
    private val ldeg = FloatArray(MAX_LETTERS)
    private val lsx = FloatArray(MAX_LETTERS)
    private val lsy = FloatArray(MAX_LETTERS)
    private val lcolor = IntArray(MAX_LETTERS)
    private val lalpha = IntArray(MAX_LETTERS)
    private val lword = IntArray(MAX_LETTERS)
    private val lglyph = IntArray(MAX_LETTERS)

    override fun draw(c: Canvas, s: SceneContext) {
        val w = s.width
        val h = s.height
        if (w <= 0f || h <= 0f) return
        if (s.lyrics !== lyrics) {
            lyrics = s.lyrics
            ribbon = null
            leaving.fill(null)
            idleLine = Int.MIN_VALUE
        }
        val now = s.clockMs
        val pos = s.positionMs
        val dt = if (lastClock == 0L) 0f else ((now - lastClock) / 1000f).coerceIn(0f, 0.1f)
        lastClock = now
        waveT += dt * drift.update(if (s.playing) 1f else 0.3f, now)
        val breathe = breath.update(s.level, now)
        dim = mix(s.ink.secondary, s.ink.light, 0.38f)

        // Which line rides the wave: the next takes over as its first word starts pouring in; none through a
        // long instrumental break.
        val lines = s.lyrics.lines
        val active = if (lines.isEmpty()) -1 else activeLine(s.lyrics, pos)
        val show = if (active >= 0 && inBreak(lines, active, pos)) -1 else active
        val current = ribbon
        if (show != (current?.line ?: -1)) {
            if (current != null && current.ready) letGo(current, now)
            ribbon = if (show >= 0) Ribbon(show, lines[show].words.size, curveOf(show)) else null
        }
        lastPos = pos
        val r = ribbon

        // The empty wave is the curve the next line will ride, so the letters pour onto what was waiting.
        val wantIdle = when {
            r != null -> r.line
            active < 0 -> 0
            else -> min(active + 1, lines.lastIndex).coerceAtLeast(0)
        }
        if (wantIdle != idleLine) {
            idleLine = wantIdle
            idle.shape(wantIdle, curveOf(wantIdle))
        }
        val idleTarget = if (r == null) 1f else 0f
        idleAlpha = when {
            idleAlpha < 0f -> idleTarget
            idleAlpha < idleTarget -> min(idleTarget, idleAlpha + dt / 0.7f)
            else -> max(idleTarget, idleAlpha - dt / 0.5f)
        }
        if (idleAlpha > 0.01f) {
            idle.build(w, h, waveT, 1f + 0.35f * breathe + 0.75f * s.beat)
            drawIdle(c, s, idleAlpha)
        }

        val gain = 1f + 0.22f * breathe + 0.32f * s.beat
        // Older exits beneath newer ones.
        val l0 = leaving[0]
        val l1 = leaving[1]
        if (l0 != null && l1 != null && l1.leaveAt < l0.leaveAt) {
            drawLeaving(c, s, 1, now, gain)
            drawLeaving(c, s, 0, now, gain)
        } else {
            drawLeaving(c, s, 0, now, gain)
            drawLeaving(c, s, 1, now, gain)
        }

        hasFront = false
        if (r != null) {
            val line = lines[r.line]
            r.path.build(w, h, waveT, gain)
            if (!r.ready) prepare(r, s, line)
            if (r.ready) drawRibbon(c, s, r, line, now, -1f)
        }
        aimCamera(s, now)
    }

    /** The line whose letters are on the wave: a line takes over [POUR_LEAD_MS] early, but not while the one before is still sung. */
    private fun activeLine(lyrics: Lyrics, pos: Long): Int {
        val i = lyrics.lineAt(pos + POUR_LEAD_MS)
        return if (i > 0 && pos < takeover(lyrics, i)) i - 1 else i
    }

    private fun takeover(lyrics: Lyrics, i: Int): Long {
        val line = lyrics.lines[i]
        if (i == 0) return line.startMs - POUR_LEAD_MS
        return (lyrics.lines[i - 1].endMs - 120).coerceIn(line.startMs - POUR_LEAD_MS, line.startMs - 150)
    }

    /** A long instrumental gap follows line [i] and it has lingered long enough: let it go. */
    private fun inBreak(lines: List<LyricLine>, i: Int, pos: Long): Boolean {
        val line = lines[i]
        val next = lines.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
        return next - line.endMs > BREAK_MS && pos > line.endMs + LINGER_MS
    }

    /** Each line steps to a different curve than the one before; the same song always flows the same way. */
    private fun curveOf(line: Int): Curve {
        val all = Curve.entries
        var k = (rand(0, 71) * all.size).toInt()
        for (i in 1..line) k = (k + 1 + (rand(i, 72) * (all.size - 1)).toInt()) % all.size
        return all[k]
    }

    private fun letGo(r: Ribbon, now: Long) {
        r.leaveAt = now
        // Frozen where it was last seen (before a seek, too), so only the exit moves.
        r.leavePos = lastPos
        val a = leaving[0]
        val b = leaving[1]
        val slot = when {
            a == null -> 0
            b == null -> 1
            a.leaveAt <= b.leaveAt -> 0
            else -> 1
        }
        leaving[slot] = r
    }

    private fun drawLeaving(c: Canvas, s: SceneContext, slot: Int, now: Long, gain: Float) {
        val r = leaving[slot] ?: return
        val t = (now - r.leaveAt) / LEAVE_MS
        val line = s.line(r.line)
        if (t >= 1f || line == null) {
            leaving[slot] = null
            return
        }
        r.path.build(s.width, s.height, waveT, gain)
        drawRibbon(c, s, r, line, now, t)
    }

    /**
     * Lays the line out once its sprites are all there: one letter height for every word, sized so the whole line
     * fits the visible stretch of its curve (long lines get a floor and scroll instead), then each letter's slot
     * and pour timing.
     */
    private fun prepare(r: Ribbon, s: SceneContext, line: LyricLine) {
        val words = line.words
        if (words.isEmpty()) return
        for (i in words.indices) r.sprites[i] = s.sprite(r.line, i) ?: return
        val w = s.width
        var perH = WORD_GAP * (words.size - 1)
        var smallest = Float.MAX_VALUE
        var letters = 0
        for (sp in r.sprites) {
            sp ?: return
            perH += sp.width.toFloat() / sp.height
            smallest = min(smallest, sp.height.toFloat())
            for (g in 0 until sp.glyphCount) if (!glyphIsBlank(sp, g)) letters++
        }
        val span = r.path.arcAt(w * (1f - MARGIN)) - r.path.arcAt(w * MARGIN)
        // Sprites are drawn at their biggest; never blow one up by more than ~1.15×.
        val lh = (span * 0.97f / perH).coerceIn(s.height * MIN_H, s.height * MAX_H).coerceAtMost(smallest * 1.15f)
        val n = min(letters, MAX_LETTERS)
        r.word = IntArray(n)
        r.glyph = IntArray(n)
        r.rank = IntArray(n)
        r.ranks = IntArray(n)
        r.slot = FloatArray(n)
        r.depart = LongArray(n)
        r.arrive = LongArray(n)
        r.fullAt = LongArray(n)
        r.drift = FloatArray(n) { (if (rand(r.line, it, 61) < 0.5f) -1f else 1f) * (0.9f + 1.6f * rand(r.line, it, 62)) }
        r.spin = FloatArray(n) { (rand(r.line, it, 63) - 0.5f) * 160f }
        var at = 0f
        var j = 0
        for (i in words.indices) {
            val sp = r.sprites[i] ?: return
            val k = lh / sp.height
            r.wordStart[i] = at
            r.wordWidth[i] = sp.width * k
            var visible = 0
            for (g in 0 until sp.glyphCount) if (!glyphIsBlank(sp, g)) visible++
            var rank = 0
            for (g in 0 until sp.glyphCount) {
                if (j >= n || glyphIsBlank(sp, g)) continue
                r.word[j] = i
                r.glyph[j] = g
                r.rank[j] = rank++
                r.ranks[j] = visible
                r.slot[j] = at + glyphCenter(sp, g) * k
                j++
            }
            at += sp.width * k + WORD_GAP * lh
        }
        r.count = j
        r.h = lh
        r.span = span
        r.length = at - WORD_GAP * lh

        // Pour timing: a word's letters land just before it's sung, a few ms apart, having set off from beyond
        // the right edge early enough to cross the distance — but not before the line took over.
        val from = takeover(s.lyrics, r.line)
        val entryGap = w * (ENTRY_U - 1f + MARGIN)
        for (q in 0 until r.count) {
            val word = words[r.word[q]]
            val total = r.ranks[q]
            val rank = r.rank[q]
            var arrive = word.startMs - LAND_LEAD_MS + (rank * min(34f, 240f / total)).toLong()
            val fromLeft = r.slot[q] - scrollTarget(r, r.slot[q])
            val dist = (span + entryGap - fromLeft).coerceAtLeast(0f)
            val travel = (280f + 430f * (dist / w).coerceAtMost(1.3f)).toLong()
            val depart = max(arrive - travel, from)
            if (arrive - depart < 200) arrive = depart + 200
            r.depart[q] = depart
            r.arrive[q] = arrive
            val dur = (word.endMs - word.startMs).coerceAtLeast(1)
            r.fullAt[q] = word.startMs + ((rank + SOFT) / (total + SOFT) * dur).toLong()
        }
        val front = frontOf(r, line, s.positionMs)
        r.front.reset(front)
        r.scroll.reset(scrollTarget(r, front))
        r.activity.reset(0f)
        r.ready = true
    }

    /** A line that fits is centred; a longer one scrolls so the voice stays a little right of the middle. */
    private fun scrollTarget(r: Ribbon, front: Float): Float =
        if (r.length <= r.span) (r.length - r.span) / 2f else (front - 0.62f * r.span).coerceIn(0f, r.length - r.span)

    /** The singing position along the ribbon (arc px from its start). */
    private fun frontOf(r: Ribbon, line: LyricLine, pos: Long): Float {
        val i = line.words.indexOfLast { it.startMs <= pos }
        if (i < 0) return 0f
        return r.wordStart[i] + r.wordWidth[i] * wordProgress(line.words[i], pos)
    }

    private fun singing(line: LyricLine, pos: Long): Boolean {
        val i = line.words.indexOfLast { it.startMs <= pos }
        return i >= 0 && pos < line.words[i].endMs
    }

    /** The held note still ringing (the newest word or the one before it), or -1. */
    private fun heldWord(line: LyricLine, pos: Long): Int {
        val words = line.words
        val newest = words.indexOfLast { it.startMs <= pos }
        for (i in newest downTo max(0, newest - 1)) {
            val wd = words[i]
            if (wd.endMs - wd.startMs >= HELD_MS && pos < wd.endMs + 700) return i
        }
        return -1
    }

    /**
     * One ribbon in two passes: first every letter's place, size and colour (drawing the speed trails and glows
     * right away, so they end up beneath all letters), then the letters. [leaveT] is -1 for the line being sung,
     * else 0..1 through its exit.
     */
    private fun drawRibbon(c: Canvas, s: SceneContext, r: Ribbon, line: LyricLine, now: Long, leaveT: Float) {
        val w = s.width
        val path = r.path
        val lh = r.h
        val words = line.words
        val ink = s.ink
        val gone = leaveT >= 0f
        val pos = if (gone) r.leavePos else s.positionMs
        val frontRaw = frontOf(r, line, pos)
        val front = if (gone) r.front.value else r.front.update(frontRaw, now)
        val scroll = if (gone) r.scroll.value else r.scroll.update(scrollTarget(r, frontRaw), now)
        val active = r.activity.update(if (!gone && singing(line, pos)) 1f else 0f, now)
        val beat = if (gone) 0f else s.beat

        // Anchored at the screen's middle, so the curve breathing stretches the ends, not the whole line.
        val origin = path.arcAt(w * 0.5f) - r.span * 0.5f - scroll
        val entry = path.arcAt(w * ENTRY_U)
        // Leaving: the ribbon keeps flowing left, faster and faster.
        val shift = if (gone) w * 0.6f * leaveT * leaveT else 0f

        // The swell under the voice.
        val frontArc = origin + front
        val swell = lh * 0.22f * active * (1f + 0.5f * beat)
        val swellSigma2 = (lh * 2.2f) * (lh * 2.2f)

        // A held note rings like a drop in water: rings spread from the word's centre and fade with distance.
        val held = heldWord(line, pos)
        var ringAmp = 0f
        var ringArc = 0f
        var ringT = 0f
        if (held >= 0) {
            val hw = words[held]
            ringT = (pos - hw.startMs) / 1000f
            val fall = 1f - ((pos - hw.endMs) / 700f).coerceIn(0f, 1f)
            ringAmp = lh * 0.15f * (ringT / 0.12f).coerceIn(0f, 1f) * fall * (if (gone) 1f - leaveT else 1f)
            ringArc = origin + r.wordStart[held] + r.wordWidth[held] * 0.5f
        }
        val ringK = TAU / (lh * 1.7f)

        // The line done: one crest rolls through it, from the left or the right.
        val crestT = (pos - line.endMs) / 800f
        val crestAmp = if (crestT in 0f..1f) lh * 0.2f * (1f - crestT) else 0f
        val crestAt = if (r.crestFromLeft) crestT else 1f - crestT
        val crestArc = origin - 2f * lh + (r.length + 4f * lh) * crestAt
        val crestSigma2 = (lh * 1.6f) * (lh * 1.6f)

        val rise = (BASELINE - CENTER_Y) * lh
        var n = 0
        var lastWord = -1
        var wp = 0f
        for (j in 0 until r.count) {
            val dep = r.depart[j]
            if (pos < dep) continue
            val wi = r.word[j]
            val sprite = r.sprites[wi] ?: continue
            if (wi != lastWord) {
                lastWord = wi
                wp = wordProgress(words[wi], pos)
            }
            val g = r.glyph[j]
            val k = ((pos - dep).toFloat() / (r.arrive[j] - dep)).coerceIn(0f, 1f)
            val e = easeOutCubic(k)
            val slotArc = origin + r.slot[j]
            val arc = entry + (slotArc - entry) * e - shift
            val x = path.xAt(arc)
            if (x < -lh * 1.5f || x > w + lh * 1.5f) continue
            val ang = atan(path.slope(x))

            // Karaoke: the voice sweeps the word letter by letter; the newest letters run hot, then cool.
            val gp = ((wp * (r.ranks[j] + SOFT) - r.rank[j]) / SOFT).coerceIn(0f, 1f)
            val heat = if (gp <= 0f) 0f else gp * exp(-max(0L, pos - r.fullAt[j]) / 280f)

            // Everything below is a lift off the curve (along its normal) and a tilt from how that lift slopes.
            var lift = rise + lh * (0.06f * gp + 0.13f * heat)
            var tilt = 0f
            if (k < 1f) {
                lift += sin(PI_F * k) * lh * 0.16f // a droplet's hop
            } else {
                val since = (pos - r.arrive[j]).toFloat()
                if (since < 700f) lift -= lh * 0.1f * sin(since * 0.025f) * exp(-since / 170f) // the bob as it lands
            }
            val along = arc + shift
            if (swell > 0.5f) {
                val d = along - frontArc
                val bump = swell * exp(-d * d / swellSigma2)
                lift += bump
                tilt += atan(2f * bump * d / swellSigma2)
            }
            if (ringAmp > 0f) {
                val d = along - ringArc
                val ad = abs(d)
                val reached = ((ringT * lh * 6f - ad) / lh).coerceIn(0f, 1f)
                if (reached > 0f) {
                    val a = ringAmp * reached * exp(-ad / (lh * 4f))
                    val ph = ad * ringK - ringT * 13f
                    lift += a * sin(ph)
                    tilt -= 0.5f * atan((if (d < 0f) -1f else 1f) * a * ringK * cos(ph))
                }
            }
            if (crestAmp > 0f) {
                val d = along - crestArc
                val bump = crestAmp * exp(-d * d / crestSigma2)
                lift += bump
                tilt += atan(2f * bump * d / crestSigma2)
            }

            var px = x + sin(ang) * lift
            var py = path.y(x) - cos(ang) * lift
            var deg = (ang + tilt) * RAD_TO_DEG
            val ws = lh / sprite.height
            val grow = 1f + 0.05f * gp + 0.12f * heat + 0.05f * beat * gp
            var sx = ws * grow * (1f + 0.6f * (1f - e)) // stretched by its speed
            var sy = ws * grow * (1f - 0.18f * (1f - e))
            var color = mix(mix(dim, ink.light, gp), ink.primary, heat * 0.85f)
            var alpha = (0.45f + 0.55f * gp) * (if (k < 1f) 0.65f + 0.35f * k else 1f)

            if (gone) {
                // Dissolving from the left: each letter lets go, drifts up or down, turns, shrinks and cools.
                val lt = ((leaveT - 0.45f * j / max(1, r.count - 1)) / 0.55f).coerceIn(0f, 1f)
                if (lt > 0f) {
                    py += r.drift[j] * lt * lt * lh
                    px -= lt * lh * 0.5f
                    deg += r.spin[j] * lt
                    val shrink = 1f - 0.4f * lt
                    sx *= shrink
                    sy *= shrink
                    alpha *= 1f - lt
                    color = mix(color, ink.primary, lt * 0.7f)
                }
            }
            val a255 = (255f * alpha).toInt().coerceAtMost(255)
            if (a255 < 3) continue

            if (!gone && k < 0.9f) {
                // A faint copy trailing behind, for speed.
                val k2 = (k - 0.09f).coerceAtLeast(0f)
                val x2 = path.xAt(entry + (slotArc - entry) * easeOutCubic(k2))
                val ta = (255f * 0.22f * (1f - k) * alpha).toInt()
                if (x2 < w + lh && ta >= 3) {
                    val a2 = atan(path.slope(x2))
                    val l2 = rise + sin(PI_F * k2) * lh * 0.16f
                    paint.color = dim
                    paint.alpha = ta
                    letter(c, sprite, g, x2 + sin(a2) * l2, path.y(x2) - cos(a2) * l2, a2 * RAD_TO_DEG, sx * 1.3f, sy * 0.9f)
                }
            }
            if (!gone && heat > 0.06f) {
                // The newest letters glow: the album colour spread around them.
                paint.color = ink.primary
                paint.alpha = (255f * 0.3f * heat).toInt()
                letter(c, sprite, g, px, py, deg, sx * 1.12f, sy * 1.12f)
                paint.alpha = (255f * 0.13f * heat).toInt()
                letter(c, sprite, g, px, py, deg, sx * 1.3f, sy * 1.3f)
            }
            if (n < MAX_LETTERS) {
                lx[n] = px
                ly[n] = py
                ldeg[n] = deg
                lsx[n] = sx
                lsy[n] = sy
                lcolor[n] = color
                lalpha[n] = a255
                lword[n] = wi
                lglyph[n] = g
                n++
            }
        }

        for (q in 0 until n) {
            val sprite = r.sprites[lword[q]] ?: continue
            paint.color = lcolor[q]
            paint.alpha = lalpha[q]
            letter(c, sprite, lglyph[q], lx[q], ly[q], ldeg[q], lsx[q], lsy[q])
        }

        if (gone) return
        val fx = path.xAt(frontArc)
        hasFront = true
        frontX = fx
        frontY = path.y(fx) - lh * 0.4f
        frontDeg = atan(path.slope(fx)) * RAD_TO_DEG
        slopeDeg = atan((path.y(w * 0.85f) - path.y(w * 0.15f)) / (w * 0.7f)) * RAD_TO_DEG

        if (held >= 0) {
            val hw = words[held]
            if (pos >= hw.startMs && pos < hw.endMs && s.effects.landOnce(burstKey(r.line, held))) {
                val bx = path.xAt(ringArc)
                burstColors[0] = ink.primary
                burstColors[1] = ink.light
                burstColors[2] = ink.secondary
                s.effects.burst(bx, path.y(bx) - lh * 0.45f, burstColors, s.density, r.line * 131 + held * 7)
            }
        }
    }

    /** Letter [g] of [sprite] with its visual centre at ([x], [y]), turned and scaled around it. */
    private fun letter(c: Canvas, sprite: WordSprite, g: Int, x: Float, y: Float, deg: Float, sx: Float, sy: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(deg)
        c.scale(sx, sy)
        drawGlyph(c, sprite, g, -(sprite.glyphRight[g] - sprite.glyphLeft[g]) * 0.5f, -CENTER_Y * sprite.height, paint)
        c.restore()
    }

    /** The empty wave: a string of beads along the curve, light running through it, kicking with the beat. */
    private fun drawIdle(c: Canvas, s: SceneContext, alpha: Float) {
        val w = s.width
        val h = s.height
        val beat = s.beat
        for (i in 0 until IDLE_DOTS) {
            val u = 0.03f + 0.94f * i / (IDLE_DOTS - 1)
            val x = u * w
            val y = idle.y(x)
            var p = 0.5f + 0.5f * sin(u * TAU * 1.3f - waveT * 2.1f)
            p *= p
            p *= p
            val a = alpha * sin(u * PI_F) * (0.3f + 0.7f * p + 0.25f * beat).coerceAtMost(1f)
            val radius = h * (0.0035f + 0.006f * p + 0.004f * beat)
            val color = mix(dim, s.ink.primary, p)
            if (p > 0.2f) {
                dotPaint.color = color
                dotPaint.alpha = (255f * a * 0.12f).toInt()
                c.drawCircle(x, y, radius * 2.4f, dotPaint)
                dotPaint.alpha = (255f * a * 0.06f).toInt()
                c.drawCircle(x, y, radius * 4.2f, dotPaint)
            }
            dotPaint.color = mix(color, s.ink.light, p * 0.5f)
            dotPaint.alpha = (255f * a).toInt()
            c.drawCircle(x, y, radius, dotPaint)
        }
    }

    /**
     * The camera follows the voice: rolls a third of the way against the slope under it (so the sung part reads
     * level), yaws with the line's overall slope, pans and pitches a little toward it. Idle: a slow drift.
     */
    private fun aimCamera(s: SceneContext, now: Long) {
        val w = s.width
        val h = s.height
        val t = waveT
        val cam = s.camera
        if (hasFront) {
            cam.rotZ = camRotZ.update((-0.3f * frontDeg).coerceIn(-6f, 6f) + 1.2f * sin(t / 9f), now)
            cam.rotY = camRotY.update((-0.3f * slopeDeg + (0.5f - frontX / w) * 8f).coerceIn(-9f, 9f), now)
            cam.rotX = camRotX.update(4f + 2f * sin(t / 7.3f) + (frontY / h - 0.5f) * 10f, now)
            cam.zoom = camZoom.update(1f + 0.02f * s.level + 0.03f * s.beat, now)
            cam.shiftX = camShiftX.update((w * 0.5f - frontX) * 0.05f, now)
            cam.shiftY = camShiftY.update((h * 0.5f - frontY) * 0.18f, now)
        } else {
            cam.rotZ = camRotZ.update(2f * sin(t / 6.5f), now)
            cam.rotY = camRotY.update(6f * sin(t / 9.5f), now)
            cam.rotX = camRotX.update(6f + 3f * sin(t / 7.3f), now)
            cam.zoom = camZoom.update(0.97f + 0.03f * s.beat, now)
            cam.shiftX = camShiftX.update(0f, now)
            cam.shiftY = camShiftY.update(0f, now)
        }
    }

    private companion object {
        const val TAU = 6.2831855f
        const val PI_F = 3.1415927f
        const val RAD_TO_DEG = 57.29578f

        /** Curve samples, and how far past the screen's edges the curve runs (letters pour in from beyond the right). */
        const val SAMPLES = 64
        const val U0 = -0.16f
        const val U1 = 1.16f
        /** Where letters set off from, as a share of the screen width. */
        const val ENTRY_U = 1.1f
        /** The ribbon is fitted between these side margins (share of the width). */
        const val MARGIN = 0.07f

        /** A line takes over this early, so its first word has time to pour across the screen. */
        const val POUR_LEAD_MS = 750L
        /** Letters land this much before their word is sung. */
        const val LAND_LEAD_MS = 140L
        const val LEAVE_MS = 1_000f
        /** After a line, wait this long before clearing it when a long instrumental gap follows. */
        const val LINGER_MS = 1_800L
        const val BREAK_MS = 3_500L
        /** A word sung at least this long is a held note: it ripples and bursts. */
        const val HELD_MS = 650L

        /** Letter height bounds, as a share of the screen height. */
        const val MIN_H = 0.085f
        const val MAX_H = 0.17f
        /** Space between words, in letter heights. */
        const val WORD_GAP = 0.22f
        /** Where the baseline and a letter's visual centre sit in its sprite, as a share of the sprite's height. */
        const val BASELINE = 0.76f
        const val CENTER_Y = 0.44f
        /** How many letters the karaoke edge is soft over. */
        const val SOFT = 1.3f
        const val MAX_LETTERS = 160
        const val IDLE_DOTS = 38

        /** Each held note bursts once; a bit of its own so keys don't collide with other scenes'. */
        fun burstKey(line: Int, word: Int): Long = (line.toLong() shl 32) or (1L shl 30) or word.toLong()

        fun mix(a: Int, b: Int, t: Float): Int {
            if (t <= 0f) return a
            if (t >= 1f) return b
            val ar = (a shr 16) and 0xff
            val ag = (a shr 8) and 0xff
            val ab = a and 0xff
            val r = ar + (((b shr 16) and 0xff) - ar) * t
            val g = ag + (((b shr 8) and 0xff) - ag) * t
            val bl = ab + ((b and 0xff) - ab) * t
            return (0xff shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or bl.toInt()
        }
    }
}
