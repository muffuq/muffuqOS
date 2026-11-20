package com.muffuq.os.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.muffuq.os.ui.theme.Manrope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The lyrics are shown the way Verci does it: not lines, a stack of at most three words. The word being sung
 * sits at the bottom in a highlighter box; when the next one comes the stack rolls up — the previous word
 * becomes the big one in the middle, the one before shrinks away at the top. Each style is a different
 * geometry for the same stack.
 */
enum class StageStyle(val label: String, internal val drum: Boolean, internal val lens: Boolean, internal val icons: Boolean) {
    /** The stack on a turning drum, seen at an angle. */
    Ship("Ship", drum = true, lens = false, icons = false),
    /** The stack under a convex lens: the middle word swells and bends around the glass. */
    Fisheye("Fisheye", drum = false, lens = true, icons = false),
    /** Words it knows get a picture beside them, in the album's colour. */
    Visual("Visual", drum = false, lens = false, icons = true),
    FisheyeVisual("Fisheye Visual", drum = false, lens = true, icons = true),
}

/**
 * Word textures for the lines around the current one. Rendering happens off the main thread, a line ahead;
 * lines left behind are dropped (not recycled: a frame in flight may still draw them, the GC frees them).
 */
class SpriteCache(private val lyrics: Lyrics, private val fitWidth: Float, private val density: Density, private val fonts: FontFamily.Resolver) {
    private val sprites = HashMap<Long, WordSprite>()
    private val lines = HashSet<Int>()

    fun get(line: Int, index: Int): WordSprite? = sprites[key(line, index)]

    /** Main thread: keep [range] rendered, drop the rest. */
    suspend fun keep(range: IntRange) {
        val missing = range.filter { it in lyrics.lines.indices && it !in lines }
        if (missing.isNotEmpty()) {
            val made = withContext(Dispatchers.Default) {
                // A measurer of its own: measurers aren't thread-safe.
                val measurer = TextMeasurer(fonts, density, LayoutDirection.Ltr, 0)
                missing.associateWith { render(measurer, it) }
            }
            for ((line, words) in made) {
                words.forEachIndexed { i, s -> sprites[key(line, i)] = s }
                lines += line
            }
        }
        lines.filter { it !in range }.forEach { line ->
            lyrics.lines[line].words.indices.forEach { sprites.remove(key(line, it)) }
            lines -= line
        }
    }

    private fun render(measurer: TextMeasurer, line: Int): List<WordSprite> = lyrics.lines[line].words.mapIndexed { i, w ->
        val probe = measurer.measure(AnnotatedString(w.text), style(BASE_SIZE), softWrap = false, maxLines = 1)
        // Short words get big, long ones small, so every word fills about the same column.
        val fit = (fitWidth / probe.size.width.coerceAtLeast(1)).coerceIn(MIN_FIT, MAX_FIT)
        val layout = measurer.measure(AnnotatedString(w.text), style(BASE_SIZE * fit), softWrap = false, maxLines = 1)
        val text = rasterize(layout, Bitmap.Config.ALPHA_8)
        val icon = EmojiMap.lookup(w.text)?.let { emoji ->
            rasterize(measurer.measure(AnnotatedString(emoji), TextStyle(fontSize = BASE_SIZE * fit * 0.62f), softWrap = false, maxLines = 1), Bitmap.Config.ARGB_8888)
        }
        // Each character's extent, for scenes that animate letter by letter.
        val left = FloatArray(w.text.length) { layout.getBoundingBox(it).left }
        val right = FloatArray(w.text.length) { layout.getBoundingBox(it).right }
        WordSprite(text, icon, iconLeft = (line * 7 + i * 3) % 3 == 0, chars = w.text, glyphLeft = left, glyphRight = right)
    }

    private fun rasterize(layout: TextLayoutResult, config: Bitmap.Config): Bitmap {
        val bitmap = Bitmap.createBitmap(layout.size.width.coerceAtLeast(1), layout.size.height.coerceAtLeast(1), config)
        layout.multiParagraph.paint(androidx.compose.ui.graphics.Canvas(bitmap.asImageBitmap()), Color.White)
        return bitmap
    }

    private fun key(line: Int, index: Int) = (line.toLong() shl 32) or index.toLong()

    private companion object {
        val BASE_SIZE = 56.sp
        const val MIN_FIT = 0.5f
        const val MAX_FIT = 2.0f

        fun style(size: TextUnit) = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.ExtraBold, fontSize = size, letterSpacing = (-0.02).em)
    }
}

/** Paints made once per palette; frames only change colour and alpha on them. */
class StageInk(palette: StagePalette, variant: Int = 0) {
    /**
     * Which colour leads. The stage turns through the variants at the song's explosive moments: the album's
     * vivid colour, then its second colour, then the vivid one turned a third of the way round the colour wheel.
     */
    private val lead: Color = when (variant % VARIANTS) {
        1 -> palette.secondary
        2 -> rotateHue(palette.primary, 120f)
        else -> palette.primary
    }
    private val second: Color = if (variant % VARIANTS == 1) palette.primary else palette.secondary

    /** The leading vivid colour and its partner, as ARGB ints for android.graphics paints. */
    val primary: Int = lead.toArgb()
    val secondary: Int = second.toArgb()
    /** Near white with a hint of the primary: sung text that should read clearly. */
    val light: Int = lerp(Color.White, lead, 0.12f).toArgb()
    /** The two colours lifted towards white, for text that has to read on the dark background. */
    val primaryLight: Int = lerp(lead, Color.White, 0.25f).toArgb()
    val secondaryLight: Int = lerp(second, Color.White, 0.35f).toArgb()
    val box: Color = lead
    /** Text inside the box. */
    val onBox: Color = Color.White
    /** Words that have left the box. */
    val stack: Color = lerp(second, Color.White, 0.35f)
    val dots: Color = lead
    val glowPrimary: Color = lead
    val glowSecondary: Color = second

    companion object {
        const val VARIANTS = 3

        private fun rotateHue(c: Color, degrees: Float): Color {
            val hsv = FloatArray(3)
            android.graphics.Color.colorToHSV(c.toArgb(), hsv)
            hsv[0] = (hsv[0] + degrees) % 360f
            return Color(android.graphics.Color.HSVToColor(hsv))
        }
    }

    internal val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** Pictures as flat silhouettes in the album colour, like Verci's symbols. */
    internal val icon = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(lead.toArgb(), PorterDuff.Mode.SRC_IN)
    }
    /** A unit-square gradient, stretched onto each box: lit at the top, deeper at the bottom like a marker. */
    internal val boxShader = LinearGradient(0f, 0f, 0f, 1f, lead.toArgb(), lerp(lead, Color.Black, 0.3f).toArgb(), Shader.TileMode.CLAMP)
    internal val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = boxShader }
    internal val boxMatrix = Matrix()
}

/** One frame: song position, wall clock (ambient motion keeps going when paused), beat 0..1. */
class StageFrame(val positionMs: Long, val clockMs: Long, val beat: Float)

/**
 * A critically damped spring: speeds up and settles like something with weight instead of sliding on a timer.
 * [snap]: further than this from the target, jump instead of animating.
 */
class Spring(private val omega: Float, private val snap: Float = Float.MAX_VALUE) {
    var value = Float.NaN
        private set
    private var v = 0f
    private var lastMs = 0L

    fun reset(to: Float) {
        value = to
        v = 0f
    }

    fun update(target: Float, nowMs: Long): Float {
        if (value.isNaN() || abs(target - value) > snap) {
            reset(target)
        } else {
            val dt = ((nowMs - lastMs) / 1000f).coerceIn(0f, 0.05f)
            val a = omega * omega * (target - value) - 2f * omega * v
            v += a * dt
            value += v * dt
        }
        lastMs = nowMs
        return value
    }
}

/** Where the stack is, carried from frame to frame. */
class StageMotion {
    internal val roll = Spring(13f, 3f)
    internal var line = Int.MIN_VALUE
    internal var leaving: Leaving? = null

    /** The stack of the line that just ended, clearing out while the next line's first word arrives. */
    internal class Leaving(val line: Int, val roll: Float, val newest: Int, val at: Long)
}

/** Words appear this much before they're sung, so the entrance is done as the voice gets there. */
private const val APPEAR_LEAD_MS = 110L
private const val LEAVE_MS = 260f
/** After a line, wait this long before clearing the stack when a long instrumental gap follows. */
private const val LINGER_MS = 1_800L


private fun appearAt(w: LyricWord) = w.startMs - APPEAR_LEAD_MS

/** Index of the newest word of [line] that has appeared, -1 if none yet. */
private fun newestWord(line: LyricLine, positionMs: Long): Int = line.words.indexOfLast { appearAt(it) <= positionMs }

/** The stack rolls so the newest word sits one slot below the middle (in the middle when it's the first). */
private fun rollTarget(line: LyricLine, positionMs: Long) = (newestWord(line, positionMs) - 1).coerceAtLeast(0).toFloat()

fun DrawScope.drawStage(lyrics: Lyrics, sprites: SpriteCache, style: StageStyle, motion: StageMotion, f: StageFrame, ink: StageInk) {
    val pos = f.positionMs
    val li = lyrics.lineAt(pos + APPEAR_LEAD_MS)
    val center = Offset(size.width / 2f, size.height * 0.5f)

    if (li != motion.line) {
        val old = motion.line
        if (old in lyrics.lines.indices && !motion.roll.value.isNaN()) {
            motion.leaving = StageMotion.Leaving(old, motion.roll.value, newestWord(lyrics.lines[old], pos).coerceAtLeast(0), f.clockMs)
        }
        motion.line = li
        motion.roll.reset(if (li >= 0) rollTarget(lyrics.lines[li], pos) else 0f)
    }

    // The previous line clears out quickly as the new one starts.
    motion.leaving?.let { leaving ->
        val t = (f.clockMs - leaving.at) / LEAVE_MS
        if (t >= 1f) {
            motion.leaving = null
        } else {
            drawStack(lyrics, sprites, style, leaving.line, leaving.roll, leaving.newest, f, ink, center, fade = 1f - easeOutCubic(t), lift = easeOutCubic(t))
        }
    }

    if (li < 0) {
        if (lyrics.lines.isNotEmpty() && lyrics.lines[0].startMs - pos > 1_200) waitingDots(center, f, ink)
        return
    }
    val line = lyrics.lines[li]
    val newest = newestWord(line, pos)
    if (newest < 0) return
    val roll = motion.roll.update(rollTarget(line, pos), f.clockMs)

    // A long instrumental break after this line: let the words go, show the dots.
    val next = lyrics.lines.getOrNull(li + 1)?.startMs ?: Long.MAX_VALUE
    val gapFade = if (next - line.endMs > 3_500) 1f - ((pos - line.endMs - LINGER_MS) / 500f).coerceIn(0f, 1f) else 1f
    if (gapFade > 0f) drawStack(lyrics, sprites, style, li, roll, newest, f, ink, center, fade = gapFade, lift = 1f - gapFade)
    if (gapFade < 1f && next - pos > 1_200) waitingDots(center, f, ink)
}

/** Size of a word at stack slot [d] (0 = middle, 1 = bottom, -1 = top); the lens makes the middle stand out more. */
private fun slotScale(style: StageStyle, d: Float): Float = if (style.lens) {
    if (d >= 0) 1f / (1f + 0.95f * d.pow(1.2f)) else 1f / (1f + 1.15f * (-d).pow(1.1f))
} else {
    if (d >= 0) 1f / (1f + 0.68f * d.pow(1.15f)) else 1f / (1f + 0.9f * (-d).pow(1.1f))
}

private class Placed(val index: Int, val sprite: WordSprite, val d: Float, val scale: Float, val squash: Float) {
    var y = 0f
    val height get() = sprite.text.height * scale * squash
}

private fun DrawScope.drawStack(
    lyrics: Lyrics,
    sprites: SpriteCache,
    style: StageStyle,
    lineIndex: Int,
    roll: Float,
    newest: Int,
    f: StageFrame,
    ink: StageInk,
    center: Offset,
    fade: Float,
    lift: Float,
) {
    val line = lyrics.lines[lineIndex]
    val pos = f.positionMs
    val placed = ArrayList<Placed>(5)
    for (j in (newest - 3).coerceAtLeast(0)..newest) {
        val sprite = sprites.get(lineIndex, j) ?: continue
        var d = j - roll
        if (j == newest) {
            // Entering: slides up into its slot from behind the word above.
            val a = easeOutCubic(((pos - appearAt(line.words[j])) / 300f).coerceIn(0f, 1f))
            d += (1f - a) * 0.85f
        }
        if (d < -2.1f) continue
        // The drum: words foreshorten as they turn away from the viewer.
        val squash = if (style.drum) cos((d * 0.5f).coerceIn(-1.3f, 1.3f)) else 1f
        placed += Placed(j, sprite, d, slotScale(style, d), squash)
    }
    if (placed.isEmpty()) return

    // Stack the words by their real sizes, so nothing overlaps however long or short a word is. The anchor is the
    // word at or just above the middle, offset by how far it has rolled: continuous as the anchor changes.
    fun step(a: Placed, b: Placed) = a.height / 2f + b.height / 2f - minOf(a.height, b.height) * 0.1f
    val anchor = placed.indexOfLast { it.d <= 0f }.coerceAtLeast(0)
    val toNext = if (anchor + 1 < placed.size) step(placed[anchor], placed[anchor + 1]) else placed[anchor].height
    placed[anchor].y = center.y + placed[anchor].d * toNext
    for (i in anchor + 1 until placed.size) placed[i].y = placed[i - 1].y + step(placed[i - 1], placed[i])
    for (i in anchor - 1 downTo 0) placed[i].y = placed[i + 1].y - step(placed[i], placed[i + 1])
    val raise = lift * 60f * density

    drawIntoCanvas { composeCanvas ->
        val c = composeCanvas.nativeCanvas
        for (p in placed) {
            val w = line.words[p.index]
            val sprite = p.sprite
            val alpha = fade * when {
                p.d < -1f -> (1f - (-p.d - 1f) / 1.1f).coerceIn(0f, 1f)
                p.index == newest -> ((pos - appearAt(w)) / 140f).coerceIn(0f, 1f)
                else -> 1f
            } * (if (style.drum) 0.55f + 0.45f * p.squash else 1f)
            if (alpha <= 0.01f) continue

            // The box: full while the word is the newest, fading once the next word has come.
            val nextAppear = line.words.getOrNull(p.index + 1)?.let { appearAt(it) } ?: Long.MAX_VALUE
            val boxed = if (pos < nextAppear) 1f else 1f - ((pos - nextAppear) / 280f).coerceIn(0f, 1f)
            val drawIn = easeOutCubic(((pos - appearAt(w)) / 200f).coerceIn(0f, 1f))
            val sung = pos in w.startMs..w.endMs
            val scale = p.scale * (if (sung) 1f + 0.05f * f.beat else 1f)

            val tw = sprite.text.width.toFloat()
            val th = sprite.text.height.toFloat()
            val icon = sprite.icon?.takeIf { style.icons }
            val iconW = if (icon != null) icon.width + th * 0.12f else 0f
            // A small, fixed sideways offset per word: a stack laid by hand, not a centred column.
            val jitter = (((lineIndex * 31 + p.index * 17) % 5) - 2) * size.width * 0.022f
            val pivotX = center.x + jitter
            val groupLeft = pivotX - (tw + iconW) / 2f
            val textLeft = groupLeft + if (sprite.iconLeft) iconW else 0f

            c.save()
            c.translate(0f, p.y - raise)
            c.scale(scale, scale * p.squash, pivotX, 0f)
            c.translate(0f, -th / 2f)

            if (boxed > 0.01f) {
                val padX = th * 0.1f
                val top = th * 0.14f
                val bottom = th * 0.93f
                val right = textLeft - padX + (tw + 2 * padX) * drawIn
                ink.boxMatrix.setScale(1f, bottom - top)
                ink.boxMatrix.postTranslate(0f, top)
                ink.boxShader.setLocalMatrix(ink.boxMatrix)
                ink.boxPaint.alpha = (255 * alpha * boxed * 0.92f).toInt()
                c.drawRect(textLeft - padX, top, right, bottom, ink.boxPaint)
            }

            ink.text.color = lerp(ink.stack, ink.onBox, boxed).toArgb()
            ink.text.alpha = (255 * alpha).toInt()
            if (style.lens) {
                drawLens(c, sprite.text, textLeft, tw, th, bulge = 0.34f * slotScale(style, p.d), paint = ink.text)
            } else {
                c.drawBitmap(sprite.text, textLeft, 0f, ink.text)
            }

            if (icon != null) {
                val since = pos - appearAt(w)
                val pop = if (since < 0) 0f else easeOutBack((since / 380f).coerceIn(0f, 1f))
                val ix = if (sprite.iconLeft) groupLeft else textLeft + tw + th * 0.12f
                val iy = (th - icon.height) / 2f
                ink.icon.alpha = (255 * alpha).toInt()
                c.save()
                c.scale(pop, pop, ix + icon.width / 2f, iy + icon.height / 2f)
                c.drawBitmap(icon, ix, iy, ink.icon)
                c.restore()
            }
            c.restore()
        }
    }
}

private const val MESH_X = 12
private const val MESH_Y = 3
private val meshVerts = FloatArray((MESH_X + 1) * (MESH_Y + 1) * 2)

/**
 * The word bent over a convex lens with a vertex mesh (no shader needed): tallest in the middle, the ends
 * shorter and curving down, the way text looks under a glass dome.
 */
private fun drawLens(c: Canvas, bitmap: Bitmap, left: Float, w: Float, h: Float, bulge: Float, paint: Paint) {
    var k = 0
    val cy = h * 0.55f
    for (yi in 0..MESH_Y) {
        val y = h * yi / MESH_Y
        for (xi in 0..MESH_X) {
            val u = xi * 2f / MESH_X - 1f
            val swell = 1f + bulge * (1f - u * u)
            meshVerts[k++] = left + w / 2f + (w / 2f) * (u * (1f + 0.25f * bulge) - 0.25f * bulge * u * u * u)
            meshVerts[k++] = cy + (y - cy) * swell + h * 0.22f * bulge * u * u
        }
    }
    c.drawBitmapMesh(bitmap, MESH_X, MESH_Y, meshVerts, 0, null, 0, paint)
}

/** Three dots breathing (and kicking on the beat) while there's nothing to sing: intros and instrumental breaks. */
private fun DrawScope.waitingDots(center: Offset, f: StageFrame, ink: StageInk) {
    val r = 7f * density
    for (k in 0..2) {
        val phase = sin(f.clockMs / 1000f * 3.2f - k * 0.8f)
        val radius = r * (1f + 0.25f * phase + 0.35f * f.beat)
        drawCircle(ink.dots.copy(alpha = 0.55f + 0.3f * phase), radius, Offset(center.x + (k - 1) * r * 4.2f, center.y))
    }
}
