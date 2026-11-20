package com.muffuq.os.stage

import android.graphics.Canvas
import android.graphics.Paint
import java.util.Locale

/**
 * What the remote cycles through: the director, which edits the whole song across scenes, or one scene.
 * [scene] builds a fresh instance.
 */
enum class StageMode(val label: String, val scene: () -> Scene) {
    Director("Otomatik", { DirectorScene() }),
    Ship("Ship", { StackScene(StageStyle.Ship) }),
    Fisheye("Fisheye", { StackScene(StageStyle.Fisheye) }),
    Visual("Visual", { StackScene(StageStyle.Visual) }),
    FisheyeVisual("Fisheye Visual", { StackScene(StageStyle.FisheyeVisual) }),
    Wave("Dalga", { WaveScene() }),
    Tunnel("Tünel", { TunnelScene() }),
    BigWord("Dev", { BigWordScene() }),
    Poster("Poster", { PosterScene() }),
}

/**
 * The editor: cuts the song into sections and gives each its own scene, like a lyric video changes looks
 * between verses and the chorus. A section starts at the first line, after an instrumental break, or every
 * four lines. Lines that come back (the chorus) get the most striking scenes. Cuts happen on line changes,
 * with a short flash and a camera punch so they read as intentional edits rather than glitches.
 */
class DirectorScene : Scene {
    private var plannedFor: Lyrics? = null
    private var plan = IntArray(0)
    private val scenes = HashMap<Int, Scene>()
    private var shown = -1
    private var cutAt = 0L
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas, s: SceneContext) {
        if (plannedFor !== s.lyrics) {
            plan = plan(s.lyrics)
            plannedFor = s.lyrics
            scenes.clear()
            shown = -1
        }
        val li = s.lineAt(110).coerceAtLeast(0)
        val choice = plan.getOrElse(li) { 0 }
        if (choice != shown) {
            if (shown >= 0) {
                cutAt = s.clockMs
                s.effects.burst(s.width / 2f, s.height / 2f, intArrayOf(s.ink.primary, s.ink.secondary), s.density, li * 7 + 3)
            }
            shown = choice
            s.camera.reset()
        }
        scenes.getOrPut(choice) { cast[choice].scene() }.draw(c, s)

        // The cut: a quick flash of the album colour and a punch-in, fading over a third of a second.
        val t = (s.clockMs - cutAt) / 320f
        if (t < 1f) {
            flash.color = s.ink.primaryLight
            flash.alpha = (255 * 0.28f * (1f - t) * (1f - t)).toInt()
            c.drawRect(-s.width * 0.5f, -s.height * 0.5f, s.width * 1.5f, s.height * 1.5f, flash)
            s.camera.zoom *= 1f + 0.08f * (1f - t)
        }
    }

    private companion object {
        /** The scenes the director cuts between: the chorus gets the [heroes], verses the rest. */
        val cast = listOf(StageMode.Ship, StageMode.Fisheye, StageMode.Visual, StageMode.FisheyeVisual, StageMode.Wave, StageMode.Tunnel, StageMode.BigWord, StageMode.Poster)
        val heroes = listOf(6, 5, 7)
        val verses = listOf(7, 4, 0, 2, 5, 1, 3)

        fun plan(lyrics: Lyrics): IntArray {
            val lines = lyrics.lines
            val texts = lines.map { l -> l.words.joinToString(" ") { it.text }.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == ' ' } }
            val counts = texts.groupingBy { it }.eachCount()
            val result = IntArray(lines.size)
            var sectionStart = 0
            var previous = -1
            var heroTurn = 0
            var verseTurn = 0
            var i = 0
            while (i < lines.size) {
                // Section boundaries: an instrumental break, or four lines.
                var end = i + 1
                while (end < lines.size && end - i < 4 && lines[end].startMs - lines[end - 1].endMs < 3_500) end++
                val chorus = (i until end).count { counts.getValue(texts[it]) >= 2 } * 2 > end - i
                var pick = if (chorus) heroes[heroTurn++ % heroes.size] else verses[verseTurn++ % verses.size]
                if (pick == previous) pick = if (chorus) heroes[heroTurn++ % heroes.size] else verses[verseTurn++ % verses.size]
                for (k in i until end) result[k] = pick
                previous = pick
                sectionStart = end
                i = end
            }
            return result
        }
    }
}
