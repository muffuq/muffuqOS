package com.muffuq.os.stage

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Verci-style word stack, choreographed: at most three words, the one being sung at the bottom in a
 * highlighter box, the stack rolling up as words come. On top of that, every line gets its own camera, layout
 * and way out, and every word its own way in — mostly letter by letter (cascades, drops, spins), with held
 * notes and line endings slamming in with sparks. Line exits break the words apart letter by letter.
 *
 * [variant] is the geometry: Ship (a turning drum), Fisheye (letters swell under a lens), Visual (pictures).
 */
class StackScene(private val variant: StageStyle) : Scene {
    private val roll = Spring(13f, 3f)
    private val camRotX = Spring(4f)
    private val camRotY = Spring(4f)
    private val camRotZ = Spring(4f)
    private val camZoom = Spring(4f)
    private val camShift = Spring(4f)
    private var line = Int.MIN_VALUE
    private var leaving: Leaving? = null
    private val move = Move()
    private val letter = Move()
    private val exitScratch = Move()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** The stack of the line that just ended, breaking apart while the next line's first word arrives. */
    private class Leaving(val line: Int, val roll: Float, val newest: Int, val at: Long)

    override fun draw(c: Canvas, s: SceneContext) {
        val pos = s.positionMs
        val li = s.lyrics.lineAt(pos + APPEAR_LEAD_MS)
        if (li != line) {
            if (line in s.lyrics.lines.indices && !roll.value.isNaN()) {
                leaving = Leaving(line, roll.value, newestWord(s.lyrics.lines[line], pos).coerceAtLeast(0), s.clockMs)
            }
            line = li
            roll.reset(if (li >= 0) rollTarget(s.lyrics.lines[li], pos) else 0f)
        }
        aimCamera(s, li)

        leaving?.let { l ->
            val t = (s.clockMs - l.at) / LEAVE_MS
            if (t >= 1f || l.line !in s.lyrics.lines.indices) leaving = null
            else drawStack(c, s, l.line, l.roll, l.newest, exitT = t, gapFade = 1f)
        }

        val cy = s.height / 2f
        if (li < 0) {
            if (s.lyrics.lines.isNotEmpty() && s.lyrics.lines[0].startMs - pos > 1_200) waitingDots(c, s, cy)
            return
        }
        val current = s.lyrics.lines[li]
        val newest = newestWord(current, pos)
        if (newest < 0) return
        val r = roll.update(rollTarget(current, pos), s.clockMs)

        // A long instrumental break after this line: let the words go, show the dots.
        val next = s.lyrics.lines.getOrNull(li + 1)?.startMs ?: Long.MAX_VALUE
        val gap = if (next - current.endMs > 3_500) ((pos - current.endMs - LINGER_MS) / LEAVE_MS).coerceIn(0f, 1f) else 0f
        if (gap < 1f) drawStack(c, s, li, r, newest, exitT = gap, gapFade = 1f)
        if (gap > 0f && next - pos > 1_200) waitingDots(c, s, cy)
    }

    /** Each line has its own pose; the camera swings over on springs, pushes in slowly, and kicks on beats. */
    private fun aimCamera(s: SceneContext, li: Int) {
        val pose = choreo(li.coerceAtLeast(0)).pose
        val tilt = if (variant.drum) 1.3f else 1f
        val progress = s.line(li)?.let { ((s.positionMs - it.startMs).toFloat() / (it.endMs - it.startMs).coerceAtLeast(1)).coerceIn(0f, 1f) } ?: 0f
        val t = s.clockMs / 1000f
        s.camera.rotX = camRotX.update(pose.rotX * tilt, s.clockMs) + 1.5f * sin(t / 5f)
        s.camera.rotY = camRotY.update(pose.rotY * tilt, s.clockMs) + 2f * sin(t / 7f)
        s.camera.rotZ = camRotZ.update(pose.rotZ, s.clockMs) + s.beat * 0.8f * sin(t * 40f)
        s.camera.zoom = camZoom.update(pose.zoom + 0.05f * progress, s.clockMs) + 0.02f * s.beat
        s.camera.shiftX = camShift.update(pose.shift * s.width, s.clockMs) + s.beat * 3f * s.density * sin(t * 53f)
        s.camera.shiftY = s.beat * 3f * s.density * cos(t * 47f)
    }

    private fun slotScale(d: Float): Float = if (variant.lens) {
        if (d >= 0) 1f / (1f + 0.95f * d.pow(1.2f)) else 1f / (1f + 1.15f * (-d).pow(1.1f))
    } else {
        if (d >= 0) 1f / (1f + 0.68f * d.pow(1.15f)) else 1f / (1f + 0.9f * (-d).pow(1.1f))
    }

    private class Placed(val index: Int, val sprite: WordSprite, val d: Float, val scale: Float, val squash: Float) {
        var y = 0f
        val height get() = sprite.height * scale * squash
    }

    private val placed = ArrayList<Placed>(6)

    private fun drawStack(c: Canvas, s: SceneContext, lineIndex: Int, r: Float, newest: Int, exitT: Float, gapFade: Float) {
        val line = s.lyrics.lines[lineIndex]
        val plan = choreo(lineIndex)
        val pos = s.positionMs
        placed.clear()
        for (j in (newest - 3).coerceAtLeast(0)..newest) {
            val sprite = s.sprite(lineIndex, j) ?: continue
            var d = j - r
            if (j == newest) d += (1f - easeOutCubic(((pos - appearAt(line.words[j])) / 320f).coerceIn(0f, 1f))) * 0.8f
            if (d < -1.9f) continue
            // The drum: words foreshorten as they turn away from the viewer.
            val squash = if (variant.drum) cos((d * 0.5f).coerceIn(-1.3f, 1.3f)) else 1f
            placed += Placed(j, sprite, d, slotScale(d), squash)
        }
        if (placed.isEmpty()) return

        // Stack by real sizes around the middle slot; the anchor is the word at or just above the middle,
        // offset by how far it has rolled, so positions stay continuous as the anchor changes.
        fun step(a: Placed, b: Placed) = a.height / 2f + b.height / 2f - minOf(a.height, b.height) * 0.1f
        val cy = s.height / 2f
        val anchor = placed.indexOfLast { it.d <= 0f }.coerceAtLeast(0)
        val toNext = if (anchor + 1 < placed.size) step(placed[anchor], placed[anchor + 1]) else placed[anchor].height
        placed[anchor].y = cy + placed[anchor].d * toNext
        for (i in anchor + 1 until placed.size) placed[i].y = placed[i - 1].y + step(placed[i - 1], placed[i])
        for (i in anchor - 1 downTo 0) placed[i].y = placed[i + 1].y - step(placed[i], placed[i + 1])

        for (p in placed) {
            val w = line.words[p.index]
            val sprite = p.sprite
            val since = (pos - appearAt(w)).toFloat()
            val alpha = gapFade * when {
                p.d < -1f -> (1f - (-p.d - 1f) / 0.8f).coerceIn(0f, 1f)
                else -> 1f
            } * (if (variant.drum) 0.55f + 0.45f * p.squash else 1f)
            if (alpha <= 0.01f) continue

            val nextAppear = line.words.getOrNull(p.index + 1)?.let { appearAt(it) } ?: Long.MAX_VALUE
            val boxed = if (pos < nextAppear) 1f else 1f - ((pos - nextAppear) / 280f).coerceIn(0f, 1f)
            val sung = pos in w.startMs..w.endMs
            val pulse = if (sung) 1f + 0.05f * s.beat else 1f

            val tw = sprite.width.toFloat()
            val th = sprite.height.toFloat()
            val icon = sprite.icon?.takeIf { variant.icons }
            val iconW = if (icon != null) icon.width + th * 0.12f else 0f
            val pivotX = s.width / 2f + layoutOffset(plan.layout, lineIndex, p.index) * s.width
            val groupLeft = pivotX - (tw + iconW) / 2f
            val textLeft = groupLeft + if (sprite.iconLeft) iconW else 0f
            val side = if (rand(lineIndex, p.index, 4) < 0.5f) -1f else 1f
            val entrance = entranceFor(line, lineIndex, p.index, plan.layout)

            // Word-level part of the entrance (slides, slams, stretches); letter cascades happen per glyph below.
            entranceMove(move, entrance, since, th, s.width, side)
            if (entrance.perLetter) move.reset()
            if (entrance == Entrance.Slam && since >= entrance.durationMs && s.effects.landOnce(wordKey(lineIndex, p.index))) {
                s.effects.burst(pivotX, p.y, intArrayOf(s.ink.primary, s.ink.secondary, s.ink.light), s.density, lineIndex * 101 + p.index)
            }

            c.save()
            c.translate(move.dx, p.y + move.dy)
            c.rotate(restingTilt(plan.layout, lineIndex, p.index) + move.rot, pivotX, 0f)
            val sc = p.scale * pulse
            c.scale(sc * move.sx, sc * move.sy * p.squash, pivotX, 0f)
            c.translate(0f, -th / 2f)
            val a = alpha * move.alpha

            // Under the lens the word grows wider than its texture; the box follows it.
            val bulge = if (variant.lens) LENS * slotScale(p.d) else 0f
            val shown = if (bulge > 0f) lensLayout(sprite, bulge) else tw
            if (boxed > 0.01f && exitT < 0.5f) {
                drawBox(c, s, textLeft - (shown - tw) / 2f, shown, th, a * boxed * (1f - exitT * 2f), since, lineIndex, p.index)
            }

            val color = mix(s.ink.light, s.ink.secondaryLight, 1f - boxed)
            // Speed ghosts for slides/slams: fading copies back along the path.
            for (g in 1..move.ghosts) {
                text.color = s.ink.primary
                text.alpha = (255 * a * 0.18f / g).toInt()
                c.drawBitmap(sprite.text, textLeft - move.dx * 0.12f * g, -move.dy * 0.12f * g, text)
            }
            drawLetters(c, s, sprite, w, textLeft, th, color, a, entrance, since, exitT, plan.exit, lineIndex, p.index, bulge)

            if (icon != null) {
                val pop = if (since < 0) 0f else easeOutBack((since / 380f).coerceIn(0f, 1f))
                val ix = if (sprite.iconLeft) groupLeft else textLeft + tw + th * 0.12f
                val iy = (th - icon.height) / 2f
                s.ink.icon.alpha = (255 * a * (1f - exitT)).toInt()
                c.save()
                c.rotate(sin(s.clockMs / 600f + p.index) * 6f, ix + icon.width / 2f, iy + icon.height / 2f)
                c.scale(pop, pop, ix + icon.width / 2f, iy + icon.height / 2f)
                c.drawBitmap(icon, ix, iy, s.ink.icon)
                c.restore()
            }
            c.restore()
        }
    }

    /**
     * The word letter by letter: each glyph gets its share of the entrance (staggered), the lens bulge
     * (Fisheye), and on the way out its own direction, spin and fall.
     */
    private fun drawLetters(
        c: Canvas, s: SceneContext, sprite: WordSprite, w: LyricWord, left: Float, th: Float, color: Int, alpha: Float,
        entrance: Entrance, since: Float, exitT: Float, exit: Exit, lineIndex: Int, wordIndex: Int, bulge: Float,
    ) {
        val n = sprite.glyphCount
        val stagger = (entrance.durationMs * 0.45f / n.coerceAtLeast(1)).coerceAtMost(45f)
        val lit = wordProgress(w, s.positionMs)
        if (bulge > 0f) lensLayout(sprite, bulge)
        for (i in 0 until n) {
            if (glyphIsBlank(sprite, i)) continue
            val gl = sprite.glyphLeft[i]
            val gw = sprite.glyphRight[i] - gl
            // Under the lens, letters sit where the swollen widths put them (see lensLayout), so they don't collide.
            val cx = left + if (bulge > 0f) lensCenters[i] else gl + gw / 2f
            letter.reset()
            if (entrance.perLetter) letterEntrance(letter, entrance, since - i * stagger, th, if ((i + wordIndex) % 2 == 0) 1f else -1f)
            if (exitT > 0f) {
                val e = exitScratch
                exitMove(e, exit, (exitT * 1.25f - i * 0.03f).coerceIn(0f, 1f), lineIndex, wordIndex * 37 + i, s.width, s.height)
                letter.dx += e.dx
                letter.dy += e.dy
                letter.rot += e.rot
                letter.sx *= e.sx
                letter.sy *= e.sy
                letter.alpha *= e.alpha
            }
            // Fisheye: letters near the middle of the word swell and the ends curve away, like a glass dome.
            val u = ((gl + gw / 2f) / sprite.width.toFloat()) * 2f - 1f
            val lens = 1f + bulge * (1f - u * u)
            val curve = bulge * u * u * th * 0.22f
            val a = alpha * letter.alpha
            if (a <= 0.01f) continue

            c.save()
            c.translate(cx + letter.dx, th / 2f + letter.dy + curve)
            if (letter.rot != 0f) c.rotate(letter.rot)
            c.scale(letter.sx * lens, letter.sy * lens)
            // Depth: a soft offset shadow under each letter.
            shadow.color = 0xFF000000.toInt()
            shadow.alpha = (255 * a * 0.32f).toInt()
            drawGlyph(c, sprite, i, -gw / 2f + th * 0.025f, -th / 2f + th * 0.045f, shadow)
            // Letters already reached by the voice are lit in the album colour for a moment.
            val reached = (i + 0.5f) / n <= lit && s.positionMs <= w.endMs + 250
            text.color = if (reached) mix(color, s.ink.primaryLight, 0.6f) else color
            text.alpha = (255 * a).toInt()
            drawGlyph(c, sprite, i, -gw / 2f, -th / 2f, text)
            c.restore()
        }
    }

    private var lensCenters = FloatArray(32)

    /**
     * Letter centres for a word under the lens: each letter's advance is widened by its own swell, and the
     * word is re-centred on its texture. Returns the swollen word's width.
     */
    private fun lensLayout(sprite: WordSprite, bulge: Float): Float {
        val n = sprite.glyphCount
        if (lensCenters.size < n) lensCenters = FloatArray(n * 2)
        var x = 0f
        for (i in 0 until n) {
            val left = sprite.glyphLeft[i]
            val advance = (if (i + 1 < n) sprite.glyphLeft[i + 1] else sprite.width.toFloat()) - left
            val u = ((left + (sprite.glyphRight[i] - left) / 2f) / sprite.width) * 2f - 1f
            val swollen = advance * (1f + bulge * (1f - u * u))
            lensCenters[i] = x + (sprite.glyphRight[i] - left) / 2f * (1f + bulge * (1f - u * u))
            x += swollen
        }
        val shift = (sprite.width - x) / 2f
        for (i in 0 until n) lensCenters[i] += shift
        return x
    }

    /** A marker box: a slightly skewed parallelogram drawn in from the left, deeper at the bottom. */
    private fun drawBox(c: Canvas, s: SceneContext, textLeft: Float, tw: Float, th: Float, alpha: Float, since: Float, lineIndex: Int, wordIndex: Int) {
        if (alpha <= 0.01f) return
        val drawIn = easeOutCubic((since / 220f).coerceIn(0f, 1f))
        val padX = th * 0.12f
        val top = th * 0.14f
        val bottom = th * 0.93f
        val skew = (rand(lineIndex, wordIndex, 17) - 0.5f) * 0.3f
        val ink = s.ink
        ink.boxMatrix.setScale(1f, bottom - top)
        ink.boxMatrix.postTranslate(0f, top)
        ink.boxShader.setLocalMatrix(ink.boxMatrix)
        ink.boxPaint.alpha = (255 * alpha * 0.92f).toInt()
        c.save()
        c.skew(skew, 0f)
        c.translate(-skew * (top + bottom) / 2f, 0f)
        c.drawRect(textLeft - padX, top, textLeft - padX + (tw + 2 * padX) * drawIn, bottom, ink.boxPaint)
        c.restore()
    }

    private fun waitingDots(c: Canvas, s: SceneContext, cy: Float) {
        val r = 7f * s.density
        text.color = s.ink.primary
        for (k in 0..2) {
            val phase = sin(s.clockMs / 1000f * 3.2f - k * 0.8f)
            text.alpha = (255 * (0.55f + 0.3f * phase)).toInt()
            c.drawCircle(s.width / 2f + (k - 1) * r * 4.2f, cy, r * (1f + 0.25f * phase + 0.35f * s.beat), text)
        }
    }

    private fun wordKey(line: Int, word: Int) = (line.toLong() shl 20) or word.toLong()

    private companion object {
        const val APPEAR_LEAD_MS = 110L
        /** How much the lens swells the middle word's centre letters. */
        const val LENS = 0.42f
        const val LEAVE_MS = 420f
        const val LINGER_MS = 1_800L

        fun appearAt(w: LyricWord) = w.startMs - APPEAR_LEAD_MS
        fun newestWord(line: LyricLine, positionMs: Long) = line.words.indexOfLast { appearAt(it) <= positionMs }
        fun rollTarget(line: LyricLine, positionMs: Long) = (newestWord(line, positionMs) - 1).coerceAtLeast(0).toFloat()

        fun mix(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            fun ch(shift: Int) = ((a shr shift and 0xFF) * (1 - k) + (b shr shift and 0xFF) * k).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}

/** Entrances that are played letter by letter (the rest move the word as one). */
internal val Entrance.perLetter get() = this == Entrance.Rise || this == Entrance.Drop || this == Entrance.Spin || this == Entrance.Wipe

/** One letter's share of a per-letter entrance at [sinceMs] into its own (staggered) start. */
internal fun letterEntrance(m: Move, e: Entrance, sinceMs: Float, th: Float, side: Float) {
    val t = (sinceMs / (e.durationMs * 0.75f)).coerceIn(0f, 1f)
    if (sinceMs < 0) {
        m.alpha = 0f
        return
    }
    if (t >= 1f) return
    when (e) {
        Entrance.Rise -> {
            m.dy = (1f - easeOutCubic(t)) * th * 0.6f
            m.alpha = (t * 3f).coerceAtMost(1f)
        }
        Entrance.Drop -> {
            m.dy = -(1f - easeOutBounce(t)) * th * 1.3f
            m.rot = (1f - t) * 25f * side
            m.alpha = (t * 4f).coerceAtMost(1f)
        }
        Entrance.Spin -> {
            val k = easeOutBack(t, 1.8f)
            m.rot = (1f - k) * 220f * side
            m.sx = 0.2f + 0.8f * k
            m.sy = m.sx
            m.alpha = (t * 3f).coerceAtMost(1f)
        }
        Entrance.Wipe -> {
            // Flip up from the baseline, one letter after another.
            val k = easeOutBack(t, 2.6f)
            m.sy = k.coerceAtLeast(0.02f)
            m.dy = (1f - k) * th * 0.3f
            m.alpha = (t * 5f).coerceAtMost(1f)
        }
        else -> Unit
    }
}
