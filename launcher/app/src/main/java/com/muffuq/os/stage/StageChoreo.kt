package com.muffuq.os.stage

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/*
 * The choreographer: so the stage never settles into one repeated move, every line gets its own camera,
 * layout and way out, and every word its own way in. All picked deterministically from the line and word
 * index (the same song always plays the same way), with neighbours guaranteed to differ.
 */

internal enum class Layout { Center, StairRight, StairLeft, Zigzag, Scatter }

internal enum class Entrance(val durationMs: Float) {
    Rise(320f), Drop(520f), SlideLeft(380f), SlideRight(380f), Spin(460f), Stretch(560f), Wipe(300f),
    /** For held notes and line endings: comes in huge and hits the screen. */
    Slam(330f),
}

internal enum class Exit { Depth, Explode, Fall, Swipe }

/** Camera for a line: tilt in three axes, zoom, sideways framing. */
internal class Pose(val rotX: Float, val rotY: Float, val rotZ: Float, val zoom: Float, val shift: Float)

private val poses = listOf(
    Pose(6f, -14f, 0f, 1.0f, 0f),
    Pose(11f, 17f, 2.5f, 1.06f, -0.04f),
    Pose(-5f, -21f, -4f, 0.97f, 0.05f),
    Pose(15f, 2f, 6f, 1.1f, 0f),
    Pose(3f, 23f, -3f, 1.02f, 0.03f),
    Pose(-9f, -7f, 3.5f, 1.12f, -0.03f),
    Pose(8f, -26f, 8f, 0.95f, 0.06f),
)

internal class LineChoreo(val pose: Pose, val layout: Layout, val exit: Exit, val seed: Int)

/** Deterministic 0..1 from a few integers. */
internal fun rand(vararg keys: Int): Float {
    var h = 0x2545F491
    for (k in keys) {
        h = h xor k
        h *= 0x5bd1e995
        h = h xor (h ushr 15)
    }
    return (h and 0x7fffffff) % 100_000 / 100_000f
}

internal fun choreo(line: Int): LineChoreo {
    // Each line steps to a different pose, layout and exit than the one before.
    fun pick(count: Int, salt: Int): Int {
        if (line <= 0) return (rand(salt) * count).toInt()
        val prev = pickFor(line - 1, count, salt)
        return (prev + 1 + (rand(line, salt) * (count - 1)).toInt()) % count
    }
    return LineChoreo(
        pose = poses[pick(poses.size, 1)],
        layout = Layout.entries[pick(Layout.entries.size, 2)],
        exit = Exit.entries[pick(Exit.entries.size, 3)],
        seed = line,
    )
}

/** Same walk as [choreo], kept separate so the recursion stays cheap: lines only look one back. */
private fun pickFor(line: Int, count: Int, salt: Int): Int = (rand(line, salt, 7) * count).toInt().let { base ->
    if (line <= 0) (rand(salt) * count).toInt() else base
}

internal fun entranceFor(line: LyricLine, lineIndex: Int, wordIndex: Int, layout: Layout): Entrance {
    val w = line.words[wordIndex]
    val held = w.endMs - w.startMs >= 650
    val last = wordIndex == line.words.lastIndex && line.words.size > 2
    if (held || last) return Entrance.Slam
    if (wordIndex == 0) return if (rand(lineIndex, 11) < 0.5f) Entrance.Drop else Entrance.Stretch
    val pool = when (layout) {
        Layout.StairRight -> arrayOf(Entrance.SlideRight, Entrance.Rise, Entrance.Wipe, Entrance.Spin)
        Layout.StairLeft -> arrayOf(Entrance.SlideLeft, Entrance.Rise, Entrance.Wipe, Entrance.Stretch)
        Layout.Zigzag -> arrayOf(Entrance.SlideLeft, Entrance.SlideRight, Entrance.Drop, Entrance.Spin)
        Layout.Scatter -> arrayOf(Entrance.Spin, Entrance.Drop, Entrance.Stretch, Entrance.Rise)
        Layout.Center -> arrayOf(Entrance.Rise, Entrance.Wipe, Entrance.Drop, Entrance.Stretch)
    }
    return pool[(rand(lineIndex, wordIndex, 5) * pool.size).toInt()]
}

/** Resting sideways offset of a word, as a share of the screen width, by the line's layout. */
internal fun layoutOffset(layout: Layout, lineIndex: Int, wordIndex: Int): Float = when (layout) {
    Layout.Center -> (rand(lineIndex, wordIndex, 3) - 0.5f) * 0.04f
    Layout.StairRight -> ((wordIndex % 4) - 1.5f) * 0.065f
    Layout.StairLeft -> -((wordIndex % 4) - 1.5f) * 0.065f
    Layout.Zigzag -> if (wordIndex % 2 == 0) -0.075f else 0.075f
    Layout.Scatter -> (rand(lineIndex, wordIndex, 9) - 0.5f) * 0.24f
}

/** Resting tilt of a word in degrees: a stack laid by hand. */
internal fun restingTilt(layout: Layout, lineIndex: Int, wordIndex: Int): Float {
    val spread = when (layout) {
        Layout.Scatter, Layout.Zigzag -> 11f
        else -> 6f
    }
    return (rand(lineIndex, wordIndex, 13) - 0.5f) * spread
}

/** How a word moves at a moment of its entrance. */
internal class Move {
    var dx = 0f
    var dy = 0f
    var sx = 1f
    var sy = 1f
    var rot = 0f
    var alpha = 1f
    /** 0..1 of the word revealed from the left (Wipe). */
    var reveal = 1f
    /** Copies trailing behind along the path, for speed. */
    var ghosts = 0

    fun reset() {
        dx = 0f; dy = 0f; sx = 1f; sy = 1f; rot = 0f; alpha = 1f; reveal = 1f; ghosts = 0
    }
}

internal fun entranceMove(m: Move, e: Entrance, sinceMs: Float, th: Float, width: Float, side: Float) {
    m.reset()
    val t = (sinceMs / e.durationMs).coerceIn(0f, 1f)
    if (t >= 1f) return
    when (e) {
        Entrance.Rise -> {
            m.dy = (1f - easeOutCubic(t)) * th * 0.9f
            m.alpha = (t * 2.5f).coerceAtMost(1f)
        }
        Entrance.Drop -> {
            m.dy = -(1f - easeOutBounce(t)) * th * 1.6f
            m.rot = (1f - t) * 14f * side
            m.alpha = (t * 4f).coerceAtMost(1f)
        }
        Entrance.SlideLeft, Entrance.SlideRight -> {
            val dir = if (e == Entrance.SlideLeft) -1f else 1f
            m.dx = dir * (1f - easeOutExpo(t)) * width * 0.55f
            m.sx = 1f + (1f - t) * 0.35f
            m.alpha = (t * 3f).coerceAtMost(1f)
            m.ghosts = 3
        }
        Entrance.Spin -> {
            val k = easeOutBack(t, 1.6f)
            m.rot = (1f - k) * 160f * side
            m.sx = 0.3f + 0.7f * k
            m.sy = m.sx
            m.alpha = (t * 3f).coerceAtMost(1f)
        }
        Entrance.Stretch -> {
            val k = easeOutElastic(t)
            m.sx = 1f + (1f - k) * 1.5f
            m.sy = 1f - (1f - k) * 0.55f
            m.alpha = (t * 4f).coerceAtMost(1f)
        }
        Entrance.Wipe -> {
            m.reveal = easeOutCubic(t)
            m.dx = (1f - easeOutCubic(t)) * -th * 0.3f
        }
        Entrance.Slam -> {
            val k = easeOutExpo(t)
            m.sx = 1f + (1f - k) * 2.6f
            m.sy = m.sx
            m.rot = (1f - k) * -9f * side
            m.alpha = t.pow(0.4f)
            m.ghosts = 2
        }
    }
}

internal fun exitMove(m: Move, exit: Exit, t: Float, lineIndex: Int, wordIndex: Int, screenW: Float, screenH: Float) {
    m.reset()
    if (t <= 0f) return
    val side = if (rand(lineIndex, wordIndex, 21) < 0.5f) -1f else 1f
    when (exit) {
        Exit.Depth -> {
            val k = easeInCubic(t)
            m.sx = 1f - 0.65f * k
            m.sy = m.sx
            m.dy = -k * screenH * 0.08f
            m.alpha = 1f - t
        }
        Exit.Explode -> {
            val angle = rand(lineIndex, wordIndex, 22) * 2f * PI.toFloat()
            val k = easeOutCubic(t)
            m.dx = cos(angle) * k * screenW * 0.45f
            m.dy = sin(angle) * k * screenH * 0.45f
            m.rot = side * k * 140f
            m.sx = 1f + 0.4f * k
            m.sy = m.sx
            m.alpha = 1f - t * t
        }
        Exit.Fall -> {
            val delay = (wordIndex % 4) * 0.12f
            val k = ((t - delay) / (1f - delay)).coerceIn(0f, 1f)
            m.dy = k * k * screenH * 0.75f
            m.rot = side * k * 35f
            m.alpha = 1f - k
        }
        Exit.Swipe -> {
            val k = easeInCubic(t)
            m.dx = side * k * screenW * 0.9f
            m.sx = 1f + k * 0.5f
            m.alpha = 1f - t
            m.ghosts = 3
        }
    }
}

/** Sparks and rings: short-lived bits of light thrown off when a word slams in. */
class Effects {
    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val color: Int, val size: Float)
    private class Ring(val x: Float, val y: Float, var age: Float, val color: Int)
    private class Bloom(val x: Float, val y: Float, var age: Float, val color: Int)

    private val sparks = ArrayList<Spark>()
    private val rings = ArrayList<Ring>()
    private val blooms = ArrayList<Bloom>()
    private val bloomRect = android.graphics.RectF()
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Soft round light texture for the colour blooms; set by the stage. */
    var dot: android.graphics.Bitmap? = null

    /** How many bursts so far: the stage watches it to turn the palette at explosive moments. */
    var bursts = 0
        private set
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var lastMs = 0L
    /** Words that already slammed, so each bursts once. */
    private val landed = HashSet<Long>()

    fun landOnce(key: Long): Boolean = landed.add(key)

    fun burst(x: Float, y: Float, colors: IntArray, density: Float, seed: Int) {
        bursts++
        // The explosion's colour washes out over the screen from where it happened.
        blooms += Bloom(x, y, 0f, colors[0])
        rings += Ring(x, y, 0f, colors[0])
        repeat(22) { i ->
            val angle = rand(seed, i, 31) * 2f * PI.toFloat()
            val speed = (220f + 520f * rand(seed, i, 32)) * density
            val life = 0.55f + 0.6f * rand(seed, i, 33)
            sparks += Spark(x, y, cos(angle) * speed, sin(angle) * speed - 180f * density, life, life, colors[i % colors.size], (2f + 3.5f * rand(seed, i, 34)) * density)
        }
        if (landed.size > 400) landed.clear()
    }

    fun draw(c: Canvas, nowMs: Long, density: Float, screenW: Float) {
        val dt = if (lastMs == 0L) 0f else ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.05f)
        lastMs = nowMs
        val gravity = 900f * density
        dot?.let { d ->
            val b = blooms.iterator()
            while (b.hasNext()) {
                val bloom = b.next()
                bloom.age += dt
                val k = bloom.age / 1.1f
                if (k >= 1f) {
                    b.remove()
                    continue
                }
                val r = screenW * (0.18f + 0.62f * easeOutCubic(k))
                bloomRect.set(bloom.x - r, bloom.y - r, bloom.x + r, bloom.y + r)
                bloomPaint.color = bloom.color
                bloomPaint.alpha = (255 * 0.42f * (1f - k) * (1f - k)).toInt()
                c.drawBitmap(d, null, bloomRect, bloomPaint)
            }
        }
        val it = sparks.iterator()
        while (it.hasNext()) {
            val s = it.next()
            s.life -= dt
            if (s.life <= 0f) {
                it.remove()
                continue
            }
            s.vy += gravity * dt
            s.vx *= 1f - 1.5f * dt
            s.x += s.vx * dt
            s.y += s.vy * dt
            val k = s.life / s.max
            paint.color = s.color
            paint.alpha = (255 * k).toInt()
            c.drawCircle(s.x, s.y, s.size * (0.5f + 0.5f * k), paint)
        }
        val r = rings.iterator()
        while (r.hasNext()) {
            val ring = r.next()
            ring.age += dt
            val k = ring.age / 0.7f
            if (k >= 1f) {
                r.remove()
                continue
            }
            stroke.color = ring.color
            stroke.alpha = (150 * (1f - k)).toInt()
            stroke.strokeWidth = (7f * (1f - k) + 1f) * density
            c.drawCircle(ring.x, ring.y, easeOutCubic(k) * screenW * 0.32f, stroke)
        }
    }
}

/** Slow soft lights floating in the space behind the words; they brighten with the beat. */
internal class Bokeh(count: Int) {
    private val seeds = IntArray(count) { it }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = android.graphics.RectF()

    fun draw(c: Canvas, dot: android.graphics.Bitmap, w: Float, h: Float, tSec: Float, beat: Float, colors: IntArray, density: Float) {
        for (i in seeds) {
            val speed = 0.015f + 0.03f * rand(i, 41)
            val x = ((rand(i, 42) + tSec * speed * (if (i % 2 == 0) 1f else -1f)) % 1f + 1f) % 1f * w
            val y = (rand(i, 43) + 0.06f * sin(tSec * (0.2f + 0.3f * rand(i, 44)) + i)) * h
            val size = (40f + 110f * rand(i, 45)) * density
            paint.color = colors[i % colors.size]
            paint.alpha = (255 * (0.05f + 0.07f * rand(i, 46) + 0.06f * beat)).toInt()
            rect.set(x - size, y - size, x + size, y + size)
            c.drawBitmap(dot, null, rect, paint)
        }
    }
}

internal fun easeOutCubic(t: Float) = 1 - (1 - t).let { it * it * it }
internal fun easeInCubic(t: Float) = t * t * t
internal fun easeOutExpo(t: Float) = if (t >= 1f) 1f else 1f - 2f.pow(-10f * t)
internal fun easeOutBack(t: Float, c1: Float = 2.2f): Float {
    val x = t - 1
    return 1 + (c1 + 1) * x * x * x + c1 * x * x
}
internal fun easeOutElastic(t: Float): Float =
    if (t <= 0f || t >= 1f) t.coerceIn(0f, 1f) else 2f.pow(-10f * t) * sin((t * 10f - 0.75f) * (2f * PI.toFloat() / 3f)) + 1f
internal fun easeOutBounce(t: Float): Float {
    val n = 7.5625f
    val d = 2.75f
    return when {
        t < 1f / d -> n * t * t
        t < 2f / d -> (t - 1.5f / d).let { n * it * it + 0.75f }
        t < 2.5f / d -> (t - 2.25f / d).let { n * it * it + 0.9375f }
        else -> (t - 2.625f / d).let { n * it * it + 0.984375f }
    }
}

/** Damps [x] towards 0 for a little settle after big moves. */
internal fun settle(x: Float, t: Float) = x * exp(-6f * t)
