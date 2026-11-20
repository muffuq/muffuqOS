package com.muffuq.os.stage

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Poster: every lyric line becomes a bold Swiss / brutalist typographic poster that builds itself as it's sung.
 *
 * Over one line:
 * - The line is laid out as a whole composition from its sprite sizes, with one of six templates: a justified
 *   block (every row scaled to the same width, so short rows get big), one huge keyword with small words around
 *   it, a vertical spine word with rows beside it, a diagonal staircase, an alternating big/small stack, or a solo
 *   word. Sprites are only ever scaled down (≤ 1.1×), then the whole poster is fitted to the screen.
 * - Words appear only as they're sung, letters cascading in (drop with a bounce, rise out of a slot, flip open,
 *   slide in, pop), each word a different move from its neighbour. Held notes and a lingering last word stamp in
 *   instead, throwing sparks and punching the camera in.
 * - The sung word is marked by the line's highlight: an album-colour bar sweeping in behind it, a karaoke fill or
 *   a growing underline. Done words settle into light (the line's hero word into the album colour).
 * - Rules, crop marks, squares and leaders draw themselves in with the composition; a progress rule tracks the line.
 * - On the next line the finished poster leaves (zooms through the camera, whips off with a smear, collapses row by
 *   row into rules, flips away, drops, splits) while the next one arrives continuing that motion.
 * - Before the first line and in long breaks: crop marks of an empty poster and a countdown rule, breathing.
 *
 * Template, highlight, exit and camera pose are walked deterministically per line, neighbours always differing, so
 * the same song always plays the same way.
 */
class PosterScene : Scene {

    /** One line's poster: the whole composition laid out once (in pixels around its centre), revealed word by word. */
    private class Poster {
        var line = -1
        var ready = false
        var template = 0
        var variant = 0
        var mark = 0
        var exit = 0
        var exitDir = 1f
        /** How this poster arrives: the previous poster's exit (continuing its motion), or -1. */
        var arrival = -1
        var arrivalDir = 1f
        var tilt = 0f
        /** The word that stays in the album colour once sung (keyword, spine, longest held). */
        var hero = 0
        var n = 0
        var rows = 1

        /** Per word: the sprite's local origin, scale and rotation (0, -90 or 90). */
        var x = FloatArray(0)
        var y = FloatArray(0)
        var s = FloatArray(0)
        var rot = FloatArray(0)
        /** Per word: the visible (cap-band) box, for accents, exits, bursts and the camera. */
        var vl = FloatArray(0)
        var vt = FloatArray(0)
        var vr = FloatArray(0)
        var vb = FloatArray(0)
        var row = IntArray(0)
        var entry = IntArray(0)
        var tone = IntArray(0)
        var burst = BooleanArray(0)

        var left = 0f
        var top = 0f
        var right = 0f
        var bottom = 0f

        val accents = Array(MAX_ACCENTS) { Accent() }
        var accentCount = 0
        /** Clock time its exit started, once it's the leaving poster. */
        var exitAt = 0L

        fun reset(line: Int) {
            this.line = line
            ready = false
            n = 0
            accentCount = 0
        }

        fun ensure(count: Int) {
            if (x.size >= count) return
            val cap = count + 8
            x = FloatArray(cap); y = FloatArray(cap); s = FloatArray(cap); rot = FloatArray(cap)
            vl = FloatArray(cap); vt = FloatArray(cap); vr = FloatArray(cap); vb = FloatArray(cap)
            row = IntArray(cap); entry = IntArray(cap); tone = IntArray(cap); burst = BooleanArray(cap)
        }
    }

    /** A graphic element of the poster: rule, square, progress rule or crop mark, drawing in at its time. */
    private class Accent {
        var kind = 0
        var x = 0f
        var y = 0f
        var w = 0f
        var h = 0f
        var at = 0L
        var ms = 400f
        /** Rules: grow from the start (0), the end (1) or the middle (2). */
        var from = 0
        var primary = false
        var alpha = 1f
    }

    // ---- State carried from frame to frame ----

    private var current = Poster()
    private var leaving = Poster()
    private var leavingActive = false
    private var lineIndex = Int.MIN_VALUE
    private var punchAt = Long.MIN_VALUE / 2

    private val camRotX = Spring(3.2f)
    private val camRotY = Spring(3.2f)
    private val camRotZ = Spring(3.2f)
    private val camZoom = Spring(7.5f)
    private val camShiftX = Spring(3.6f)
    private val camShiftY = Spring(3.6f)
    private val idle = Spring(6f)

    // ---- Reused drawing objects: nothing is allocated per frame ----

    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private val matrix = Matrix()
    private val point = FloatArray(2)
    private val burstColors = IntArray(3)

    private var inkFor: StageInk? = null
    private var cPrimary = 0
    private var cLight = 0
    private var cSettled = 0
    private var cSoft = 0
    private var cDim = 0
    private var cOnBar = 0

    /** The word being drawn, as three colour slices along x: [0, cut1) col0, [cut1, cut2) col1, [cut2, w) col2. */
    private var cut1 = 0f
    private var cut2 = 0f
    private var col0 = 0
    private var col1 = 0
    private var col2 = 0

    /** The current poster's arrival offset this frame. */
    private var arrScale = 1f
    private var arrDx = 0f
    private var arrDy = 0f

    /** Scratch box of one row (see [rowBox]). */
    private var rbL = 0f
    private var rbT = 0f
    private var rbR = 0f
    private var rbB = 0f

    // ---- Layout scratch, grown at line changes only ----

    private var aspect = FloatArray(16)
    private var spriteH = FloatArray(16)
    private var order = IntArray(16)
    private var rowEnd = IntArray(17)
    private var rowTmp = IntArray(17)
    private var cost = FloatArray(17)
    private var back = IntArray(17)
    private var rowH = FloatArray(17)
    private var rowW = FloatArray(17)

    override fun draw(c: Canvas, s: SceneContext) {
        if (s.width <= 0f || s.height <= 0f) return
        colors(s.ink)
        val pos = s.positionMs
        val now = s.clockMs
        val li = s.lineAt(LEAD_MS)
        if (li != lineIndex) switchLine(li, s)
        if (li >= 0 && !current.ready) layout(current, s)

        var leaveT = -1f
        if (leavingActive) {
            leaveT = (now - leaving.exitAt) / EXIT_MS[leaving.exit]
            if (leaveT >= 1f || leaveT < 0f) {
                leavingActive = false
                leaveT = -1f
            }
        }
        val ready = li >= 0 && current.ready
        val gapT = if (ready) gapExit(current, s) else 1f
        val showCurrent = gapT < 1f

        updateCamera(s, li, showCurrent, gapT, leaveT)

        // Intro and long instrumental breaks: the empty poster, counting down to the next line.
        val wantIdle = if (li < 0) {
            val first = s.line(0)
            first != null && first.startMs - pos > 700
        } else {
            val next = s.line(li + 1)
            ready && gapT >= 0.5f && (next == null || next.startMs - pos > 700)
        }
        val idleA = idle.update(if (wantIdle) 1f else 0f, now).coerceIn(0f, 1f)
        if (idleA > 0.01f) drawIdle(c, s, idleA, idleProgress(s, li))

        if (leaveT >= 0f) drawPoster(c, s, leaving, leaveT, arriving = false)
        if (showCurrent) drawPoster(c, s, current, gapT, arriving = true)
    }

    // ---- Line changes ----

    /** The finished poster starts leaving (unless a break already took it away); the new line's poster starts empty. */
    private fun switchLine(li: Int, s: SceneContext) {
        val old = current
        if (old.ready && old.line >= 0) {
            val g = gapExit(old, s)
            if (g < 1f) {
                // Continues a break exit already under way rather than restarting it.
                old.exitAt = s.clockMs - (g * EXIT_MS[old.exit]).toLong()
                current = leaving
                leaving = old
                leavingActive = true
            }
        }
        current.reset(li)
        lineIndex = li
    }

    /** 0..1 through the poster's exit into a long break after its line (0 while the line is on, or no break follows). */
    private fun gapExit(p: Poster, s: SceneContext): Float {
        val line = s.line(p.line) ?: return 1f
        val next = s.line(p.line + 1)
        if (next != null && next.startMs - line.endMs <= GAP_MS) return 0f
        return ((s.positionMs - line.endMs - LINGER_MS) / EXIT_MS[p.exit]).coerceIn(0f, 1f)
    }

    // ---- Layout: once per line, as soon as all its sprites exist ----

    private fun layout(p: Poster, s: SceneContext) {
        val li = p.line
        val line = s.line(li) ?: return
        val n = line.words.size
        if (n == 0) return
        ensureScratch(n)
        for (i in 0 until n) {
            val sprite = s.sprite(li, i) ?: return // not rendered yet: try again next frame
            aspect[i] = sprite.width / sprite.height.toFloat()
            spriteH[i] = sprite.height.toFloat()
        }
        p.ensure(n)
        p.n = n
        p.accentCount = 0
        for (i in 0 until n) {
            p.rot[i] = 0f
            p.row[i] = 0
            p.tone[i] = TONE_LIGHT
            p.burst[i] = false
        }
        val w = s.width
        val h = s.height
        p.template = templateFor(li, n)
        p.variant = (rand(li, 64) * 2f).toInt()
        p.mark = walk(li, 3, 62)
        p.exit = walk(li, EXIT_MS.size, 63)
        p.exitDir = if (rand(li, 70) < 0.5f) -1f else 1f
        val prev = s.line(li - 1)
        p.arrival = if (prev != null && line.startMs - prev.endMs <= GAP_MS) walk(li - 1, EXIT_MS.size, 63) else -1
        p.arrivalDir = if (rand(li - 1, 70) < 0.5f) -1f else 1f
        p.tilt = 0f
        val tiltSide = if (rand(li, 72) < 0.5f) -1f else 1f
        p.hero = when (p.template) {
            L_JUSTIFIED -> {
                layoutJustified(p, n, w)
                if (rand(li, 71) < 0.4f) p.tilt = tiltSide * (3f + 3f * rand(li, 73))
                heldWord(line, n)
            }
            L_KEYWORD -> {
                if (rand(li, 71) < 0.3f) p.tilt = tiltSide * 2.5f
                layoutKeyword(p, line, n, w, h)
            }
            L_SPINE -> layoutSpine(p, n, w, h)
            L_STAIR -> {
                layoutStair(p, n, w)
                if (rand(li, 71) < 0.5f) p.tilt = tiltSide * (3f + 4f * rand(li, 73))
                heldWord(line, n)
            }
            L_ALTERNATE -> {
                layoutAlternate(p, n, w)
                heldWord(line, n)
            }
            else -> {
                layoutSolo(p, w, h)
                0
            }
        }
        p.tone[p.hero] = TONE_ACCENT
        fitAndCentre(p, w * MAX_W, h * MAX_H)
        chooseEntries(p, line, li)
        buildAccents(p, line, s)
        p.ready = true
    }

    /** The line's template, walked so neighbours differ; skips templates that don't suit the line's words. */
    private fun templateFor(li: Int, n: Int): Int {
        if (n == 1) return L_SOLO
        var widest = 0f
        for (i in 0 until n) widest = max(widest, aspect[i])
        var t = walk(li, 5, 61)
        repeat(5) {
            val ok = when (t) {
                L_KEYWORD -> n <= 12
                L_SPINE -> n in 3..12 && widest >= 2.6f
                L_STAIR -> n <= 9
                L_ALTERNATE -> n <= 8
                else -> true
            }
            if (ok) return t
            t = (t + 1) % 5
        }
        return L_JUSTIFIED
    }

    /** Every row scaled to fill the same width: rows of short words get big, long rows small. */
    private fun layoutJustified(p: Poster, n: Int, w: Float) {
        for (q in 0 until n) order[q] = q
        var widest = 0f
        for (i in 0 until n) widest = max(widest, aspect[i] * spriteH[i] * MAX_SCALE)
        // As wide as the widest word can go, so it alone can fill a row.
        val width = widest.coerceIn(0.4f * w, 0.66f * w)
        val rows = breakRows(0, n, width, width / 4.6f, 4)
        var top = 0f
        var a = 0
        for (r in 0 until rows) {
            val b = rowEnd[r]
            val rh = rowFit(a, b, width)
            putRow(p, a, b, 0f, top, rh, r)
            top += (VH + LEADING) * rh
            a = b
        }
        p.rows = rows
    }

    /** One huge keyword; the words before it small above, the ones after small below, flush to opposite edges. */
    private fun layoutKeyword(p: Poster, line: LyricLine, n: Int, w: Float, h: Float): Int {
        for (q in 0 until n) order[q] = q
        val k = keywordIndex(line, n)
        val kH = min(min(MAX_SCALE * spriteH[k], 0.64f * w / aspect[k]), 0.38f * h / VH)
        val kw = aspect[k] * kH
        val small = kH * 0.36f
        val limit = max(kw, 0.34f * w)
        val beforeLeft = p.variant == 0

        val above = wrap(0, k, small, limit)
        var bottom = -(LEADING * small + 0.05f * kH)
        for (r in above - 1 downTo 0) {
            val a = if (r == 0) 0 else rowEnd[r - 1]
            val b = rowEnd[r]
            val rh = min(small, rowFit(a, b, limit))
            val top = bottom - VH * rh
            putRow(p, a, b, if (beforeLeft) 0f else kw - rowNatural(a, b) * rh, top, rh, r)
            bottom = top - LEADING * rh
        }
        put(p, k, 0f, 0f, kH, above)

        // Below the keyword's descenders and the rule drawn under it.
        val below = wrap(k + 1, n, small, limit)
        var top = VH * kH + 0.34f * kH
        var a = k + 1
        for (r in 0 until below) {
            val b = rowEnd[r]
            val rh = min(small, rowFit(a, b, limit))
            putRow(p, a, b, if (beforeLeft) kw - rowNatural(a, b) * rh else 0f, top, rh, above + 1 + r)
            top += (VH + LEADING) * rh
            a = b
        }
        p.rows = above + 1 + below
        for (i in 0 until n) if (i != k) p.tone[i] = TONE_SOFT
        return k
    }

    /** The longest word turned 90° as a spine along one edge, the other words as a justified block beside it. */
    private fun layoutSpine(p: Poster, n: Int, w: Float, h: Float): Int {
        var sp = 0
        for (i in 1 until n) if (aspect[i] > aspect[sp]) sp = i
        var m = 0
        for (i in 0 until n) if (i != sp) order[m++] = i
        val spH = min(MAX_SCALE * spriteH[sp], 0.6f * h / aspect[sp])
        val spLen = aspect[sp] * spH
        val thick = VH * spH
        // Room for the spine's descenders, which face the rows.
        val gutter = 0.2f * spH + 0.012f * w

        // The block width whose height best matches the spine's length.
        var width = 0.46f * w
        var bestDiff = Float.MAX_VALUE
        for (cand in 0..3) {
            val cw = (0.34f + 0.08f * cand) * w
            val diff = abs(blockHeight(breakRows(0, m, cw, cw / 4.2f, 3), cw) - spLen)
            if (diff < bestDiff) {
                bestDiff = diff
                width = cw
            }
        }
        val rows = breakRows(0, m, width, width / 4.2f, 3)
        val bh = blockHeight(rows, width)
        val onLeft = p.variant == 0
        val rowsLeft = if (onLeft) thick + gutter else 0f
        // Flush with the spine's top or its bottom.
        var top = if (bh < spLen && rand(p.line, 66) < 0.5f) spLen - bh else 0f
        var rowsRight = 0f
        var a = 0
        for (r in 0 until rows) {
            val b = rowEnd[r]
            val rh = rowFit(a, b, width)
            putRow(p, a, b, rowsLeft, top, rh, r + 1)
            rowsRight = max(rowsRight, rowsLeft + rowNatural(a, b) * rh)
            top += (VH + LEADING) * rh
            a = b
        }
        val spineTop = if (bh > spLen) (bh - spLen) / 2f else 0f
        p.s[sp] = spH / spriteH[sp]
        p.row[sp] = 0
        if (onLeft) {
            // Reads bottom to top, letter tops facing out of the poster.
            p.rot[sp] = -90f
            p.x[sp] = -CAP_TOP * spH
            p.y[sp] = spineTop + spLen
        } else {
            // Reads top to bottom on the right edge.
            p.rot[sp] = 90f
            p.x[sp] = rowsRight + gutter + BASE * spH
            p.y[sp] = spineTop
        }
        p.rows = rows + 1
        return sp
    }

    /** Rows of equal width stepping diagonally down the poster (right, or mirrored to the left). */
    private fun layoutStair(p: Poster, n: Int, w: Float) {
        for (q in 0 until n) order[q] = q
        val rowWidth = 0.44f * w
        val rows = if (n <= 5) {
            for (q in 0 until n) rowEnd[q] = q + 1
            n
        } else {
            breakRows(0, n, rowWidth, rowWidth / 3.2f, 3)
        }
        val step = if (rows > 1) min((0.78f * w - rowWidth) / (rows - 1), 0.12f * w) else 0f
        val mirrored = p.variant == 1
        val farRight = rowWidth + (rows - 1) * step
        var top = 0f
        var a = 0
        for (r in 0 until rows) {
            val b = rowEnd[r]
            val rh = rowFit(a, b, rowWidth)
            // Mirrored stairs are flush right, each row stepping left.
            val left = if (mirrored) farRight - r * step - rowNatural(a, b) * rh else r * step
            putRow(p, a, b, left, top, rh, r)
            top += (VH + LEADING) * rh
            a = b
        }
        p.rows = rows
    }

    /** A stack alternating big rows flush left and small rows flush right (or mirrored). */
    private fun layoutAlternate(p: Poster, n: Int, w: Float) {
        for (q in 0 until n) order[q] = q
        var rows = 0
        if (n <= 6) {
            for (q in 0 until n) rowEnd[q] = q + 1
            rows = n
        } else {
            var start = 0
            while (start < n) {
                start = min(n, start + 2)
                rowEnd[rows++] = start
            }
        }
        var blockW = 0f
        var a = 0
        for (r in 0 until rows) {
            val b = rowEnd[r]
            var rh = rowFit(a, b, if (r % 2 == 0) 0.58f * w else 0.36f * w)
            // Small rows always clearly smaller than the big row above: the contrast is the point.
            if (r % 2 == 1) rh = min(rh, rowH[r - 1] * 0.62f)
            rowH[r] = rh
            rowW[r] = rowNatural(a, b) * rh
            blockW = max(blockW, rowW[r])
            a = b
        }
        val mirrored = p.variant == 1
        var top = 0f
        a = 0
        for (r in 0 until rows) {
            val b = rowEnd[r]
            val flushLeft = (r % 2 == 0) != mirrored
            putRow(p, a, b, if (flushLeft) 0f else blockW - rowW[r], top, rowH[r], r)
            if (r % 2 == 1) for (q in a until b) p.tone[q] = TONE_SOFT
            top += (VH + LEADING) * rowH[r]
            a = b
        }
        p.rows = rows
    }

    private fun layoutSolo(p: Poster, w: Float, h: Float) {
        order[0] = 0
        val rh = min(min(MAX_SCALE * spriteH[0], 0.7f * w / aspect[0]), 0.44f * h / VH)
        put(p, 0, 0f, 0f, rh, 0)
        p.rows = 1
    }

    /** Word [i] with its visible (cap-band) top-left at ([left], [top]), drawn [height] tall. */
    private fun put(p: Poster, i: Int, left: Float, top: Float, height: Float, row: Int) {
        p.s[i] = height / spriteH[i]
        p.rot[i] = 0f
        p.x[i] = left
        p.y[i] = top - CAP_TOP * height
        p.row[i] = row
    }

    /** Words order[a until b] side by side, all at the same type size (sprite height). */
    private fun putRow(p: Poster, a: Int, b: Int, left: Float, top: Float, height: Float, row: Int) {
        var x = left
        for (k in a until b) {
            val i = order[k]
            put(p, i, x, top, height, row)
            x += (aspect[i] + GAP) * height
        }
    }

    /** Width of words order[a until b] at a sprite height of 1. */
    private fun rowNatural(a: Int, b: Int): Float {
        var sum = 0f
        for (k in a until b) sum += aspect[order[k]]
        return sum + GAP * (b - a - 1)
    }

    /** The tallest the row may be drawn before any of its sprites would be scaled up past [MAX_SCALE]. */
    private fun rowCap(a: Int, b: Int): Float {
        var m = Float.MAX_VALUE
        for (k in a until b) m = min(m, spriteH[order[k]])
        return m * MAX_SCALE
    }

    private fun rowFit(a: Int, b: Int, width: Float) = min(width / rowNatural(a, b), rowCap(a, b))

    /** Greedy rows of at most [limit] wide at [height]; ends in [rowEnd]. */
    private fun wrap(from: Int, to: Int, height: Float, limit: Float): Int {
        var rows = 0
        var a = from
        while (a < to) {
            var b = a + 1
            while (b < to && rowNatural(a, b + 1) * height <= limit) b++
            rowEnd[rows++] = b
            a = b
        }
        return rows
    }

    /**
     * Breaks order[from until to] into rows filling [width], by dynamic programming over the breaks: rows near
     * [targetH] tall are cheap, rows that can't fill the width (they'd need scaling up) are expensive.
     */
    private fun breakRows(from: Int, to: Int, width: Float, targetH: Float, maxPerRow: Int): Int {
        val m = to - from
        if (m <= 0) return 0
        cost[0] = 0f
        for (j in 1..m) {
            var best = Float.MAX_VALUE
            var bi = j - 1
            for (i in max(0, j - maxPerRow) until j) {
                val c = cost[i] + rowCost(from + i, from + j, width, targetH)
                if (c < best) {
                    best = c
                    bi = i
                }
            }
            cost[j] = best
            back[j] = bi
        }
        var rows = 0
        var j = m
        while (j > 0) {
            rowTmp[rows++] = j
            j = back[j]
        }
        for (r in 0 until rows) rowEnd[r] = from + rowTmp[rows - 1 - r]
        return rows
    }

    private fun rowCost(a: Int, b: Int, width: Float, targetH: Float): Float {
        val ideal = width / rowNatural(a, b)
        val rh = min(ideal, rowCap(a, b))
        var c = 0.4f
        if (ideal > rh * 1.02f) c += 1.2f + 2.5f * (ideal / rh - 1f)
        val r = ln(rh / targetH)
        return c + 1.2f * r * r
    }

    private fun blockHeight(rows: Int, width: Float): Float {
        var total = 0f
        var a = 0
        for (r in 0 until rows) {
            val b = rowEnd[r]
            val rh = rowFit(a, b, width)
            total += VH * rh + if (r < rows - 1) LEADING * rh else 0f
            a = b
        }
        return total
    }

    /** The visible box of word [i] from its placement (rotated spines included). */
    private fun box(p: Poster, i: Int) {
        val hh = spriteH[i] * p.s[i]
        val ww = aspect[i] * hh
        when {
            p.rot[i] < 0f -> {
                p.vl[i] = p.x[i] + CAP_TOP * hh; p.vr[i] = p.x[i] + BASE * hh
                p.vt[i] = p.y[i] - ww; p.vb[i] = p.y[i]
            }
            p.rot[i] > 0f -> {
                p.vl[i] = p.x[i] - BASE * hh; p.vr[i] = p.x[i] - CAP_TOP * hh
                p.vt[i] = p.y[i]; p.vb[i] = p.y[i] + ww
            }
            else -> {
                p.vl[i] = p.x[i]; p.vr[i] = p.x[i] + ww
                p.vt[i] = p.y[i] + CAP_TOP * hh; p.vb[i] = p.y[i] + BASE * hh
            }
        }
    }

    /** Centres the composition on (0, 0) and scales it down (never up) to fit, tilt included. */
    private fun fitAndCentre(p: Poster, maxW: Float, maxH: Float) {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until p.n) {
            box(p, i)
            l = min(l, p.vl[i]); t = min(t, p.vt[i]); r = max(r, p.vr[i]); b = max(b, p.vb[i])
        }
        val bw = r - l
        val bh = b - t
        val rad = p.tilt * PI_F / 180f
        val cs = abs(cos(rad))
        val sn = abs(sin(rad))
        val f = min(1f, min(maxW / (bw * cs + bh * sn), maxH / (bw * sn + bh * cs)))
        val dx = -(l + r) / 2f
        val dy = -(t + b) / 2f
        for (i in 0 until p.n) {
            p.x[i] = (p.x[i] + dx) * f; p.y[i] = (p.y[i] + dy) * f; p.s[i] *= f
            p.vl[i] = (p.vl[i] + dx) * f; p.vr[i] = (p.vr[i] + dx) * f
            p.vt[i] = (p.vt[i] + dy) * f; p.vb[i] = (p.vb[i] + dy) * f
        }
        p.left = (l + dx) * f; p.right = (r + dx) * f
        p.top = (t + dy) * f; p.bottom = (b + dy) * f
    }

    /** The word to make huge: long, held, or the line's last, preferring real words over "a", "to". */
    private fun keywordIndex(line: LyricLine, n: Int): Int {
        var best = 0
        var bestScore = -1f
        for (i in 0 until n) {
            val wd = line.words[i]
            val letters = wd.text.count { it.isLetterOrDigit() }
            var score = min(letters, 9) + (wd.endMs - wd.startMs) / 250f + if (i == n - 1) 1.5f else 0f
            if (letters < 3) score *= 0.4f
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** The longest-sung real word: it keeps the album colour once sung. */
    private fun heldWord(line: LyricLine, n: Int): Int {
        var best = 0
        var bestScore = -1f
        for (i in 0 until n) {
            val wd = line.words[i]
            val letters = wd.text.count { it.isLetterOrDigit() }
            val score = (wd.endMs - wd.startMs) * if (letters >= 3) 1f else 0.35f
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    /** Each word's way in, never the same as the word before; held notes and a lingering last word stamp in. */
    private fun chooseEntries(p: Poster, line: LyricLine, li: Int) {
        val pool = when (p.template) {
            L_JUSTIFIED -> POOL_JUSTIFIED
            L_KEYWORD -> POOL_SMALL
            L_SPINE -> POOL_SPINE
            L_STAIR -> POOL_STAIR
            else -> POOL_ALTERNATE
        }
        // At most two bursts per poster: the longest held note, and the last word if it lingers.
        var held = -1
        var longest = HELD_MS - 1
        for (i in 0 until p.n) {
            val d = line.words[i].endMs - line.words[i].startMs
            if (d > longest) {
                longest = d
                held = i
            }
        }
        if (held >= 0) p.burst[held] = true
        val last = line.words[p.n - 1]
        if (p.n >= 2 && last.endMs - last.startMs >= 380) p.burst[p.n - 1] = true

        var prev = -1
        for (i in 0 until p.n) {
            val e = when {
                p.burst[i] || p.template == L_SOLO || (p.template == L_KEYWORD && i == p.hero) ->
                    if (rand(li, i, 68) < 0.6f) E_STAMP else E_DROP
                p.template == L_SPINE && i == p.hero -> if (rand(li, 69) < 0.5f) E_SLIDE else E_DROP
                else -> {
                    var q = (rand(li, i, 67) * pool.size).toInt()
                    if (pool[q] == prev) q = (q + 1) % pool.size
                    pool[q]
                }
            }
            p.entry[i] = e
            prev = e
        }
    }

    /** The graphic furniture of each template, timed to draw in with the composition and its words. */
    private fun buildAccents(p: Poster, line: LyricLine, s: SceneContext) {
        p.accentCount = 0
        val d = s.density
        val w = s.width
        val h = s.height
        val first = line.words[0].startMs - LEAD_MS
        val gapTop = 0.032f * h
        val gapBottom = 0.05f * h
        val thin = 1.5f * d
        val thick = 4.5f * d
        val l = p.left
        val t = p.top
        val r = p.right
        val b = p.bottom
        when (p.template) {
            L_JUSTIFIED -> {
                if (p.variant == 0) {
                    accent(p, A_RULE_H, l, t - gapTop, r - l, thin, first - 80, 700f, 0, false, 0.7f)
                    accent(p, A_SQUARE, l + 5f * d, t - gapTop - 13f * d, 10f * d, 0f, first + 120, 420f, 0, true, 1f)
                } else {
                    val m = 0.03f * h
                    for (k in 0..3) {
                        val cx = if (k % 2 == 0) l - m else r + m
                        val cy = if (k < 2) t - m else b + m
                        accent(p, A_CROSS, cx, cy, 9f * d, thin, first - 60 + k * 90L, 360f, 0, false, 0.75f)
                    }
                }
                accent(p, A_PROGRESS, l, b + gapBottom, r - l, thick, first, 500f, 0, true, 1f)
            }
            L_KEYWORD -> {
                val k = p.hero
                val at = line.words[k].startMs - LEAD_MS
                // Under the keyword's descenders, above the small rows that follow it.
                val ruleY = p.vb[k] + 0.42f * (p.vb[k] - p.vt[k])
                accent(p, A_RULE_H, p.vl[k], ruleY, p.vr[k] - p.vl[k], thin, at - 40, 600f, p.variant, false, 0.75f)
                accent(p, A_SQUARE, p.vr[k] + 12f * d, p.vt[k] + 5f * d, 10f * d, 0f, at + 160, 420f, 0, true, 1f)
                accent(p, A_PROGRESS, l, t - gapTop, r - l, thick * 0.8f, first, 500f, 0, true, 1f)
            }
            L_SPINE -> {
                val sp = p.hero
                var rowsL = Float.MAX_VALUE
                var rowsR = -Float.MAX_VALUE
                for (i in 0 until p.n) if (i != sp) {
                    rowsL = min(rowsL, p.vl[i])
                    rowsR = max(rowsR, p.vr[i])
                }
                val onLeft = p.rot[sp] < 0f
                val gx = if (onLeft) (p.vr[sp] + rowsL) / 2f else (rowsR + p.vl[sp]) / 2f
                accent(p, A_RULE_V, gx, t, thin, b - t, first - 80, 800f, 1, false, 0.6f)
                val sx = (p.vl[sp] + p.vr[sp]) / 2f
                val spAt = line.words[sp].startMs - LEAD_MS
                accent(p, A_SQUARE, sx, if (onLeft) t - 16f * d else b + 16f * d, 10f * d, 0f, spAt + 200, 420f, 0, true, 1f)
                accent(p, A_PROGRESS, rowsL, b + gapBottom, rowsR - rowsL, thick, first, 500f, 0, true, 1f)
            }
            L_STAIR -> {
                // A tick before each step, landing with the step's first word.
                val len = 0.035f * w
                for (row in 0 until p.rows) {
                    val i0 = rowBox(p, row)
                    if (i0 < 0) continue
                    val at = line.words[i0].startMs - LEAD_MS
                    accent(p, A_RULE_H, rbL - len - 0.012f * w, (rbT + rbB) / 2f, len, thick, at - 40, 320f, 0, true, 1f)
                }
                accent(p, A_RULE_H, l, t - gapTop, r - l, thin, first - 80, 700f, if (p.variant == 1) 1 else 0, false, 0.6f)
                accent(p, A_PROGRESS, l, b + gapBottom, r - l, thick, first, 500f, 0, true, 1f)
            }
            L_ALTERNATE -> {
                // Leaders shoot from each short row across its empty side, ending in a square.
                val gap = 0.025f * w
                for (row in 0 until p.rows) {
                    val i0 = rowBox(p, row)
                    if (i0 < 0) continue
                    val at = line.words[i0].startMs - LEAD_MS
                    val y = (rbT + rbB) / 2f
                    if (rbL - l > 0.12f * (r - l)) {
                        accent(p, A_RULE_H, l, y, rbL - gap - l, thin, at, 420f, 1, false, 0.55f)
                        accent(p, A_SQUARE, l, y, 8f * d, 0f, at + 300, 380f, 0, true, 1f)
                    } else if (r - rbR > 0.12f * (r - l)) {
                        accent(p, A_RULE_H, rbR + gap, y, r - rbR - gap, thin, at, 420f, 0, false, 0.55f)
                        accent(p, A_SQUARE, r, y, 8f * d, 0f, at + 300, 380f, 0, true, 1f)
                    }
                }
                accent(p, A_PROGRESS, l, t - gapTop, r - l, thick * 0.8f, first, 500f, 0, true, 1f)
            }
            else -> {
                accent(p, A_RULE_H, l, t - gapTop, r - l, thin, first - 60, 700f, 2, false, 0.7f)
                accent(p, A_PROGRESS, l, b + gapBottom, r - l, thick, first, 500f, 0, true, 1f)
                val m = 0.06f * h
                for (k in 0..3) {
                    val cx = if (k % 2 == 0) l - m else r + m
                    val cy = if (k < 2) t - m else b + m
                    accent(p, A_CROSS, cx, cy, 9f * d, thin, first + k * 80L, 360f, 0, false, 0.6f)
                }
            }
        }
    }

    private fun accent(p: Poster, kind: Int, x: Float, y: Float, w: Float, h: Float, at: Long, ms: Float, from: Int, primary: Boolean, alpha: Float) {
        if (p.accentCount >= p.accents.size) return
        val a = p.accents[p.accentCount++]
        a.kind = kind; a.x = x; a.y = y; a.w = w; a.h = h
        a.at = at; a.ms = ms; a.from = from; a.primary = primary; a.alpha = alpha
    }

    /** Bounds of row [row] into rb*; returns its first word, or -1 if the row is empty. */
    private fun rowBox(p: Poster, row: Int): Int {
        var first = -1
        rbL = Float.MAX_VALUE; rbT = Float.MAX_VALUE; rbR = -Float.MAX_VALUE; rbB = -Float.MAX_VALUE
        for (i in 0 until p.n) {
            if (p.row[i] != row) continue
            if (first < 0) first = i
            rbL = min(rbL, p.vl[i]); rbT = min(rbT, p.vt[i]); rbR = max(rbR, p.vr[i]); rbB = max(rbB, p.vb[i])
        }
        return first
    }

    private fun ensureScratch(n: Int) {
        if (aspect.size >= n) return
        val cap = n + 8
        aspect = FloatArray(cap); spriteH = FloatArray(cap); order = IntArray(cap)
        rowEnd = IntArray(cap + 1); rowTmp = IntArray(cap + 1); cost = FloatArray(cap + 1); back = IntArray(cap + 1)
        rowH = FloatArray(cap + 1); rowW = FloatArray(cap + 1)
    }

    // ---- Drawing a poster ----

    /**
     * The poster at exit progress [exitT] (0: standing). Poster-wide exits (zoom, whip, flip) transform the whole
     * matrix and leave smeared copies behind it; the others move word by word in [drawWords].
     */
    private fun drawPoster(c: Canvas, s: SceneContext, p: Poster, exitT: Float, arriving: Boolean) {
        val line = s.line(p.line) ?: return
        val pos = s.positionMs
        arrive(p, s, line, arriving)
        val cx = s.width * 0.5f
        val cy = s.height * CENTER_Y
        val t = exitT.coerceIn(0f, 1f)
        val alpha = posterAlpha(p.exit, t)
        if (t > 0.04f && (p.exit == X_WHIP || p.exit == X_ZOOM)) {
            // A zoomed copy covers most of the screen: fewer of those, this GPU is easily filled.
            for (g in (if (p.exit == X_WHIP) GHOSTS else GHOSTS - 1) downTo 1) {
                setMatrix(p, s, (t - g * 0.07f).coerceAtLeast(0f), cx, cy)
                c.save()
                c.concat(matrix)
                drawWords(c, s, p, line, pos, t, alpha * 0.3f / g, ghost = true)
                c.restore()
            }
        }
        setMatrix(p, s, t, cx, cy)
        c.save()
        c.concat(matrix)
        drawAccents(c, s, p, line, pos, t, alpha)
        drawWords(c, s, p, line, pos, t, alpha, ghost = false)
        c.restore()
        if (arriving && t <= 0f) fireBursts(s, p, line, pos)
    }

    /** The new poster comes in continuing the old one's way out: from depth after a zoom, from the side after a whip. */
    private fun arrive(p: Poster, s: SceneContext, line: LyricLine, on: Boolean) {
        arrScale = 1f
        arrDx = 0f
        arrDy = 0f
        if (!on) return
        val first = line.words.firstOrNull() ?: return
        val t = ((s.positionMs - (first.startMs - LEAD_MS)) / ARRIVE_MS).coerceIn(0f, 1f)
        if (t >= 1f) return
        val k = 1f - easeOutCubic(t)
        when (p.arrival) {
            X_ZOOM -> arrScale = 1f - 0.22f * k
            X_WHIP -> arrDx = -p.arrivalDir * 0.12f * s.width * k
            X_FLIP -> arrDy = -0.07f * s.height * k
            else -> arrScale = 1f - 0.05f * k
        }
    }

    private fun setMatrix(p: Poster, s: SceneContext, t: Float, cx: Float, cy: Float) {
        matrix.reset()
        if (p.tilt != 0f) matrix.postRotate(p.tilt)
        if (arrScale != 1f) matrix.postScale(arrScale, arrScale)
        if (arrDx != 0f || arrDy != 0f) matrix.postTranslate(arrDx, arrDy)
        if (t > 0f) when (p.exit) {
            X_ZOOM -> {
                // Flies past the camera.
                val z = 1f + 1.9f * easeInCubic(t)
                matrix.postScale(z, z)
            }
            X_WHIP -> {
                // A small wind-up the other way, then off with the top leaning into the motion.
                val k = easeInCubic(t)
                val windUp = 0.04f * sin(PI_F * min(1f, t * 2.2f))
                matrix.postSkew(-p.exitDir * 0.32f * k, 0f)
                matrix.postTranslate(p.exitDir * (1.25f * k - windUp) * s.width, 0f)
            }
            X_FLIP -> {
                // Folds back over its bottom edge.
                val k = easeInCubic(t)
                matrix.postScale(1f + 0.18f * k, cos(k * PI_F / 2f), 0f, p.bottom)
                matrix.postTranslate(0f, k * 0.06f * s.height)
            }
        }
        matrix.postTranslate(cx, cy)
    }

    private fun posterAlpha(exit: Int, t: Float): Float = if (t <= 0f) 1f else when (exit) {
        X_ZOOM -> 1f - t * t
        X_WHIP -> 1f - t * t * t * t
        X_FLIP -> 1f - t * t * t
        else -> 1f
    }

    private fun drawWords(c: Canvas, s: SceneContext, p: Poster, line: LyricLine, pos: Long, t: Float, alpha: Float, ghost: Boolean) {
        for (i in 0 until p.n) {
            val word = line.words.getOrNull(i) ?: break
            val since = (pos - (word.startMs - LEAD_MS)).toFloat()
            if (since < 0f) continue // not sung yet: the poster never gives the line away early
            val sprite = s.sprite(p.line, i) ?: continue
            var a = alpha
            var fold = 0f
            c.save()
            if (t > 0f) when (p.exit) {
                X_COLLAPSE -> {
                    // Row after row folds shut, top first.
                    val rows = max(1, p.rows)
                    val stagger = if (rows > 1) 0.45f / (rows - 1) else 0f
                    fold = ((t - p.row[i] * stagger) / 0.55f).coerceIn(0f, 1f)
                }
                X_DROP -> {
                    val delay = hash(p.line, i, 77) * 0.35f
                    val k = ((t - delay) / 0.65f).coerceIn(0f, 1f)
                    if (k > 0f) {
                        val side = if (hash(p.line, i, 78) < 0.5f) -1f else 1f
                        c.translate(0f, k * k * s.height * 0.95f)
                        c.rotate(side * k * 38f, (p.vl[i] + p.vr[i]) / 2f, (p.vt[i] + p.vb[i]) / 2f)
                        a *= 1f - k * k
                    }
                }
                X_SPLIT -> {
                    // Alternate rows shoot off in opposite directions.
                    val dir = if (p.row[i] % 2 == 0) p.exitDir else -p.exitDir
                    c.translate(dir * easeInCubic(t) * s.width * 1.2f, 0f)
                    a *= 1f - t * t * t
                }
            }
            if (a > 0.01f) {
                c.translate(p.x[i], p.y[i])
                if (p.rot[i] != 0f) c.rotate(p.rot[i])
                c.scale(p.s[i], p.s[i])
                drawWordBody(c, s, p, line, i, sprite, word, since, pos, a, ghost, fold)
            }
            c.restore()
        }
    }

    /** One word in its own sprite space: exit fold, beat thump, highlight, then its entrance or its settled self. */
    private fun drawWordBody(
        c: Canvas,
        s: SceneContext,
        p: Poster,
        line: LyricLine,
        i: Int,
        sprite: WordSprite,
        word: LyricWord,
        since: Float,
        pos: Long,
        alpha: Float,
        ghost: Boolean,
        fold: Float,
    ) {
        val w = sprite.width.toFloat()
        val h = sprite.height.toFloat()
        val mid = h * MID
        var a = alpha
        if (fold > 0f) {
            // The row squashes into an album-colour rule, which then shrinks away to nothing.
            val squash = easeInCubic(min(1f, fold / 0.55f))
            val shrink = easeInCubic(((fold - 0.55f) / 0.45f).coerceIn(0f, 1f)) * w / 2f
            val th = 1.8f * s.density / p.s[i]
            fill.color = cPrimary
            fill.alpha = (255 * a * min(1f, fold * 4f)).toInt()
            c.drawRect(shrink, mid - th, w - shrink, mid + th, fill)
            if (squash >= 0.99f) return
            c.scale(1f + 0.12f * fold, 1f - squash, w / 2f, mid)
            a *= 1f - 0.5f * squash
        }

        // The word is "on" from its appearance until the next word appears (the last one: until the line ends).
        val next = line.words.getOrNull(i + 1)
        val activeEnd = if (next != null) next.startMs - LEAD_MS else max(line.endMs, word.endMs)
        val active = pos < activeEnd
        val outK = if (active) 0f else easeInOutCubic(((pos - activeEnd) / 280f).coerceIn(0f, 1f))
        val inK = easeOutCubic((since / 200f).coerceIn(0f, 1f))
        val settled = when (p.tone[i]) {
            TONE_ACCENT -> cPrimary
            TONE_SOFT -> cSoft
            else -> cSettled
        }
        cut1 = w
        cut2 = w
        col0 = settled
        col1 = settled
        col2 = settled
        if (!ghost && outK < 1f) {
            val thump = 1f + 0.05f * s.beat * (1f - outK)
            c.scale(thump, thump, w / 2f, mid)
            when (p.mark) {
                M_BAR -> {
                    // An album-colour bar sweeps in behind the word, and sweeps off to the right when it's done.
                    val pad = h * 0.07f
                    val l = -pad + (w + 2f * pad) * outK
                    val r = -pad + (w + 2f * pad) * inK
                    if (r > l) {
                        fill.color = cPrimary
                        fill.alpha = (255 * a).toInt()
                        c.drawRect(l, h * BAR_TOP, r, h * BAR_BOTTOM, fill)
                    }
                    cut1 = l
                    cut2 = r
                    col1 = cOnBar
                    col2 = WHITE
                }
                M_FILL -> if (active) {
                    // Karaoke: the sung part fills with the album colour, the rest waits dim.
                    cut1 = 0f
                    cut2 = w * wordProgress(word, pos)
                    col1 = cPrimary
                    col2 = cDim
                } else {
                    col0 = mix(cPrimary, settled, outK)
                }
                else -> {
                    // A thick underline grows with the singing, then pulls away from the left.
                    val prog = if (active) max(wordProgress(word, pos), 0.15f * inK) else 1f
                    val l = w * outK
                    val r = w * prog
                    if (r > l) {
                        val y = h * (BASE + 0.05f)
                        fill.color = cPrimary
                        fill.alpha = (255 * a).toInt()
                        c.drawRect(l, y, r, y + h * 0.06f * (1f + 0.5f * s.beat), fill)
                    }
                    col0 = if (active) WHITE else mix(WHITE, settled, outK)
                }
            }
        }

        val e = p.entry[i]
        when {
            ghost -> drawSlices(c, sprite, a)
            e == E_STAMP && since < ENTRY_MS[E_STAMP] -> drawStamp(c, sprite, since, a, if (i % 2 == 0) 1f else -1f)
            e != E_STAMP && since < entryTotal(e, sprite.glyphCount) -> {
                if (e == E_SLOT) {
                    // The slot the letters rise out of: a rule that's there only while they come up.
                    val total = entryTotal(e, sprite.glyphCount)
                    val k = easeOutExpo((since / 180f).coerceIn(0f, 1f)) * (1f - ((since - total + 160f) / 160f).coerceIn(0f, 1f))
                    val th = 1.2f * s.density / p.s[i]
                    fill.color = cPrimary
                    fill.alpha = (255 * a * k).toInt()
                    c.drawRect(0f, h * SLOT_FLOOR - th, w * k, h * SLOT_FLOOR + th, fill)
                }
                drawLetters(c, sprite, e, since, a, p.line * 131 + i)
            }
            else -> drawSlices(c, sprite, a)
        }
    }

    /** Letter by letter, staggered. Colours follow the word's slices, so the highlight is right even mid-entrance. */
    private fun drawLetters(c: Canvas, sprite: WordSprite, e: Int, since: Float, a: Float, seed: Int) {
        val n = sprite.glyphCount
        val h = sprite.height.toFloat()
        val mid = h * MID
        val stagger = stagger(n)
        val dur = ENTRY_MS[e]
        for (g in 0 until n) {
            if (glyphIsBlank(sprite, g)) continue
            val order = when (e) {
                E_FLIP -> abs(g - (n - 1) / 2f) // from the middle out
                E_POP -> hash(seed, g, 69) * (n - 1) // scattered
                else -> g.toFloat()
            }
            val t = ((since - order * stagger) / dur).coerceIn(0f, 1f)
            if (t <= 0f) continue
            val gx = glyphX(sprite, g)
            val gc = glyphCenter(sprite, g)
            val color = if (gc < cut1) col0 else if (gc < cut2) col1 else col2
            var la = 1f
            c.save()
            when (e) {
                E_DROP -> {
                    c.translate(0f, -(1f - easeOutBounce(t)) * h * 1.05f)
                    c.rotate((1f - t) * (if (g % 2 == 0) 16f else -16f), gc, mid)
                    la = min(1f, t * 5f)
                }
                E_FLIP -> {
                    c.scale(1f, easeOutBack(t, 2.4f), gc, mid)
                    la = min(1f, t * 3f)
                }
                E_SLIDE -> {
                    // Flies in from the right, stretched by its speed.
                    val k = easeOutExpo(t)
                    c.translate((1f - k) * h * 1.3f, 0f)
                    c.scale(1f + (1f - k) * 0.9f, 1f, gx, 0f)
                    la = min(1f, t * 2.5f)
                }
                E_POP -> {
                    val k = easeOutBack(t, 2.8f)
                    c.rotate((1f - t) * -28f, gc, mid)
                    c.scale(k, k, gc, mid)
                    la = min(1f, t * 3f)
                }
            }
            val alpha = ((color ushr 24) * a * la).toInt()
            if (alpha > 0) {
                text.color = color
                text.alpha = alpha
                if (e == E_SLOT) {
                    glyphInSlot(c, sprite, g, gx, (1f - easeOutExpo(t)) * h * 0.8f, h * SLOT_FLOOR)
                } else {
                    drawGlyph(c, sprite, g, gx, 0f, text)
                }
            }
            c.restore()
        }
    }

    /**
     * Letter [g] lowered by [dy] and cut off at [floor]: it rises out of a slot. Cropping the source rect does what
     * a clipRect would, without a (stencil) clip under the stage's 3D camera.
     */
    private fun glyphInSlot(c: Canvas, sprite: WordSprite, g: Int, x: Float, dy: Float, floor: Float) {
        val visible = min((floor - dy).toInt(), sprite.height)
        if (visible <= 0) return
        val l = sprite.glyphLeft[g].toInt().coerceIn(0, sprite.width)
        val r = ceil(sprite.glyphRight[g]).toInt().coerceIn(l, sprite.width)
        if (r <= l) return
        src.set(l, 0, r, visible)
        dst.set(x, dy, x + (r - l), dy + visible)
        c.drawBitmap(sprite.text, src, dst, text)
    }

    /** The whole word slams in from huge, two faint copies collapsing onto it like the smear of the hit. */
    private fun drawStamp(c: Canvas, sprite: WordSprite, since: Float, a: Float, side: Float) {
        val w = sprite.width.toFloat()
        val mid = sprite.height * MID
        val t = (since / ENTRY_MS[E_STAMP]).coerceIn(0f, 1f)
        val k = easeOutExpo(t)
        val grow = (1f - k) * 1.6f
        for (g in 2 downTo 1) {
            val sc = 1f + grow * (1f + 0.35f * g)
            c.save()
            c.scale(sc, sc, w / 2f, mid)
            text.color = WHITE
            text.alpha = (255 * a * (1f - k) * 0.25f / g).toInt()
            c.drawBitmap(sprite.text, 0f, 0f, text)
            c.restore()
        }
        c.save()
        c.rotate((1f - k) * -8f * side, w / 2f, mid)
        c.scale(1f + grow, 1f + grow, w / 2f, mid)
        drawSlices(c, sprite, a * sqrt(t))
        c.restore()
    }

    /** The word in up to three colour slices (see [cut1]); one bitmap draw when it's a single colour. */
    private fun drawSlices(c: Canvas, sprite: WordSprite, a: Float) {
        val w = sprite.width
        val k1 = cut1.roundToInt().coerceIn(0, w)
        val k2 = cut2.roundToInt().coerceIn(k1, w)
        slice(c, sprite, 0, k1, col0, a)
        slice(c, sprite, k1, k2, col1, a)
        slice(c, sprite, k2, w, col2, a)
    }

    private fun slice(c: Canvas, sprite: WordSprite, l: Int, r: Int, color: Int, a: Float) {
        if (r <= l) return
        val alpha = ((color ushr 24) * a).toInt()
        if (alpha <= 0) return
        text.color = color
        text.alpha = alpha
        if (l == 0 && r == sprite.width) {
            c.drawBitmap(sprite.text, 0f, 0f, text)
            return
        }
        src.set(l, 0, r, sprite.height)
        dst.set(l.toFloat(), 0f, r.toFloat(), sprite.height.toFloat())
        c.drawBitmap(sprite.text, src, dst, text)
    }

    private fun drawAccents(c: Canvas, s: SceneContext, p: Poster, line: LyricLine, pos: Long, t: Float, alpha: Float) {
        // Word-by-word exits leave the furniture behind: it fades quickly instead.
        val perWord = p.exit == X_COLLAPSE || p.exit == X_DROP || p.exit == X_SPLIT
        val a0 = alpha * if (perWord) (1f - t * 1.6f).coerceIn(0f, 1f) else 1f
        if (a0 <= 0.01f) return
        val beat = s.beat
        for (k in 0 until p.accentCount) {
            val ac = p.accents[k]
            val at = ((pos - ac.at) / ac.ms).coerceIn(0f, 1f)
            if (at <= 0f) continue
            val e = easeOutExpo(at)
            fill.color = if (ac.primary) cPrimary else cLight
            fill.alpha = (255 * ac.alpha * a0).toInt()
            when (ac.kind) {
                A_RULE_H -> {
                    val th = ac.h * (if (ac.primary) 1f + 0.6f * beat else 1f) / 2f
                    val len = ac.w * e
                    val x0 = when (ac.from) {
                        0 -> ac.x
                        1 -> ac.x + ac.w - len
                        else -> ac.x + (ac.w - len) / 2f
                    }
                    c.drawRect(x0, ac.y - th, x0 + len, ac.y + th, fill)
                }
                A_RULE_V -> {
                    val th = ac.w / 2f
                    val len = ac.h * e
                    val y0 = when (ac.from) {
                        0 -> ac.y
                        1 -> ac.y + ac.h - len
                        else -> ac.y + (ac.h - len) / 2f
                    }
                    c.drawRect(ac.x - th, y0, ac.x + th, y0 + len, fill)
                }
                A_SQUARE -> {
                    val half = ac.w / 2f * easeOutBack(at, 2.6f) * (1f + 0.35f * beat)
                    if (half > 0f) c.drawRect(ac.x - half, ac.y - half, ac.x + half, ac.y + half, fill)
                }
                A_PROGRESS -> {
                    // A faint track drawing in, and the album-colour bar following the line.
                    val th = ac.h / 2f * (1f + 0.6f * beat)
                    fill.color = cLight
                    fill.alpha = (255 * 0.16f * a0).toInt()
                    c.drawRect(ac.x, ac.y - th * 0.4f, ac.x + ac.w * e, ac.y + th * 0.4f, fill)
                    val len = ac.w * lineProgress(line, pos)
                    if (len > 0f) {
                        fill.color = cPrimary
                        fill.alpha = (255 * ac.alpha * a0).toInt()
                        c.drawRect(ac.x, ac.y - th, ac.x + len, ac.y + th, fill)
                    }
                }
                A_CROSS -> {
                    val arm = ac.w * e
                    val th = ac.h / 2f
                    c.drawRect(ac.x - arm, ac.y - th, ac.x + arm, ac.y + th, fill)
                    c.drawRect(ac.x - th, ac.y - arm, ac.x + th, ac.y + arm, fill)
                }
            }
        }
    }

    /** Sparks and a camera punch as a held note or a lingering last word lands; once per word. */
    private fun fireBursts(s: SceneContext, p: Poster, line: LyricLine, pos: Long) {
        for (i in 0 until p.n) {
            if (!p.burst[i]) continue
            val wd = line.words.getOrNull(i) ?: continue
            val land = wd.startMs + 70
            // Only at the moment it lands: not when seeking into the middle of it.
            if (pos < land || pos > land + 500) continue
            if (s.sprite(p.line, i) == null) continue
            if (!s.effects.landOnce(BURST_KEY or (p.line.toLong() shl 16) or i.toLong())) continue
            point[0] = (p.vl[i] + p.vr[i]) / 2f
            point[1] = (p.vt[i] + p.vb[i]) / 2f
            matrix.mapPoints(point)
            burstColors[0] = s.ink.primary
            burstColors[1] = s.ink.secondary
            burstColors[2] = s.ink.light
            s.effects.burst(point[0], point[1], burstColors, s.density, p.line * 31 + i)
            punchAt = s.clockMs
        }
    }

    // ---- Camera ----

    /**
     * Each poster is seen at its own slight angle, the camera pushing in slowly as it builds and leaning toward the
     * word being sung. Exits kick it (zoom, tilt, turn), landings punch it in; a slow sway keeps it alive when paused.
     */
    private fun updateCamera(s: SceneContext, li: Int, showCurrent: Boolean, gapT: Float, leaveT: Float) {
        val now = s.clockMs
        val sec = now / 1000f
        var rx = 0f
        var ry = 0f
        var rz = 0f
        var zoom = 1f
        var sx = 0f
        var sy = 0f
        val line = s.line(li)
        if (showCurrent && line != null) {
            val p = current
            val side = if (li % 2 == 0) 1f else -1f
            ry = side * (3f + 5f * hash(li, 81, 0))
            rx = (hash(li, 82, 0) - 0.5f) * 8f
            rz = (hash(li, 83, 0) - 0.5f) * 3f
            zoom += 0.045f * lineProgress(line, s.positionMs)
            val nw = newestWordAt(line, s.positionMs, LEAD_MS)
            if (nw in 0 until p.n) {
                sx = -(p.vl[nw] + p.vr[nw]) * 0.5f * FOLLOW
                sy = -(p.vt[nw] + p.vb[nw]) * 0.5f * FOLLOW
            }
        }
        val exiting: Poster?
        val et: Float
        if (leaveT >= 0f) {
            exiting = leaving
            et = leaveT
        } else if (showCurrent && gapT > 0f) {
            exiting = current
            et = gapT
        } else {
            exiting = null
            et = 0f
        }
        if (exiting != null) {
            val b = sin(PI_F * et)
            when (exiting.exit) {
                X_ZOOM -> zoom += 0.12f * b
                X_FLIP -> rx += 13f * b
                X_WHIP -> {
                    ry += exiting.exitDir * 9f * b
                    rz -= exiting.exitDir * 2f * b
                }
                X_SPLIT -> rz += 2.5f * b * exiting.exitDir
                X_DROP -> rx -= 6f * b
                else -> zoom -= 0.03f * b
            }
        }
        val sincePunch = (now - punchAt).toFloat()
        if (sincePunch >= 0f && sincePunch < 1500f) zoom += 0.11f * exp(-sincePunch / 240f)
        rx += 1.1f * sin(sec / 6.3f + 1f)
        ry += 1.6f * sin(sec / 4.7f)
        rz += 0.4f * sin(sec / 9.1f)

        val cam = s.camera
        cam.rotX = camRotX.update(rx, now)
        cam.rotY = camRotY.update(ry, now)
        cam.rotZ = camRotZ.update(rz, now)
        cam.zoom = camZoom.update(zoom, now)
        cam.shiftX = camShiftX.update(sx, now)
        cam.shiftY = camShiftY.update(sy, now)
    }

    // ---- Intro and breaks ----

    /** Crop marks of an empty poster breathing, and a countdown rule filling toward the next line. */
    private fun drawIdle(c: Canvas, s: SceneContext, a: Float, progress: Float) {
        val d = s.density
        val k = easeOutCubic(a)
        val sec = s.clockMs / 1000f
        val beat = s.beat
        val cx = s.width * 0.5f
        val cy = s.height * CENTER_Y

        val breathe = (1f + 0.018f * sin(sec * 1.2f) + 0.012f * beat) * (0.8f + 0.2f * k)
        val fw = s.width * 0.21f * breathe
        val fh = s.height * 0.19f * breathe
        val arm = 16f * d * k
        val th = 0.8f * d
        fill.color = cLight
        fill.alpha = (255 * 0.55f * a).toInt()
        for (corner in 0..3) {
            val sx = if (corner % 2 == 0) -1f else 1f
            val sy = if (corner < 2) -1f else 1f
            val x = cx + sx * fw
            val y = cy + sy * fh
            // Arms pointing into the frame.
            rect(c, x, y - th, x - sx * arm, y + th)
            rect(c, x - th, y, x + th, y - sy * arm)
        }

        val half = s.width * 0.13f * k
        fill.alpha = (255 * 0.22f * a).toInt()
        c.drawRect(cx - half, cy - th, cx + half, cy + th, fill)
        val px = cx - half + 2f * half * progress
        val bar = (1.6f + 1.6f * beat) * d
        fill.color = cPrimary
        fill.alpha = (255 * a).toInt()
        c.drawRect(cx - half, cy - bar, px, cy + bar, fill)
        val head = (4f + 2.5f * beat) * d * k
        c.drawRect(px - head, cy - head, px + head, cy + head, fill)

        // Three marks above the track's start, breathing in turn.
        for (q in 0..2) {
            val phase = sin(sec * 3.2f - q * 0.8f)
            val r = (3f + phase + 2f * beat) * d * k
            val x = cx - half + r + q * 13f * d
            val y = cy - 24f * d
            fill.color = if (q == 0) cPrimary else cLight
            fill.alpha = (255 * a * (0.55f + 0.3f * phase)).toInt()
            c.drawRect(x - r, y - r, x + r, y + r, fill)
        }
    }

    private fun idleProgress(s: SceneContext, li: Int): Float {
        val pos = s.positionMs
        if (li < 0) {
            val first = s.line(0) ?: return 0f
            return if (first.startMs <= 0) 1f else (pos.toFloat() / first.startMs).coerceIn(0f, 1f)
        }
        val line = s.line(li) ?: return 0f
        val start = line.endMs + LINGER_MS
        // The song's outro: nothing to count down to, the cursor just sweeps.
        val next = s.line(li + 1) ?: return 0.5f + 0.5f * sin(s.clockMs / 1600f)
        return ((pos - start).toFloat() / (next.startMs - start).coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // ---- Small helpers ----

    private fun rect(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.drawRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), fill)
    }

    private fun colors(ink: StageInk) {
        if (ink === inkFor) return
        inkFor = ink
        cPrimary = ink.primary
        cLight = ink.light
        cSettled = withAlpha(ink.light, 0.94f)
        cSoft = withAlpha(mix(ink.secondary, ink.light, 0.45f), 0.9f)
        cDim = withAlpha(ink.light, 0.38f)
        // Text on the bar: dark on a light album colour (yellow, mint), white on a deep one.
        val lum = (0.299f * ((cPrimary shr 16) and 0xFF) + 0.587f * ((cPrimary shr 8) and 0xFF) + 0.114f * (cPrimary and 0xFF)) / 255f
        cOnBar = if (lum > 0.62f) 0xFF121212.toInt() else WHITE
    }

    private fun lineProgress(line: LyricLine, pos: Long): Float =
        ((pos - line.startMs).toFloat() / (line.endMs - line.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)

    private fun stagger(n: Int) = min(34f, 300f / max(1, n - 1))

    private fun entryTotal(e: Int, n: Int): Float {
        val span = if (e == E_FLIP) (n - 1) / 2f else (n - 1).toFloat()
        return span * stagger(n) + ENTRY_MS[e]
    }

    /** A step through [count] choices per line from the start of the song: neighbours always differ. */
    private fun walk(line: Int, count: Int, salt: Int): Int {
        var v = (hash(salt, 0, 0) * count).toInt()
        for (k in 1..line) v = (v + 1 + (hash(k, salt, 0) * (count - 1)).toInt()) % count
        return v
    }

    /** [rand] without its vararg array, for use every frame. */
    private fun hash(a: Int, b: Int, c: Int): Float {
        var h = 0x2545F491
        h = (h xor a) * 0x5bd1e995
        h = h xor (h ushr 15)
        h = (h xor b) * 0x5bd1e995
        h = h xor (h ushr 15)
        h = (h xor c) * 0x5bd1e995
        h = h xor (h ushr 15)
        return (h and 0x7fffffff) % 100_000 / 100_000f
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        val v = 1f - u
        val aa = ((a ushr 24) * v + (b ushr 24) * u).toInt()
        val rr = (((a shr 16) and 0xFF) * v + ((b shr 16) and 0xFF) * u).toInt()
        val gg = (((a shr 8) and 0xFF) * v + ((b shr 8) and 0xFF) * u).toInt()
        val bb = ((a and 0xFF) * v + (b and 0xFF) * u).toInt()
        return (aa shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    private fun withAlpha(color: Int, alpha: Float): Int = (color and 0x00FFFFFF) or ((alpha * 255).toInt().coerceIn(0, 255) shl 24)

    private fun easeInOutCubic(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

    private companion object {
        /** Words appear this much before they're sung. */
        const val LEAD_MS = 100L
        /** After a line, wait this long before clearing the poster when a long break follows. */
        const val LINGER_MS = 1_800L
        const val GAP_MS = 3_500L
        const val HELD_MS = 700L
        const val ARRIVE_MS = 620f

        /** Manrope's cap top and baseline as shares of the sprite (line) height: the visible band rows stack by. */
        const val CAP_TOP = 0.253f
        const val BASE = 0.78f
        const val VH = BASE - CAP_TOP
        /** Middle of the capitals, the pivot for letter and word motion. */
        const val MID = 0.52f
        /** Word space and row gap as shares of the row's sprite height (the gap keeps descenders off the next row). */
        const val GAP = 0.17f
        const val LEADING = 0.16f
        const val MAX_SCALE = 1.1f
        const val BAR_TOP = 0.17f
        const val BAR_BOTTOM = 0.9f
        const val SLOT_FLOOR = 0.95f

        const val MAX_W = 0.76f
        const val MAX_H = 0.6f
        const val CENTER_Y = 0.52f
        /** How far the camera leans toward the word being sung. */
        const val FOLLOW = 0.07f
        const val GHOSTS = 3
        const val MAX_ACCENTS = 20
        const val PI_F = 3.1415927f
        const val WHITE = -0x1 // 0xFFFFFFFF
        const val BURST_KEY = 0x5053L shl 48

        const val L_JUSTIFIED = 0
        const val L_KEYWORD = 1
        const val L_SPINE = 2
        const val L_STAIR = 3
        const val L_ALTERNATE = 4
        const val L_SOLO = 5

        const val M_BAR = 0
        const val M_FILL = 1

        const val X_ZOOM = 0
        const val X_WHIP = 1
        const val X_COLLAPSE = 2
        const val X_FLIP = 3
        const val X_DROP = 4
        const val X_SPLIT = 5
        val EXIT_MS = floatArrayOf(560f, 480f, 640f, 560f, 760f, 520f)

        const val E_DROP = 0
        const val E_SLOT = 1
        const val E_FLIP = 2
        const val E_SLIDE = 3
        const val E_POP = 4
        const val E_STAMP = 5
        val ENTRY_MS = floatArrayOf(520f, 400f, 440f, 380f, 420f, 380f)

        val POOL_JUSTIFIED = intArrayOf(E_SLOT, E_FLIP, E_SLIDE, E_DROP)
        val POOL_SMALL = intArrayOf(E_SLIDE, E_SLOT, E_POP)
        val POOL_SPINE = intArrayOf(E_FLIP, E_DROP, E_SLIDE, E_POP)
        val POOL_STAIR = intArrayOf(E_SLIDE, E_DROP, E_POP, E_SLOT)
        val POOL_ALTERNATE = intArrayOf(E_FLIP, E_SLOT, E_POP, E_SLIDE)

        const val TONE_LIGHT = 0
        const val TONE_ACCENT = 1
        const val TONE_SOFT = 2

        const val A_RULE_H = 0
        const val A_RULE_V = 1
        const val A_SQUARE = 2
        const val A_PROGRESS = 3
        const val A_CROSS = 4
    }
}
