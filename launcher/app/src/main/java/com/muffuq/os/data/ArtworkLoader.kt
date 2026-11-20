package com.muffuq.os.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Color as AndroidColor

/** [seed] is a 40x22 thumbnail of the card, the source for its ambient backdrop. */
sealed class Artwork(val seed: Bitmap, val bytes: Int) {
    /** A proper 16:9 TV banner. */
    class Banner(val image: ImageBitmap, seed: Bitmap, bytes: Int) : Artwork(seed, bytes)

    /** Phone-style square icon, shown on a backdrop tinted with its dominant colour. */
    class Icon(val image: ImageBitmap, val backdrop: Color, seed: Bitmap, bytes: Int) : Artwork(seed, bytes)
}

/**
 * Decodes banners/icons off the main thread at the size they are drawn and keeps them in a bounded LRU.
 * Cheap TVs have well under 1 GB of RAM: opaque banners are kept as RGB_565 (half the bytes, and half the GPU
 * texture), and no hardware bitmaps, because some TV gralloc drivers pad every one to ~1 MB.
 */
class ArtworkLoader(context: Context) {
    private val pm = context.packageManager

    // Big enough for the largest card (a quarter of the screen width), never bigger than the 320x180 source.
    private val bannerWidth = (context.resources.displayMetrics.widthPixels * 0.21f).toInt().coerceIn(160, 320)
    private val bannerHeight = bannerWidth * 9 / 16
    private val iconSize = (bannerHeight * 0.6f).toInt().coerceAtLeast(64)

    private val cache = object : LruCache<String, Artwork>(cacheBytes()) {
        override fun sizeOf(key: String, value: Artwork) = value.bytes
    }

    /** Pre-blurred backdrops (~56 KB each). */
    private val ambientCache = LruCache<String, ImageBitmap>(24)

    fun peek(app: AppEntry): Artwork? = cache.get(app.component.flattenToString())

    suspend fun load(app: AppEntry): Artwork = withContext(Dispatchers.IO) {
        val key = app.component.flattenToString()
        cache.get(key) ?: decode(app).also { cache.put(key, it) }
    }

    /**
     * A soft, dark colour field derived from the app's artwork. Blurred on the CPU at 40x22 and upscaled by the
     * GPU, which looks the same as a real blur at full screen but costs nothing per frame, even on cheap TVs.
     */
    suspend fun ambient(app: AppEntry): ImageBitmap {
        val key = app.component.flattenToString()
        ambientCache.get(key)?.let { return it }
        val artwork = load(app)
        return withContext(Dispatchers.Default) {
            ambientFrom(artwork.seed).asImageBitmap().also { ambientCache.put(key, it) }
        }
    }

    /** Drops cached artwork for [packageName], or everything when null. */
    fun evict(packageName: String?) {
        if (packageName == null) {
            cache.evictAll()
            ambientCache.evictAll()
            return
        }
        val prefix = "$packageName/"
        cache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { cache.remove(it) }
        ambientCache.snapshot().keys.filter { it.startsWith(prefix) }.forEach { ambientCache.remove(it) }
    }

    private fun decode(app: AppEntry): Artwork {
        val banner = runCatching {
            pm.getActivityBanner(app.component) ?: pm.getApplicationBanner(app.packageName)
        }.getOrNull()
        if (banner != null) {
            val bitmap = banner.toBitmap(bannerWidth, bannerHeight, Bitmap.Config.ARGB_8888)
            val seed = seedOf { canvas, paint ->
                canvas.drawColor(AndroidColor.BLACK)
                canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), paint)
            }
            val compact = if (bitmap.hasAlpha()) bitmap else bitmap.copy(Bitmap.Config.RGB_565, false) ?: bitmap
            return Artwork.Banner(compact.asImageBitmap(), seed, compact.allocationByteCount)
        }

        val icon = runCatching { pm.getActivityIcon(app.component) }.getOrElse { pm.defaultActivityIcon }
        val bitmap = icon.toBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
        val backdrop = backdropFor(bitmap)
        val seed = seedOf { canvas, paint ->
            canvas.drawColor(backdrop.toArgb())
            val s = canvas.height * 3 / 4
            val left = (canvas.width - s) / 2
            val top = (canvas.height - s) / 2
            canvas.drawBitmap(bitmap, null, Rect(left, top, left + s, top + s), paint)
        }
        return Artwork.Icon(bitmap.asImageBitmap(), backdrop, seed, bitmap.allocationByteCount)
    }

    private inline fun seedOf(draw: (Canvas, Paint) -> Unit): Bitmap {
        var bitmap = Bitmap.createBitmap(SEED_SOURCE_W, SEED_SOURCE_H, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        // Halve repeatedly so every source pixel contributes (a single big downscale just skips pixels).
        while (bitmap.width > SEED_W) {
            bitmap = Bitmap.createScaledBitmap(bitmap, bitmap.width / 2, bitmap.height / 2, true)
        }
        return bitmap
    }

    private fun ambientFrom(seed: Bitmap): Bitmap {
        val w = seed.width
        val h = seed.height
        val px = IntArray(w * h).also { seed.getPixels(it, 0, w, 0, 0, w, h) }
        repeat(3) { boxBlur(px, w, h) }

        // Keep it moody: only the colourful parts glow. Greys/whites sink towards black, otherwise a white
        // banner (YouTube, Play Store...) turns the whole room into grey fog.
        val hsv = FloatArray(3)
        for (i in px.indices) {
            AndroidColor.colorToHSV(px[i], hsv)
            val saturation = hsv[1]
            hsv[1] = (saturation * 1.35f).coerceAtMost(1f)
            hsv[2] = hsv[2].coerceAtMost(0.7f) * (0.12f + 0.68f * saturation)
            px[i] = AndroidColor.HSVToColor(hsv)
        }
        val tiny = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(tiny, w * 4, h * 4, true)
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int) {
        val out = IntArray(px.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0
                var g = 0
                var b = 0
                for (dy in -1..1) {
                    val row = (y + dy).coerceIn(0, h - 1) * w
                    for (dx in -1..1) {
                        val c = px[row + (x + dx).coerceIn(0, w - 1)]
                        r += (c shr 16) and 0xFF
                        g += (c shr 8) and 0xFF
                        b += c and 0xFF
                    }
                }
                out[y * w + x] = (0xFF shl 24) or ((r / 9) shl 16) or ((g / 9) shl 8) or (b / 9)
            }
        }
        out.copyInto(px)
    }

    /** Average of the icon's saturated, opaque pixels, darkened so white labels and the icon stay readable. */
    private fun backdropFor(bitmap: Bitmap): Color {
        val small = Bitmap.createScaledBitmap(bitmap, SAMPLE, SAMPLE, true)
        val pixels = IntArray(SAMPLE * SAMPLE).also { small.getPixels(it, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE) }
        val hsv = FloatArray(3)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        for (p in pixels) {
            if (AndroidColor.alpha(p) < 160) continue
            AndroidColor.colorToHSV(p, hsv)
            if (hsv[1] < 0.2f || hsv[2] < 0.15f) continue
            r += AndroidColor.red(p)
            g += AndroidColor.green(p)
            b += AndroidColor.blue(p)
            n++
        }
        if (n == 0) return FALLBACK_BACKDROP
        AndroidColor.colorToHSV(AndroidColor.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt()), hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
        hsv[2] = 0.38f
        return Color(AndroidColor.HSVToColor(hsv))
    }

    private companion object {
        const val SAMPLE = 12
        const val SEED_SOURCE_W = 160
        const val SEED_SOURCE_H = 90
        const val SEED_W = 40
        val FALLBACK_BACKDROP = Color(0xFF2A3040)

        fun cacheBytes(): Int = (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(16L * 1024 * 1024).toInt()
    }
}
