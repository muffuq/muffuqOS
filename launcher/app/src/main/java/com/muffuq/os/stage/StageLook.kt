package com.muffuq.os.stage

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import android.graphics.Color as AndroidColor

/** The stage's colours, taken from the album art (what Verci calls Auto Sync, Lumn "album color"). */
class StagePalette(
    /** The vivid one: the sung word, the glow's main colour. */
    val primary: Color,
    /** The glow's second colour. */
    val secondary: Color,
    /** Words that have left the box: the album's second colour, lightened to read on the dark background. */
    val lyric: Color,
    /** Not yet sung. */
    val dim: Color,
    /**
     * The album art as the background: heavily blurred, darkened and vignetted into a small opaque image
     * (done once on the CPU; the GPU then only stretches it, the bilinear filter keeps it smooth).
     */
    val backdrop: ImageBitmap,
) {
    companion object {
        fun from(art: Bitmap?, fallback: Color): StagePalette {
            if (art == null) return of(fallback.toArgb(), shiftHue(fallback.toArgb(), 40f), null)
            val small = Bitmap.createScaledBitmap(art, 32, 32, true)
            val px = IntArray(32 * 32).also { small.getPixels(it, 0, 32, 0, 0, 32, 32) }
            val hsv = FloatArray(3)
            // Score hue buckets by how much vivid colour they hold; the strongest is the primary.
            val score = FloatArray(12)
            val best = IntArray(12)
            val bestScore = FloatArray(12)
            for (p in px) {
                AndroidColor.colorToHSV(p, hsv)
                if (hsv[2] < 0.18f) continue
                val vivid = hsv[1] * hsv[2]
                val bucket = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
                score[bucket] += vivid
                if (vivid > bestScore[bucket]) {
                    bestScore[bucket] = vivid
                    best[bucket] = p
                }
            }
            val first = score.indices.maxBy { score[it] }
            // Black-and-white artwork: no colour to follow, use the launcher accent.
            if (score[first] < 3f) return of(fallback.toArgb(), shiftHue(fallback.toArgb(), 40f), art)
            val second = score.indices.filter { hueDistance(it, first) >= 2 && score[it] > score[first] * 0.12f }
                .maxByOrNull { score[it] }
            val primary = best[first]
            return of(primary, second?.let { best[it] } ?: shiftHue(primary, 45f), art)
        }

        private fun of(primary: Int, secondary: Int, art: Bitmap?): StagePalette {
            val p = Color(boost(primary, 0.6f, 0.95f))
            val s = Color(boost(secondary, 0.5f, 0.9f))
            val lyric = lerp(s, Color.White, 0.35f)
            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(primary, it) }
            val background = AndroidColor.HSVToColor(floatArrayOf(hsv[0], 0.55f, 0.1f))
            return StagePalette(p, s, lyric, lyric.copy(alpha = 0.3f), backdrop(art, background))
        }

        private fun backdrop(art: Bitmap?, background: Int): ImageBitmap {
            val w = 64
            val h = 36
            val px = IntArray(w * h) { background }
            art?.let { Bitmap.createScaledBitmap(it, w, h, true).getPixels(px, 0, w, 0, 0, w, h) }
            if (art != null) repeat(3) { boxBlur(px, w, h, 3) }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    // Darker towards the bottom and the corners, so the words and the edge light stand out.
                    val vertical = 0.58f - 0.22f * y / (h - 1)
                    val dx = (x - (w - 1) / 2f) / (w / 2f)
                    val dy = (y - (h - 1) / 2f) / (h / 2f)
                    val vignette = 1f - 0.4f * (dx * dx + dy * dy) / 2f
                    px[y * w + x] = scale(mix(px[y * w + x], background, 0.2f), vertical * vignette)
                }
            }
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }

        /** In-place box blur, horizontal then vertical. */
        private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
            val tmp = IntArray(px.size)
            fun pass(src: IntArray, dst: IntArray, len: Int, count: Int, at: (Int, Int) -> Int) {
                for (line in 0 until count) {
                    for (i in 0 until len) {
                        var red = 0
                        var green = 0
                        var blue = 0
                        var n = 0
                        for (k in -r..r) {
                            val c = src[at(line, (i + k).coerceIn(0, len - 1))]
                            red += AndroidColor.red(c)
                            green += AndroidColor.green(c)
                            blue += AndroidColor.blue(c)
                            n++
                        }
                        dst[at(line, i)] = AndroidColor.rgb(red / n, green / n, blue / n)
                    }
                }
            }
            pass(px, tmp, w, h) { y, x -> y * w + x }
            pass(tmp, px, h, w) { x, y -> y * w + x }
        }

        private fun mix(a: Int, b: Int, t: Float) = AndroidColor.rgb(
            (AndroidColor.red(a) * (1 - t) + AndroidColor.red(b) * t).toInt(),
            (AndroidColor.green(a) * (1 - t) + AndroidColor.green(b) * t).toInt(),
            (AndroidColor.blue(a) * (1 - t) + AndroidColor.blue(b) * t).toInt(),
        )

        private fun scale(c: Int, k: Float) = AndroidColor.rgb(
            (AndroidColor.red(c) * k).toInt().coerceIn(0, 255),
            (AndroidColor.green(c) * k).toInt().coerceIn(0, 255),
            (AndroidColor.blue(c) * k).toInt().coerceIn(0, 255),
        )

        private fun boost(color: Int, minSat: Float, minValue: Float): Int {
            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
            hsv[1] = hsv[1].coerceAtLeast(minSat)
            hsv[2] = hsv[2].coerceAtLeast(minValue)
            return AndroidColor.HSVToColor(hsv)
        }

        private fun shiftHue(color: Int, degrees: Float): Int {
            val hsv = FloatArray(3).also { AndroidColor.colorToHSV(color, it) }
            hsv[0] = (hsv[0] + degrees) % 360f
            return AndroidColor.HSVToColor(hsv)
        }

        private fun hueDistance(a: Int, b: Int) = min(abs(a - b), 12 - abs(a - b))
    }
}

/** A soft round light (alpha only), stretched and tinted behind the word being sung. */
fun glowDot(): ImageBitmap {
    val n = 64
    val bitmap = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8)
    val stride = bitmap.rowBytes
    val bytes = ByteArray(stride * n)
    for (y in 0 until n) {
        for (x in 0 until n) {
            val d = hypot(x + 0.5f - n / 2f, y + 0.5f - n / 2f) / (n / 2f)
            bytes[y * stride + x] = (exp(-d * d * 3.2f) * (1f - d).coerceAtLeast(0f) * 255).toInt().toByte()
        }
    }
    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
    return bitmap.asImageBitmap()
}

/**
 * Lumn-style light around the screen edges: two colours turning around the frame, swelling on beats.
 * Drawn as four edge strips (the middle of the screen has no light, so it isn't touched) with an edge mask ×
 * a linear gradient: no blur, no offscreen layer, no per-pixel angle maths. This TV's GPU is easily filled.
 */
class EdgeGlow {
    private var mask: Bitmap? = null
    private var maskShader: BitmapShader? = null
    private var maskFor = 0L
    private var gradient: LinearGradient? = null
    private var composed: ComposeShader? = null
    private var gradientFor = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskMatrix = Matrix()
    private val gradientMatrix = Matrix()

    /**
     * @param turns rotation of the colours, in full turns
     * @param strength overall opacity 0..1
     * @param swell 0..1, thickens the band (beats)
     */
    fun draw(scope: DrawScope, primary: Color, secondary: Color, turns: Float, strength: Float, swell: Float) {
        val w = scope.size.width
        val h = scope.size.height
        if (w <= 0f || h <= 0f) return
        val shader = ensureShader(w, h, primary.toArgb(), secondary.toArgb())
        val mask = mask!!
        val cx = w / 2f
        val cy = h / 2f
        // Shrinking the mask towards the centre lets its full-strength edge (CLAMP) cover a wider band.
        val k = 1f - 0.025f * swell.coerceIn(0f, 1f)
        maskMatrix.setScale(w / mask.width * k, h / mask.height * k)
        maskMatrix.postTranslate(cx * (1f - k), cy * (1f - k))
        maskShader!!.setLocalMatrix(maskMatrix)
        gradientMatrix.setRotate(turns * 360f, cx, cy)
        gradient!!.setLocalMatrix(gradientMatrix)
        paint.shader = shader
        paint.alpha = (strength.coerceIn(0f, 1f) * 255).toInt()
        val band = h * BAND
        scope.drawIntoCanvas {
            val c = it.nativeCanvas
            c.drawRect(0f, 0f, w, band, paint)
            c.drawRect(0f, h - band, w, h, paint)
            c.drawRect(0f, band, band, h - band, paint)
            c.drawRect(w - band, band, w, h - band, paint)
        }
    }

    private fun ensureShader(w: Float, h: Float, p: Int, s: Int): ComposeShader {
        val sizeKey = (w.toLong() shl 32) or h.toLong()
        if (maskFor != sizeKey) {
            buildMask(w, h)
            maskFor = sizeKey
            gradientFor = 0
        }
        val colorKey = (p.toLong() shl 32) or (s.toLong() and 0xFFFFFFFFL)
        if (gradientFor != colorKey || composed == null) {
            // Half the diagonal per colour, mirrored: one side of the frame primary, the opposite secondary.
            val r = hypot(w, h) / 2f
            gradient = LinearGradient(w / 2f - r, h / 2f, w / 2f + r, h / 2f, p, s, Shader.TileMode.MIRROR)
            composed = ComposeShader(maskShader!!, gradient!!, PorterDuff.Mode.SRC_IN)
            gradientFor = colorKey
        }
        return composed!!
    }

    private fun buildMask(w: Float, h: Float) {
        // Low resolution is plenty for a soft falloff; the GPU's bilinear filter smooths it up to full screen.
        val mw = 192
        val mh = (192 * h / w).toInt().coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(mw, mh, Bitmap.Config.ALPHA_8)
        val stride = bitmap.rowBytes
        val bytes = ByteArray(stride * mh)
        // Thin, like a light hugging the bezel rather than a band.
        val near = h * 0.018f
        val far = h * 0.05f
        // The light must be exactly zero well before the strips end (even when a beat swells it), or the
        // strips' inner edge shows as a dark rectangle in the middle of the screen.
        val reach = h * REACH
        for (y in 0 until mh) {
            val fy = (y + 0.5f) / mh * h
            for (x in 0 until mw) {
                val fx = (x + 0.5f) / mw * w
                var a = 0f
                // Sum over the four edges, so corners pool a little more light, like a real bezel glow.
                for (d in floatArrayOf(fx, w - fx, fy, h - fy)) {
                    if (d >= reach) continue
                    val fade = (1f - d / reach).let { it * it }
                    a += (0.8f * exp(-d / near) + 0.25f * exp(-d / far)) * fade
                }
                bytes[y * stride + x] = (a.coerceAtMost(1f) * 255).toInt().toByte()
            }
        }
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        mask?.recycle()
        mask = bitmap
        maskShader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        composed = null
    }

    private companion object {
        /** Strip thickness as a share of the height. */
        const val BAND = 0.16f
        /** Where the light reaches zero; with the beat swell it still stays inside [BAND]. */
        const val REACH = 0.1f
    }
}
