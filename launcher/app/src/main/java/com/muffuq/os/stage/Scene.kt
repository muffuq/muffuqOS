package com.muffuq.os.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/*
 * The stage's lyrics are drawn by scenes. A scene is one complete way of animating lyrics (the Verci-style
 * word stack, a 3D tunnel, a typographic poster…); the stage hands it the canvas every frame.
 *
 * Rules every scene follows, because this runs on a 1 GB Android TV with a Mali-470 (GLES 2) GPU at 1280×720:
 * - Per frame, only draw: bitmaps (word sprites / glyph pieces), rects, circles, bitmap meshes (sparingly),
 *   with save/translate/scale/rotate/skew/clipRect. No drawText, no text measuring, no new Shader/Bitmap/Path,
 *   no blur, no saveLayer (fade with paint alpha). Reuse objects; keep it under ~150 draw calls.
 * - Words are pre-rendered textures ([WordSprite]): white-on-transparent ALPHA_8, tinted with the paint's
 *   colour. They are rendered at the size they're shown biggest; scaling down is fine, up by more than ~1.2×
 *   gets soft.
 */

/** Where the lyrics layer is looked at from. Scenes set it every frame (smoothly); the stage applies it in 3D. */
class StageCamera {
    var rotX = 0f
    var rotY = 0f
    var rotZ = 0f
    var zoom = 1f
    /** Sideways / vertical framing, in pixels. */
    var shiftX = 0f
    var shiftY = 0f

    fun reset() {
        rotX = 0f; rotY = 0f; rotZ = 0f; zoom = 1f; shiftX = 0f; shiftY = 0f
    }
}

/** Everything a scene needs for a frame. The stage owns it and updates the moving parts each frame. */
class SceneContext(val lyrics: Lyrics, val sprites: SpriteCache, ink: StageInk, val effects: Effects) {
    /** Colours and paints. Can change between frames (the palette turns at explosive moments): read it each frame. */
    var ink: StageInk = ink
    var width = 0f
    var height = 0f
    /** Pixels per dp. */
    var density = 1f
    /** Song position in ms (already shifted a little early, so the eye isn't late). */
    var positionMs = 0L
    /** Wall clock in ms: keeps running while the song is paused, for ambient motion. */
    var clockMs = 0L
    /** Beat envelope 0..1: jumps on a kick, falls off over ~0.3 s. */
    var beat = 0f
    /** Smoothed loudness 0..1. */
    var level = 0f
    var playing = false
    val camera = StageCamera()

    /** Index of the line being sung (shifted [leadMs] early), -1 before the first line. */
    fun lineAt(leadMs: Long = 0): Int = lyrics.lineAt(positionMs + leadMs)

    fun line(index: Int): LyricLine? = lyrics.lines.getOrNull(index)

    /** The word's texture, or null when its line isn't rendered (yet): skip it then. */
    fun sprite(line: Int, word: Int): WordSprite? = sprites.get(line, word)
}

/** One way of animating lyrics. An instance lives as long as it's on screen; keep per-scene state in fields. */
interface Scene {
    fun draw(c: Canvas, s: SceneContext)
}

/**
 * A word drawn once into a texture at the size it's shown biggest.
 * [glyphLeft]/[glyphRight]: each character's horizontal extent in the texture, for per-letter animation.
 */
class WordSprite(
    val text: Bitmap,
    val icon: Bitmap?,
    val iconLeft: Boolean,
    val chars: String,
    val glyphLeft: FloatArray,
    val glyphRight: FloatArray,
) {
    val width get() = text.width
    val height get() = text.height
    val glyphCount get() = chars.length
}

private val src = Rect()
private val dst = RectF()

/** The whole word with its top-left at ([x], [y]), tinted by [paint]'s colour. */
fun drawWord(c: Canvas, sprite: WordSprite, x: Float, y: Float, paint: Paint) {
    c.drawBitmap(sprite.text, x, y, paint)
}

/** The first [fraction] (0..1) of the word from the left, at its place: wipes and karaoke fills. */
fun drawWordPart(c: Canvas, sprite: WordSprite, x: Float, y: Float, fraction: Float, paint: Paint, fromRight: Boolean = false) {
    val w = (sprite.width * fraction.coerceIn(0f, 1f)).toInt()
    if (w <= 0) return
    if (fromRight) {
        src.set(sprite.width - w, 0, sprite.width, sprite.height)
        dst.set(x + sprite.width - w, y, x + sprite.width, y + sprite.height)
    } else {
        src.set(0, 0, w, sprite.height)
        dst.set(x, y, x + w, y + sprite.height)
    }
    c.drawBitmap(sprite.text, src, dst, paint)
}

/**
 * Letter [i] of the word with its own left edge at ([x], [y]). To keep a letter where it sits in the word, pass
 * x = wordX + [glyphX]. Transform the canvas around it for per-letter motion.
 */
fun drawGlyph(c: Canvas, sprite: WordSprite, i: Int, x: Float, y: Float, paint: Paint) {
    val l = sprite.glyphLeft[i].toInt().coerceIn(0, sprite.width)
    val r = kotlin.math.ceil(sprite.glyphRight[i]).toInt().coerceIn(l, sprite.width)
    if (r <= l) return
    src.set(l, 0, r, sprite.height)
    dst.set(x, y, x + (r - l), y + sprite.height)
    c.drawBitmap(sprite.text, src, dst, paint)
}

/** Letter [i]'s left edge within its word. */
fun glyphX(sprite: WordSprite, i: Int) = sprite.glyphLeft[i]

/** Letter [i]'s horizontal centre within its word. */
fun glyphCenter(sprite: WordSprite, i: Int) = (sprite.glyphLeft[i] + sprite.glyphRight[i]) / 2f

/** True for spaces/punctuation-only glyphs worth skipping in letter effects. */
fun glyphIsBlank(sprite: WordSprite, i: Int) = sprite.chars[i].isWhitespace() || sprite.glyphRight[i] - sprite.glyphLeft[i] < 1f

/** 0..1 through the word's sung time (0 before, 1 after). */
fun wordProgress(w: LyricWord, positionMs: Long): Float =
    ((positionMs - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)

/** Index of the newest word of [line] that has started (shifted [leadMs] early), -1 if none yet. */
fun newestWordAt(line: LyricLine, positionMs: Long, leadMs: Long = 100): Int = line.words.indexOfLast { it.startMs - leadMs <= positionMs }
