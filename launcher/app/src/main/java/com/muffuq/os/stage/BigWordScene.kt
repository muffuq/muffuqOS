package com.muffuq.os.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * "Dev": one giant word at a time in the middle of the screen, the punchy lyric-edit look.
 *
 * Over a song:
 * - Intro and instrumental breaks: three big soft dots breathe. In the last 1.5 s before the singing they pop one
 *   by one (a count-in) while the coming line's first word fades in faintly where it will land.
 * - Every word arrives letter by letter, never the same way twice in a row, and quicker when the words come fast:
 *   slam (each letter hits and squashes), assemble (letters fly in from everywhere), flip (up off the baseline),
 *   stretch (springs in from wide, the outer letters catching up), type (with an album-colour cursor), bounce
 *   (drops from above), spin (grows out of each letter's centre), rise (out from behind a line). Held notes and
 *   line endings get the punch: the word rushes at the camera trailing echoes.
 * - While it's sung, the fill runs from near-white to the album colour across the letters, each letter hopping as
 *   the fill reaches it. Held notes keep growing and tremble; when they land: sparks, a camera punch and a flash,
 *   bigger for the line's last word, and the word turns solid (a short 3D extrusion).
 * - When the next word comes the old one leaves its own way: bursts apart, falls to pieces, flies past the camera,
 *   is sliced in two, or shrinks up into the sentence row.
 * - Above the big word, the last few words of the line in small type, so the sentence can be followed.
 * - The camera drifts like it's handheld, takes a different angle for every line, punches on emphasis and shakes
 *   on strong beats (with a faint flash of the album colour).
 *
 * Everything tied to the lyrics runs on the song position (it freezes with the song when paused); ambient motion
 * (drift, dots, cursor blink) runs on the wall clock and calms down while paused.
 */
class BigWordScene : Scene {

    /**
     * How a word arrives. [ms]: its length when there's time; [spread]: the share of it over which the letters are
     * staggered; [needMs]: time to the next word it needs to still read; [landAt]: share of it where it hits.
     */
    private enum class Arrive(val ms: Float, val spread: Float, val needMs: Long, val landAt: Float) {
        Slam(560f, 0.55f, 300, 0.75f),
        Assemble(600f, 0.35f, 380, 0.8f),
        Flip(460f, 0.5f, 0, 0.6f),
        Stretch(640f, 0.35f, 380, 0.45f),
        Type(0f, 0f, 340, 1f),
        Bounce(760f, 0.45f, 460, 0.65f),
        Spin(500f, 0.5f, 0, 0.8f),
        Rise(440f, 0.4f, 0, 0.7f),

        /** Held notes and line endings: the word rushes at the camera and hits. */
        Punch(400f, 0f, 0, 0.42f),
    }

    /** How a word leaves when the next one comes. [perLetter]: needs the letters drawn one by one. */
    private enum class Leave(val ms: Float, val perLetter: Boolean) {
        Explode(560f, true),
        Fall(720f, true),
        Zoom(380f, false),
        Split(460f, false),

        /** Shrinks up into its place in the sentence row. */
        ToRow(420f, false),
    }

    /** A word on its way out, drawn from where it last stood. */
    private class Leaving {
        var active = false
        var sprite: WordSprite? = null
        var line = -1
        var word = -1
        var how = Leave.Zoom
        var startMs = 0L
        var dur = 400f
        var cx = 0f
        var cy = 0f
        var scale = 1f
        var rot = 0f
        var side = 1f
        var iconX = 0f
        var iconY = 0f

        /** ToRow: its place in the row, kept up to date while the row has it, so the flight never loses its way. */
        var hasTarget = false
        var tx = 0f
        var ty = 0f
        var ts = 1f
        var ta = 1f
        val r1 = FloatArray(MAX_GLYPHS)
        val r2 = FloatArray(MAX_GLYPHS)
        val r3 = FloatArray(MAX_GLYPHS)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()

    // Letter poses, filled by an entrance or exit and drawn right away. Offsets in sprite pixels; the pivot is a share
    // of the sprite's height (0.5 spins round the middle, BASELINE squashes onto the line).
    private val lx = FloatArray(MAX_GLYPHS)
    private val ly = FloatArray(MAX_GLYPHS)
    private val lsx = FloatArray(MAX_GLYPHS)
    private val lsy = FloatArray(MAX_GLYPHS)
    private val lrot = FloatArray(MAX_GLYPHS)
    private val la = FloatArray(MAX_GLYPHS)
    private val lpy = FloatArray(MAX_GLYPHS)

    // Whole-word parts of an entrance.
    private var wMul = 1f
    private var wRot = 0f
    private var wAlpha = 1f
    private var wMask = false

    /** Letters that have hit so far (slam) or been typed: each knocks the camera once. */
    private var poseHits = 0

    // The word on screen.
    private var lyricsRef: Lyrics? = null
    private var curLine = -1
    private var curWord = -1
    private var curSprite: WordSprite? = null
    private var curArrive = Arrive.Rise
    private var curOrder = ORDER_LTR
    private var curAppear = 0L
    private var curDur = 400f
    private var curHeld = false
    private var curFinale = false

    /** Too many letters to move one by one within the draw budget: animated as a whole. */
    private var curWhole = false
    private var curOffX = 0f
    private var curOffY = 0f
    private var curTilt = 0f
    private var curSide = 1f
    private var curThuds = 0
    private val r1 = FloatArray(MAX_GLYPHS)
    private val r2 = FloatArray(MAX_GLYPHS)
    private val r3 = FloatArray(MAX_GLYPHS)
    private val r4 = FloatArray(MAX_GLYPHS)
    private val r5 = FloatArray(MAX_GLYPHS)

    // Its layout this frame, and how it was last drawn (handed to its exit).
    private var baseScale = 1f
    private var wordCx = 0f
    private var wordCy = 0f
    private var drawnScale = 1f
    private var drawnRot = 0f
    private var drawnIconX = 0f
    private var drawnIconY = 0f

    private var lastArrive: Arrive? = null
    private var lastLeave: Leave? = null
    private val arrivePool = arrayOfNulls<Arrive>(Arrive.entries.size)
    private var arriveCount = 0
    private val leavePool = arrayOfNulls<Leave>(8)
    private var leaveCount = 0
    private val leaving = arrayOf(Leaving(), Leaving())

    // Waiting: intro, instrumental breaks.
    private var waitLine = -1
    private var noneSince = 0L
    private var teaseOffX = 0f
    private var teaseOffY = 0f

    // The sentence row: one spring per slot so words slide along as the row grows.
    private val slotWord = IntArray(8) { -1 }
    private val slotX = Array(8) { Spring(13f) }
    private val rowY = Spring(9f)
    private var rowTargetY = 0f
    private var rowChangedAt = 0L
    private var rowCount = 0
    private val rowSprite = arrayOfNulls<WordSprite>(4)
    private val rowX = FloatArray(4)
    private val rowS = FloatArray(4)
    private val rowA = FloatArray(4)
    private var rowDrawnY = 0f

    // The previous line's row, drifting up and away.
    private var oldCount = 0
    private val oldSprite = arrayOfNulls<WordSprite>(4)
    private val oldX = FloatArray(4)
    private val oldS = FloatArray(4)
    private val oldA = FloatArray(4)
    private var oldY = 0f
    private var oldAt = 0L

    // Camera: the line's pose through springs, with drift, punches and shake on top.
    private val camRx = Spring(2.6f)
    private val camRy = Spring(2.6f)
    private val camRz = Spring(2.6f)
    private val camZoom = Spring(2.6f)
    private val camSx = Spring(3.2f)
    private val camSy = Spring(3.2f)
    private var poseRx = 4f
    private var poseRy = 0f
    private var poseRz = 0f
    private var poseZoom = 0.96f
    private var poseShift = 0f
    private var punchAt = -100_000L
    private var punchZoom = 0f
    private var punchRot = 0f
    private var carryZoom = 0f
    private var carryRot = 0f
    private var shakeAt = -100_000L
    private var shakeAmp = 0f
    private var flashAt = -100_000L
    private var flashAmp = 0f
    private var beatAt = -100_000L
    private var lastBeat = 0f

    /** Wall-clock seconds since the scene came up, for ambient motion (small numbers keep the sines precise). */
    private var ambient = 0f
    private var lastClock = 0L

    // Colours derived from the palette.
    private var inkRef: StageInk? = null
    private var shadowColor = 0
    private var extrudeColor = 0
    private val burstColors = IntArray(3)

    override fun draw(c: Canvas, s: SceneContext) {
        if (s.width <= 0f || s.height <= 0f) return
        if (s.lyrics !== lyricsRef) reset(s)
        if (s.ink !== inkRef) tint(s.ink)
        val dt = if (lastClock == 0L) 0f else ((s.clockMs - lastClock) / 1000f).coerceIn(0f, 0.1f)
        lastClock = s.clockMs
        ambient += dt

        track(s)
        layout(s)
        steer(s)

        drawFlash(c, s)
        drawWaiting(c, s)
        drawRow(c, s)
        drawLeaving(c, s)
        drawCurrent(c, s)
    }

    private fun reset(s: SceneContext) {
        lyricsRef = s.lyrics
        curLine = -1
        curWord = -1
        curSprite = null
        for (l in leaving) {
            l.active = false
            l.sprite = null
        }
        oldCount = 0
        rowCount = 0
        resetSlots()
        noneSince = s.clockMs
        aimGap()
    }

    private fun tint(ink: StageInk) {
        inkRef = ink
        shadowColor = mix(ink.primary, BLACK, 0.74f)
        extrudeColor = mix(ink.secondary, BLACK, 0.35f)
        burstColors[0] = ink.primary
        burstColors[1] = ink.light
        burstColors[2] = ink.secondary
    }

    // ---- Timing: which word is up -------------------------------------------------------------------------------

    private fun track(s: SceneContext) {
        val pos = s.positionMs
        val lines = s.lyrics.lines
        val li = s.lineAt(LEAD_MS)
        var tLine = -1
        var tWord = -1
        var wait = if (li < 0 && lines.isNotEmpty()) 0 else -1
        if (li >= 0) {
            val line = lines[li]
            val nextStart = lines.getOrNull(li + 1)?.startMs ?: Long.MAX_VALUE
            // A long instrumental break after this line: let the word go and wait for the next one.
            val gap = nextStart - line.endMs > GAP_MS && pos > line.endMs + LINGER_MS
            if (gap) {
                if (li + 1 < lines.size) wait = li + 1
            } else {
                val w = newestWordAt(line, pos, LEAD_MS)
                if (w >= 0) {
                    tLine = li
                    tWord = w
                } else if (curLine == li - 1 && curWord >= 0) {
                    // The line has started but its first word hasn't: the last word stays a moment longer.
                    tLine = curLine
                    tWord = curWord
                }
            }
        }
        if (wait != waitLine && wait >= 0) {
            // The teaser stands where that word will land.
            teaseOffX = offX(wait, 0)
            teaseOffY = offY(wait, 0)
        }
        waitLine = wait
        // Position estimates wobble a little: only step back a word for a real seek.
        if (tLine == curLine && tWord in 0 until curWord && pos > curAppear - 250) tWord = curWord
        if (tLine == curLine && tWord == curWord) return
        change(s, tLine, tWord)
    }

    private fun change(s: SceneContext, tLine: Int, tWord: Int) {
        val lineChange = tLine != curLine
        val toNone = tWord < 0
        // The incoming word's tempo sets how quickly the outgoing one must clear the stage.
        val avail = if (toNone) 1_200L else availFor(s, tLine, tWord)
        if (curWord >= 0 && curSprite != null) {
            val how = chooseLeave(toNone, lineChange)
            val dur = if (toNone) how.ms * 1.25f else min(how.ms, max(avail * 1.6f, 240f))
            pushLeaving(s, how, dur)
            lastLeave = how
        }
        if (lineChange) {
            snapshotRow(s)
            resetSlots()
        }
        rowChangedAt = s.positionMs
        curLine = tLine
        curWord = tWord
        curSprite = null
        if (toNone) {
            noneSince = s.clockMs
            aimGap()
            return
        }
        if (lineChange) aimLine(tLine, s.width)
        setupWord(s, avail)
    }

    /** Time from this word to the next (or to the line's end), which paces its entrance. */
    private fun availFor(s: SceneContext, li: Int, wi: Int): Long {
        val line = s.lyrics.lines[li]
        val w = line.words[wi]
        val next = line.words.getOrNull(wi + 1)?.startMs ?: min(s.line(li + 1)?.startMs ?: Long.MAX_VALUE, w.endMs + 900)
        return (next - w.startMs).coerceIn(100L, 4_000L)
    }

    private fun setupWord(s: SceneContext, avail: Long) {
        val li = curLine
        val wi = curWord
        val line = s.lyrics.lines[li]
        val w = line.words[wi]
        val n = w.text.length
        curAppear = w.startMs - LEAD_MS
        // Estimated timings stretch words evenly, so only trust a long one as a held note when it's very long.
        curHeld = w.endMs - w.startMs >= if (s.lyrics.wordTimed) HELD_MS else HELD_ESTIMATED_MS
        curFinale = wi == line.words.lastIndex && line.words.size >= 3
        curWhole = n > LETTER_CAP
        curArrive = chooseArrive(li, wi, n, avail)
        lastArrive = curArrive
        // Fast words get fast entrances, so they're readable before the next one comes.
        curDur = when (curArrive) {
            Arrive.Type -> (n * 50f + 80f).coerceAtMost(avail * 0.75f).coerceAtLeast(120f)
            else -> min(curArrive.ms, max(avail * 0.65f, 140f))
        }
        val o = rand(li, wi, 76)
        curOrder = when (curArrive) {
            Arrive.Slam, Arrive.Spin -> if (o < 0.7f) ORDER_LTR else ORDER_RTL
            Arrive.Assemble -> ORDER_RANDOM
            Arrive.Flip -> if (o < 0.5f) ORDER_CENTER else ORDER_LTR
            Arrive.Stretch -> ORDER_CENTER
            Arrive.Bounce -> if (o < 0.5f) ORDER_RANDOM else ORDER_LTR
            else -> ORDER_LTR
        }
        curOffX = offX(li, wi)
        curOffY = offY(li, wi)
        curTilt = if (rand(li, wi, 73) < 0.55f) (rand(li, wi, 74) - 0.5f) * 7f else 0f
        curSide = if (rand(li, wi, 75) < 0.5f) -1f else 1f
        curThuds = 0
        for (i in 0 until min(n, MAX_GLYPHS)) {
            r1[i] = rand(li, wi, i, 1)
            r2[i] = rand(li, wi, i, 2)
            r3[i] = rand(li, wi, i, 3)
            r4[i] = rand(li, wi, i, 4)
            r5[i] = rand(li, wi, i, 5)
        }
        // A small push on every word; smaller when they come fast, so rap doesn't turn into a shake.
        punch(s.clockMs, 0.014f * (avail / 450f).coerceIn(0.35f, 1f), 0f)
    }

    private fun chooseArrive(li: Int, wi: Int, n: Int, avail: Long): Arrive {
        if (n > LETTER_CAP) return Arrive.Punch
        arriveCount = 0
        when {
            curFinale -> {
                addArrive(Arrive.Punch, avail, n)
                addArrive(Arrive.Slam, avail, n)
            }
            curHeld -> {
                addArrive(Arrive.Punch, avail, n)
                addArrive(Arrive.Slam, avail, n)
                addArrive(Arrive.Stretch, avail, n)
                addArrive(Arrive.Bounce, avail, n)
            }
            wi == 0 -> {
                // A line opens with a big arrival.
                addArrive(Arrive.Assemble, avail, n)
                addArrive(Arrive.Bounce, avail, n)
                addArrive(Arrive.Stretch, avail, n)
                addArrive(Arrive.Slam, avail, n)
                addArrive(Arrive.Rise, avail, n)
            }
            else -> for (a in Arrive.entries) if (a != Arrive.Punch) addArrive(a, avail, n)
        }
        if (arriveCount == 0) addArrive(Arrive.Rise, avail, n)
        var k = (rand(li, wi, 91) * arriveCount).toInt().coerceIn(0, arriveCount - 1)
        if (arrivePool[k] == lastArrive && arriveCount > 1) k = (k + 1) % arriveCount
        return arrivePool[k] ?: Arrive.Rise
    }

    private fun addArrive(a: Arrive, avail: Long, n: Int) {
        if (avail < a.needMs || (a == Arrive.Type && n > 10)) return
        arrivePool[arriveCount++] = a
    }

    private fun chooseLeave(toNone: Boolean, lineChange: Boolean): Leave {
        leaveCount = 0
        when {
            // Into a break: something unhurried.
            toNone -> {
                addLeave(Leave.Zoom)
                addLeave(Leave.Fall)
                addLeave(Leave.Split)
            }
            // The line's over: its row is going too, so no flying into it.
            lineChange -> {
                addLeave(Leave.Explode)
                addLeave(Leave.Zoom)
                addLeave(Leave.Split)
                addLeave(Leave.Fall)
            }
            else -> {
                addLeave(Leave.Explode)
                addLeave(Leave.Fall)
                addLeave(Leave.Zoom)
                addLeave(Leave.Split)
                // Twice: going up into the sentence is the natural way out mid-line.
                addLeave(Leave.ToRow)
                addLeave(Leave.ToRow)
            }
        }
        var k = (rand(curLine, curWord, 61) * leaveCount).toInt().coerceIn(0, leaveCount - 1)
        if (leavePool[k] == lastLeave && leaveCount > 1) k = (k + 1) % leaveCount
        return leavePool[k] ?: Leave.Zoom
    }

    private fun addLeave(l: Leave) {
        if (curWhole && l.perLetter) return
        leavePool[leaveCount++] = l
    }

    private fun pushLeaving(s: SceneContext, how: Leave, dur: Float) {
        val a = leaving[0]
        val b = leaving[1]
        val l = when {
            !a.active -> a
            !b.active -> b
            a.startMs <= b.startMs -> a
            else -> b
        }
        l.active = true
        l.sprite = curSprite
        l.line = curLine
        l.word = curWord
        l.how = how
        l.startMs = s.positionMs
        l.dur = dur
        l.cx = wordCx
        l.cy = wordCy
        l.scale = drawnScale
        l.rot = drawnRot
        l.side = curSide
        l.iconX = drawnIconX
        l.iconY = drawnIconY
        l.hasTarget = false
        val n = min(curSprite?.glyphCount ?: 0, MAX_GLYPHS)
        for (i in 0 until n) {
            l.r1[i] = rand(curLine, curWord, i, 51)
            l.r2[i] = rand(curLine, curWord, i, 52)
            l.r3[i] = rand(curLine, curWord, i, 53)
        }
    }

    private fun layout(s: SceneContext) {
        if (curWord < 0) return
        val sp = s.sprite(curLine, curWord) ?: curSprite ?: return
        curSprite = sp
        val w = s.width
        val h = s.height
        val icon = sp.icon
        val iconW = if (icon != null) icon.width + sp.height * ICON_GAP else 0f
        // The fit size (a touch over), never wider than the screen allows.
        baseScale = min(BASE_SCALE, w * 0.86f / (sp.width + iconW))
        val cx = w / 2f + curOffX * w
        wordCx = when {
            icon == null -> cx
            sp.iconLeft -> cx + iconW * baseScale / 2f
            else -> cx - iconW * baseScale / 2f
        }
        // Alone it sits in the middle; with the row above, a little lower so the pair is centred.
        wordCy = h * (if (curWord > 0) ROW_WORD_Y else SOLO_WORD_Y) + curOffY * h
        rowTargetY = h * ROW_WORD_Y - 0.33f * sp.height * baseScale - h * ROW_HEIGHT * 1.05f
    }

    // ---- Camera ---------------------------------------------------------------------------------------------------

    /** Each line is seen from its own angle; neighbours lean opposite ways. */
    private fun aimLine(li: Int, width: Float) {
        val side = if (li % 2 == 0) 1f else -1f
        poseRx = -3f + 9f * rand(li, 81)
        poseRy = side * (4f + 9f * rand(li, 82))
        poseRz = (rand(li, 83) - 0.5f) * 5f
        poseZoom = 0.985f + 0.05f * rand(li, 84)
        poseShift = (rand(li, 85) - 0.5f) * width * 0.03f
    }

    private fun aimGap() {
        poseRx = 4f
        poseRy = 0f
        poseRz = 0f
        poseZoom = 0.96f
        poseShift = 0f
    }

    private fun steer(s: SceneContext) {
        val now = s.clockMs
        val dp = s.density
        val t = ambient
        // Strong beats: a shake, a small punch and a faint flash, at most a couple of times a second.
        val beat = s.beat
        if (beat > BEAT_KICK && lastBeat <= BEAT_KICK && now - beatAt > 380) {
            beatAt = now
            flash(now, 0.07f * beat)
            shake(now, 3.5f * dp * beat)
            punch(now, 0.012f * beat, 0f)
        }
        lastBeat = beat

        val calm = if (s.playing) 1f else 0.5f
        val idle = if (curWord < 0) 1f else 0f
        // The operator re-frames a little towards each word's offset.
        val followX = if (curWord >= 0) -curOffX * s.width * 0.25f else 0f
        val followY = if (curWord >= 0) -curOffY * s.height * 0.25f else 0f
        val rotX = camRx.update(poseRx, now) + calm * (1.1f * sin(t * 0.37f) + 0.4f * sin(t * 0.93f + 1f))
        val rotY = camRy.update(poseRy, now) + calm * (1.5f * sin(t * 0.29f + 2f) + 0.5f * sin(t * 0.81f)) + idle * 5f * sin(t * 0.33f)
        var rotZ = camRz.update(poseRz, now) + calm * 0.6f * sin(t * 0.21f + 0.5f)
        var zoom = camZoom.update(poseZoom, now)
        var shiftX = camSx.update(poseShift + followX, now) + calm * 5f * dp * sin(t * 0.43f + 1.7f)
        var shiftY = camSy.update(followY, now) + calm * 4f * dp * sin(t * 0.34f + 0.3f)

        val age = (now - punchAt).toFloat()
        val carry = exp(-age / PUNCH_DECAY)
        val pk = kick(age, PUNCH_ATTACK, PUNCH_DECAY)
        zoom += carryZoom * carry + punchZoom * pk
        rotZ += carryRot * carry + punchRot * pk

        val sa = (now - shakeAt).toFloat()
        if (sa < 1_200f) {
            val e = shakeAmp * exp(-sa / SHAKE_DECAY)
            shiftX += e * sin(sa * 0.09f)
            shiftY += e * cos(sa * 0.077f + 1f)
            rotZ += e / dp * 0.07f * sin(sa * 0.063f)
        }
        val cam = s.camera
        cam.rotX = rotX
        cam.rotY = rotY
        cam.rotZ = rotZ
        cam.zoom = zoom
        cam.shiftX = shiftX
        cam.shiftY = shiftY
    }

    /** A camera punch-in. Stacks onto whatever is still ringing, so nothing jumps. */
    private fun punch(now: Long, zoom: Float, rot: Float) {
        val age = (now - punchAt).toFloat()
        val carry = exp(-age / PUNCH_DECAY)
        val pk = kick(age, PUNCH_ATTACK, PUNCH_DECAY)
        carryZoom = carryZoom * carry + punchZoom * pk
        carryRot = carryRot * carry + punchRot * pk
        punchAt = now
        punchZoom = zoom
        punchRot = rot
    }

    private fun shake(now: Long, amp: Float) {
        shakeAmp = max(amp, shakeAmp * exp(-(now - shakeAt) / SHAKE_DECAY))
        shakeAt = now
    }

    private fun flash(now: Long, amp: Float) {
        flashAmp = max(amp, flashAmp * exp(-(now - flashAt) / FLASH_DECAY))
        flashAt = now
    }

    /** Emphasis lands: sparks, a punch, a flash. The line's last word throws sparks off both ends. */
    private fun land(s: SceneContext, cx: Float, cy: Float, halfWidth: Float) {
        val now = s.clockMs
        val dp = s.density
        val seed = curLine * 131 + curWord * 7
        if (curFinale) {
            s.effects.burst(cx - halfWidth * 0.8f, cy, burstColors, dp * 1.15f, seed)
            s.effects.burst(cx + halfWidth * 0.8f, cy, burstColors, dp * 1.15f, seed + 1)
            punch(now, 0.085f, 3.2f * curSide)
            shake(now, 11f * dp)
            flash(now, 0.16f)
        } else {
            s.effects.burst(cx, cy, burstColors, dp, seed)
            punch(now, 0.05f, 1.6f * curSide)
            shake(now, 6f * dp)
            flash(now, 0.1f)
        }
    }

    private fun landKey(s: SceneContext): Long =
        (System.identityHashCode(s.lyrics).toLong() shl 32) or ((curLine.toLong() and 0xFFFFF) shl 12) or ((curWord.toLong() and 0x3FF) shl 2) or 2L

    // ---- Drawing --------------------------------------------------------------------------------------------------

    private fun drawFlash(c: Canvas, s: SceneContext) {
        val a = flashAmp * exp(-(s.clockMs - flashAt) / FLASH_DECAY)
        if (a < 0.004f) return
        val w = s.width
        val h = s.height
        fill.color = s.ink.primary
        fill.alpha = (255 * a.coerceAtMost(1f)).toInt()
        // Oversized: the camera turns the layer, the flash must still cover the screen.
        c.drawRect(-w * 0.3f, -h * 0.3f, w * 1.3f, h * 1.3f, fill)
    }

    /** Intro and breaks: soft dots breathing, a count-in, and a glimpse of the word that's coming. */
    private fun drawWaiting(c: Canvas, s: SceneContext) {
        if (curWord >= 0) return
        val next = s.line(waitLine) ?: return
        val remaining = (next.startMs - s.positionMs).toFloat()
        if (remaining <= 0f) return
        val ink = s.ink
        val w = s.width
        val h = s.height
        val fadeIn = ((s.clockMs - noneSince) / 500f).coerceIn(0f, 1f)

        if (remaining < TEASE_MS) {
            val sp = s.sprite(waitLine, 0)
            if (sp != null) {
                val k = 1f - remaining / TEASE_MS
                // Gone just before the entrance takes over.
                val a = 0.17f * easeOutCubic(k) * (remaining / 260f).coerceAtMost(1f)
                val scale = min(BASE_SCALE, w * 0.86f / sp.width) * (0.8f + 0.14f * k)
                drawWhole(c, sp, w / 2f + teaseOffX * w, h * SOLO_WORD_Y + teaseOffY * h, scale, 0f, ink.light, a)
            }
        }

        val r = h * 0.021f
        val cy = h * 0.5f
        for (k in 0..2) {
            // Count-in: they pop at 1.5, 1.0 and 0.5 s before the line.
            val popAge = (COUNT_MS - k * COUNT_MS / 3f) - remaining
            if (popAge > POP_MS) continue
            val phase = sin(ambient * 3.1f - k * 0.9f)
            var radius = r * (1f + 0.2f * phase + 0.3f * s.beat)
            var a = (0.6f + 0.3f * phase) * fadeIn
            if (popAge > 0f) {
                val q = popAge / POP_MS
                radius *= 1f + 1.1f * easeOutCubic(q)
                a *= 1f - q
            }
            if (a <= 0.01f) continue
            val x = w / 2f + (k - 1) * r * 4.4f
            // A halo of two faint discs makes the dot glow without a blur.
            fill.color = ink.primary
            fill.alpha = (255 * a * 0.12f).toInt()
            c.drawCircle(x, cy, radius * 2.4f, fill)
            fill.alpha = (255 * a * 0.26f).toInt()
            c.drawCircle(x, cy, radius * 1.6f, fill)
            fill.color = ink.light
            fill.alpha = (255 * a).toInt()
            c.drawCircle(x, cy, radius, fill)
        }
    }

    /** The line so far, small, above the big word: words slide left as new ones join, the oldest falls off. */
    private fun drawRow(c: Canvas, s: SceneContext) {
        drawOldRow(c, s)
        rowCount = 0
        if (curLine < 0 || curWord < 1) return
        val pos = s.positionMs
        val now = s.clockMs
        val rowH = s.height * ROW_HEIGHT
        val gap = rowH * 0.32f
        val first = max(0, curWord - 3)
        var total = 0f
        var count = 0
        for (j in first until curWord) {
            val sp = s.sprite(curLine, j) ?: continue
            total += sp.width * rowScale(sp, rowH)
            count++
        }
        if (count == 0) return
        total += gap * (count - 1)
        val y = rowY.update(rowTargetY, now)
        rowDrawnY = y
        val enter = ((pos - rowChangedAt) / 220f).coerceIn(0f, 1f)
        val color = s.ink.secondary
        var x = s.width / 2f - total / 2f

        val dropped = curWord - 4
        if (dropped >= 0 && enter < 1f) {
            val sp = s.sprite(curLine, dropped)
            if (sp != null) {
                val rs = rowScale(sp, rowH)
                drawRowWord(c, sp, slot(dropped, x - gap - sp.width * rs / 2f, now), y, rs, (ROW_ALPHA - 0.6f) * (1f - enter), color)
            }
        }
        for (j in first until curWord) {
            val sp = s.sprite(curLine, j) ?: continue
            val rs = rowScale(sp, rowH)
            val ww = sp.width * rs
            val px = slot(j, x + ww / 2f, now)
            x += ww + gap
            var a = ROW_ALPHA - 0.2f * (curWord - 1 - j)
            val flight = flightTo(s, j)
            if (flight != null) {
                // Still flying up here: steer it to this spot, and show it only once it has arrived.
                flight.hasTarget = true
                flight.tx = px
                flight.ty = y
                flight.ts = rs
                flight.ta = a
                a = 0f
            } else if (j == curWord - 1) {
                // The word that just left the big slot fades in.
                a *= enter
            }
            if (a > 0.01f) drawRowWord(c, sp, px, y, rs, a, color)
            if (rowCount < rowSprite.size) {
                rowSprite[rowCount] = sp
                rowX[rowCount] = px
                rowS[rowCount] = rs
                rowA[rowCount] = a
                rowCount++
            }
        }
    }

    private fun drawOldRow(c: Canvas, s: SceneContext) {
        if (oldCount == 0) return
        val k = (s.clockMs - oldAt) / OLD_ROW_MS
        if (k >= 1f) {
            for (i in 0 until oldCount) oldSprite[i] = null
            oldCount = 0
            return
        }
        val rise = easeOutCubic(k) * s.height * ROW_HEIGHT * 0.9f
        for (i in 0 until oldCount) {
            val sp = oldSprite[i] ?: continue
            drawRowWord(c, sp, oldX[i], oldY - rise, oldS[i] * (1f - 0.1f * k), oldA[i] * (1f - k) * (1f - k), s.ink.secondary)
        }
    }

    private fun snapshotRow(s: SceneContext) {
        oldCount = rowCount
        for (i in 0 until rowCount) {
            oldSprite[i] = rowSprite[i]
            oldX[i] = rowX[i]
            oldS[i] = rowS[i]
            oldA[i] = rowA[i]
        }
        oldY = rowDrawnY
        oldAt = s.clockMs
    }

    private fun resetSlots() {
        for (i in slotWord.indices) slotWord[i] = -1
    }

    /** Word [j]'s x in the row, sliding on its slot's spring; a word new to the row starts where it belongs. */
    private fun slot(j: Int, target: Float, now: Long): Float {
        val k = j and 7
        if (slotWord[k] != j) {
            slotWord[k] = j
            slotX[k].reset(target)
        }
        return slotX[k].update(target, now)
    }

    /** The ToRow exit still on its way to row word [word] of the current line, if any. */
    private fun flightTo(s: SceneContext, word: Int): Leaving? {
        for (l in leaving) {
            if (l.active && l.how == Leave.ToRow && l.line == curLine && l.word == word && s.positionMs - l.startMs < l.dur) return l
        }
        return null
    }

    private fun drawRowWord(c: Canvas, sp: WordSprite, x: Float, y: Float, scale: Float, alpha: Float, color: Int) {
        if (alpha <= 0.01f) return
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        paint.color = color
        paint.alpha = (255 * alpha.coerceAtMost(1f)).toInt()
        drawWord(c, sp, -sp.width / 2f, -sp.height / 2f, paint)
        c.restore()
    }

    private fun drawLeaving(c: Canvas, s: SceneContext) {
        val pos = s.positionMs
        for (l in leaving) {
            if (!l.active) continue
            val sp = l.sprite
            val t = (pos - l.startMs) / l.dur
            // Done, or the song was sought back past its start.
            if (sp == null || t >= 1f || t < -0.5f) {
                l.active = false
                l.sprite = null
                continue
            }
            val k = t.coerceAtLeast(0f)
            drawExit(c, s, l, sp, k)
            val icon = sp.icon
            if (icon != null) {
                val q = 1f - easeOutCubic((k * 2.5f).coerceAtMost(1f))
                drawIcon(c, s, icon, l.iconX, l.iconY, l.scale * q, q, 0f)
            }
        }
    }

    private fun drawExit(c: Canvas, s: SceneContext, l: Leaving, sp: WordSprite, t: Float) {
        val ink = s.ink
        when (l.how) {
            Leave.Explode, Leave.Fall -> {
                val n = min(sp.glyphCount, MAX_GLYPHS)
                poseExit(l, sp, n, t, s)
                drawLetters(c, sp, n, l.cx, l.cy, l.scale, l.rot, 1f, Float.MAX_VALUE, ink.primary, ink.primary, 0, 0f, false)
            }
            Leave.Zoom -> zoomOut(c, sp, l, t, ink.primary)
            Leave.Split -> splitOut(c, sp, l, t, ink)
            Leave.ToRow -> {
                if (!l.hasTarget) {
                    // Its place in the row never showed up (sprite not ready): just fly past.
                    zoomOut(c, sp, l, t, ink.primary)
                } else {
                    val e = easeInOutCubic(t)
                    val arc = sin(PI.toFloat() * e) * sp.height * l.scale * 0.25f
                    // If the line moved on mid-flight its row is gone: land where it was, fading.
                    val fade = if (l.line != curLine) 1f - t else 1f
                    drawWhole(
                        c, sp,
                        lerp(l.cx, l.tx, e), lerp(l.cy, l.ty, e) - arc,
                        lerp(l.scale, l.ts, e), lerp(l.rot, 0f, e),
                        mix(ink.primary, ink.secondary, e), lerp(1f, l.ta, e) * fade,
                    )
                }
            }
        }
    }

    /** Flies past the camera: grows faster and faster as it fades, a fainter echo a step behind. */
    private fun zoomOut(c: Canvas, sp: WordSprite, l: Leaving, t: Float, color: Int) {
        val k = t * t
        val a = (1f - t).pow(1.6f)
        drawWhole(c, sp, l.cx, l.cy, l.scale * (1f + 1.2f * k), l.rot, color, a * 0.35f)
        drawWhole(c, sp, l.cx, l.cy + k * sp.height * 0.1f, l.scale * (1f + 2.4f * k), l.rot + 10f * l.side * k, color, a * 0.95f)
    }

    /** Sliced through the middle: a bright cut runs across, then the halves slide apart. */
    private fun splitOut(c: Canvas, sp: WordSprite, l: Leaving, t: Float, ink: StageInk) {
        val sw = sp.width.toFloat()
        val sh = sp.height.toFloat()
        val sep = 0.06f * (t * 8f).coerceAtMost(1f) + t * t
        val a = (1f - t).pow(1.2f)
        val cut = (sh * 0.56f).toInt()
        enterWord(c, l.cx, l.cy, l.scale, l.rot, sw, sh)
        paint.color = ink.primary
        paint.alpha = (255 * a).toInt()
        piece(c, sp.text, 0, 0, sp.width, cut, -sep * sw * 0.18f * l.side, -sep * sh * 0.9f)
        piece(c, sp.text, 0, cut, sp.width, sp.height, sep * sw * 0.18f * l.side, sep * sh * 0.9f)
        val blade = 1f - t * 3f
        if (blade > 0f) {
            fill.color = ink.light
            fill.alpha = (255 * blade).toInt()
            val reach = -sw * 0.1f + sw * 1.2f * (t * 6f).coerceAtMost(1f)
            c.drawRect(-sw * 0.1f, cut - sh * 0.012f, reach, cut + sh * 0.012f, fill)
        }
        c.restore()
    }

    private fun drawCurrent(c: Canvas, s: SceneContext) {
        if (curWord < 0) return
        val sp = curSprite ?: return
        val w = s.line(curLine)?.words?.getOrNull(curWord) ?: return
        val ink = s.ink
        val pos = s.positionMs
        val since = (pos - curAppear).toFloat().coerceAtLeast(0f)
        val n = min(sp.glyphCount, MAX_GLYPHS)
        val sw = sp.width.toFloat()
        val sh = sp.height.toFloat()
        val p = wordProgress(w, pos)

        poseEntrance(sp, n, since, s)
        poseSung(sp, n, w, pos, s.level)

        // Held notes keep growing while held; finales stand a bit bigger. Everything breathes with the beat.
        var grow = 1f
        if (curHeld) grow += 0.09f * easeOutCubic(p)
        if (curFinale) grow += 0.04f
        val scale = min(baseScale * grow, MAX_SCALE) * (1f + 0.022f * s.beat) * wMul
        val rot = curTilt + wRot
        val cx = wordCx
        val cy = wordCy
        drawnScale = scale
        drawnRot = rot
        val landAt = curArrive.landAt * curDur

        if (curArrive == Arrive.Punch && since < curDur) {
            // Echoes of the word still rushing in from in front of the camera.
            val k = 1f - easeOutExpo(since / curDur)
            for (g in 3 downTo 1) {
                drawWhole(c, sp, cx, cy, scale * (1f + g * 0.22f * k), rot + g * 4f * curSide * k, ink.primary, 0.3f * k * (1f - g * 0.22f))
            }
        }

        val litX = if (pos < w.startMs) -1f else p * sw
        if (curWhole) {
            drawWholeSung(c, sp, cx, cy, scale, rot, litX, ink)
        } else {
            // Emphasis turns solid once it has hit (a short extrusion); other words get a plain drop shadow.
            val depth = if ((curHeld || curFinale) && n <= 12) 3 else 1
            val depthK = if (depth > 1) easeOutBack(((since - landAt) / 260f).coerceIn(0f, 1f), 2f) else 1f
            drawLetters(c, sp, n, cx, cy, scale, rot, wAlpha, litX, ink.light, ink.primary, depth, depthK, wMask)
        }
        if (curArrive == Arrive.Type) drawCursor(c, s, sp, n, cx, cy, scale, rot, since)
        if (curArrive == Arrive.Rise) drawMaskLine(c, sp, cx, cy, scale, rot, since, ink.primary)

        val icon = sp.icon
        if (icon != null) {
            val off = sw * scale / 2f + (sh * ICON_GAP + icon.width / 2f) * scale
            val ix = if (sp.iconLeft) cx - off else cx + off
            val iy = cy + sin(ambient * 2.1f) * sh * 0.025f * scale
            drawnIconX = ix
            drawnIconY = iy
            val k = easeOutBack(((since - 120f) / 380f).coerceIn(0f, 1f), 1.8f)
            drawIcon(c, s, icon, ix, iy, scale * k, wAlpha, sin(ambient * 1.7f) * 6f)
        }

        if ((curHeld || curFinale) && since >= landAt && since < landAt + 500f && s.effects.landOnce(landKey(s))) {
            land(s, cx, cy, sw * scale / 2f)
        }
        // Slammed letters and typed keys each knock the camera a little.
        if ((curArrive == Arrive.Slam || curArrive == Arrive.Type) && curDur >= 260f && since < curDur + 60f && poseHits > curThuds) {
            curThuds = poseHits
            shake(s.clockMs, (if (curArrive == Arrive.Slam) 3f else 1.4f) * s.density)
        }
    }

    // ---- Letter choreography --------------------------------------------------------------------------------------

    private fun resetPose(n: Int) {
        for (i in 0 until n) {
            lx[i] = 0f
            ly[i] = 0f
            lsx[i] = 1f
            lsy[i] = 1f
            lrot[i] = 0f
            la[i] = 1f
            lpy[i] = 0.5f
        }
    }

    /** When letter [i] of [n] starts, as a share of the stagger: left to right, right to left, centre out, shuffled. */
    private fun delayFrac(i: Int, n: Int): Float {
        if (n <= 1) return 0f
        val f = i / (n - 1f)
        return when (curOrder) {
            ORDER_RTL -> 1f - f
            ORDER_CENTER -> abs(f - 0.5f) * 2f
            ORDER_RANDOM -> r5[i]
            else -> f
        }
    }

    /** The current word's entrance at [since] ms: letter poses, plus the whole-word parts (wMul, wRot, wAlpha). */
    private fun poseEntrance(sp: WordSprite, n: Int, since: Float, s: SceneContext) {
        resetPose(n)
        wMul = 1f
        wRot = 0f
        wAlpha = 1f
        wMask = false
        val dur = curDur
        // Typed letters finish their little pop after the last key.
        val end = if (curArrive == Arrive.Type) dur + 90f else dur
        if (since >= end) {
            poseHits = n
            return
        }
        val t = since / dur
        val sw = sp.width.toFloat()
        val sh = sp.height.toFloat()
        poseHits = 0
        when (curArrive) {
            Arrive.Punch -> {
                val k = easeOutExpo(t)
                wMul = 1f + 1.7f * (1f - k)
                wRot = (1f - k) * -11f * curSide
                wAlpha = t.coerceAtMost(1f).pow(0.35f)
                return
            }
            Arrive.Type -> {
                val step = dur / n.coerceAtLeast(1)
                for (i in 0 until n) {
                    val at = i * step
                    if (since < at) {
                        la[i] = 0f
                        continue
                    }
                    poseHits++
                    // Each key lands a touch big, high and askew, and settles: a typewriter's misprint.
                    val k = easeOutCubic(((since - at) / 90f).coerceAtMost(1f))
                    lsx[i] = 1f + 0.3f * (1f - k)
                    lsy[i] = lsx[i]
                    ly[i] = -(1f - k) * sh * 0.06f
                    lrot[i] = (r1[i] - 0.5f) * 14f * (1f - k)
                }
                return
            }
            else -> Unit
        }
        // Fast words compress the stagger more than the letters' own motion, so each letter still reads.
        val spread = dur * curArrive.spread * (if (dur < 260f) 0.6f else 1f)
        val each = (dur - spread).coerceAtLeast(1f)
        for (i in 0 until n) {
            val u = ((since - delayFrac(i, n) * spread) / each).coerceIn(0f, 1f)
            when (curArrive) {
                Arrive.Slam -> {
                    // Falls big out of the camera, accelerating, then squashes flat on impact and wobbles back.
                    lpy[i] = BASELINE
                    if (u < SLAM_HIT) {
                        val k = u / SLAM_HIT
                        val e = k * k
                        val sc = 2.4f - 1.4f * e
                        lsx[i] = sc
                        lsy[i] = sc
                        ly[i] = -(1f - e) * sh * 0.45f
                        lrot[i] = (r1[i] - 0.5f) * 30f * (1f - e)
                        la[i] = (k * 2.5f).coerceAtMost(1f)
                    } else {
                        poseHits++
                        val v = (u - SLAM_HIT) / (1f - SLAM_HIT)
                        val d = exp(-5.5f * v) * cos(v * 11f)
                        lsy[i] = 1f - 0.34f * d
                        lsx[i] = 1f + 0.22f * d
                    }
                }
                Arrive.Assemble -> {
                    // From anywhere on (or off) screen, spinning, odd-sized; snaps together.
                    val k = easeOutExpo(u)
                    val ang = r1[i] * 2f * PI.toFloat()
                    val dist = (0.35f + 0.35f * r2[i]) * s.width
                    lx[i] = cos(ang) * dist * (1f - k)
                    ly[i] = sin(ang) * dist * 0.55f * (1f - k)
                    lrot[i] = (r3[i] - 0.5f) * 520f * (1f - k)
                    val sc0 = 0.35f + 1.5f * r4[i]
                    lsx[i] = sc0 + (1f - sc0) * k
                    lsy[i] = lsx[i]
                    la[i] = (u * 3f).coerceAtMost(1f)
                }
                Arrive.Flip -> {
                    // Hinged on the baseline, flips up past upright and settles.
                    lpy[i] = BASELINE
                    val k = easeOutBack(u, 2.4f)
                    lsy[i] = k.coerceAtLeast(0f)
                    lsx[i] = 1f + 0.18f * (1f - k).coerceAtLeast(0f)
                    la[i] = (u * 5f).coerceAtMost(1f)
                }
                Arrive.Stretch -> {
                    // Starts spread wide; the middle springs home first, the ends catch up, overshooting inward.
                    lpy[i] = BASELINE
                    val k = easeOutElastic(u)
                    val wide = (s.width * 1.05f / (sw * baseScale) - 1f).coerceIn(0.3f, 1.8f)
                    lx[i] = (glyphCenter(sp, i) - sw / 2f) * wide * (1f - k)
                    lsx[i] = (1f + 1.1f * (1f - k)).coerceAtLeast(0.55f)
                    lsy[i] = 1f - 0.3f * (1f - k)
                    la[i] = (u * 4f).coerceAtMost(1f)
                }
                Arrive.Bounce -> {
                    // Dropped from above the screen; squashes on every contact.
                    lpy[i] = BASELINE
                    val b = easeOutBounce(u)
                    ly[i] = -(1f - b) * s.height * 0.6f
                    lrot[i] = (r1[i] - 0.5f) * 40f * (1f - b)
                    val q = max(contact(u, 0.3636f, 1f), max(contact(u, 0.7273f, 0.5f), contact(u, 0.9091f, 0.25f))) * ((1f - u) * 8f).coerceAtMost(1f)
                    lsy[i] = 1f - 0.3f * q
                    lsx[i] = 1f + 0.2f * q
                    la[i] = (u * 6f).coerceAtMost(1f)
                }
                Arrive.Spin -> {
                    // Grows out of its own centre, spinning, with a little overshoot.
                    val k = easeOutBack(u, 1.7f).coerceAtLeast(0f)
                    lsx[i] = k
                    lsy[i] = k
                    lrot[i] = (1f - easeOutCubic(u)) * (280f + 80f * r1[i]) * curSide
                    la[i] = (u * 3f).coerceAtMost(1f)
                }
                Arrive.Rise -> {
                    // Up from behind the line under the word (the mask hides what's still below it).
                    wMask = true
                    ly[i] = (1f - easeOutBack(u, 1.2f)) * sh
                }
                else -> Unit
            }
        }
    }

    /** Squash after a bounce contact at [at]. */
    private fun contact(u: Float, at: Float, amp: Float) = if (u < at) 0f else amp * exp(-(u - at) * 28f)

    /** On top of the entrance while sung: each letter hops as the fill reaches it; held notes tremble. */
    private fun poseSung(sp: WordSprite, n: Int, w: LyricWord, pos: Long, level: Float) {
        if (pos < w.startMs || pos > w.endMs + 500) return
        val sh = sp.height.toFloat()
        val sw = sp.width.toFloat()
        val len = (w.endMs - w.startMs).coerceAtLeast(1)
        for (i in 0 until n) {
            val at = w.startMs + (sp.glyphLeft[i] / sw * len).toLong()
            val k = kick((pos - at).toFloat(), 50f, 170f)
            if (k > 0.002f) {
                ly[i] -= sh * 0.045f * k
                lsx[i] *= 1f + 0.07f * k
                lsy[i] *= 1f + 0.07f * k
            }
        }
        if (curHeld && pos <= w.endMs + 150) {
            val v = ((pos - w.startMs) / 300f).coerceIn(0f, 1f) * (0.6f + 0.8f * level)
            val ph = (pos % 1_000_000L).toFloat()
            for (i in 0 until n) {
                ly[i] += sin(ph * 0.11f + i * 1.7f) * sh * 0.01f * v
                lrot[i] += sin(ph * 0.083f + i * 2.3f) * 1.6f * v
            }
        }
    }

    /** A leaving word's letters, [t] 0..1 through its exit. */
    private fun poseExit(l: Leaving, sp: WordSprite, n: Int, t: Float, s: SceneContext) {
        resetPose(n)
        val sw = sp.width.toFloat()
        val sh = sp.height.toFloat()
        // Offsets are in sprite pixels; the screen in those units.
        val w = s.width / l.scale
        val h = s.height / l.scale
        for (i in 0 until n) {
            if (l.how == Leave.Explode) {
                // Outward from the middle (outer letters furthest), mostly upward, spinning; gravity at the end.
                val lt = ((t - l.r1[i] * 0.15f) / 0.85f).coerceIn(0f, 1f)
                val k = easeOutCubic(lt)
                val rel = (glyphCenter(sp, i) - sw / 2f) / sw
                val vx = rel * 2.4f + (l.r2[i] - 0.5f) * 0.9f
                val vy = (l.r3[i] - 0.62f) * 1.5f
                lx[i] = vx * k * w * 0.42f
                ly[i] = vy * k * h * 0.45f + lt * lt * h * 0.2f
                lrot[i] = (l.r2[i] - 0.5f) * 720f * k
                lsx[i] = 1f + 0.5f * k
                lsy[i] = lsx[i]
                la[i] = (1f - lt).pow(1.6f)
            } else {
                // Loose tiles: a small hop, then each drops under its own gravity, tumbling.
                val d = l.r1[i] * 0.18f
                val lt = ((t - d) / (1f - d)).coerceIn(0f, 1f)
                val g = 0.75f + 0.6f * l.r2[i]
                lpy[i] = BASELINE
                ly[i] = -0.5f * sh * lt + g * h * 1.1f * lt * lt
                lx[i] = (l.r3[i] - 0.5f) * sw * 0.35f * lt
                lrot[i] = (l.r3[i] - 0.5f) * 160f * lt
                la[i] = 0.85f * (1f - lt).pow(1.3f)
            }
        }
    }

    // ---- Drawing primitives ---------------------------------------------------------------------------------------

    /** Into the word's own space: origin at the sprite's top-left, around its centre ([cx], [cy]). Caller restores. */
    private fun enterWord(c: Canvas, cx: Float, cy: Float, scale: Float, rot: Float, sw: Float, sh: Float) {
        c.save()
        c.translate(cx, cy)
        if (rot != 0f) c.rotate(rot)
        c.scale(scale, scale)
        c.translate(-sw / 2f, -sh / 2f)
    }

    /**
     * The word letter by letter in the current poses. [litX]: sprite x the karaoke fill has reached (letters left of
     * it in [lit], right of it in [base], the one it's crossing split exactly). [depth]: 0 nothing underneath,
     * 1 a drop shadow, more an extrusion of that many layers pushed out by [depthK].
     */
    private fun drawLetters(
        c: Canvas,
        sp: WordSprite,
        n: Int,
        cx: Float,
        cy: Float,
        scale: Float,
        rot: Float,
        alpha: Float,
        litX: Float,
        base: Int,
        lit: Int,
        depth: Int,
        depthK: Float,
        mask: Boolean,
    ) {
        if (alpha <= 0.01f) return
        val sh = sp.height.toFloat()
        enterWord(c, cx, cy, scale, rot, sp.width.toFloat(), sh)
        for (d in depth downTo 1) {
            val ox: Float
            val oy: Float
            val a0: Float
            if (depth == 1) {
                ox = sh * 0.03f
                oy = sh * 0.05f
                a0 = SHADOW_ALPHA
                paint.color = shadowColor
            } else {
                ox = sh * 0.02f * d * depthK
                oy = sh * 0.028f * d * depthK
                a0 = 0.9f - 0.15f * d
                paint.color = extrudeColor
            }
            for (i in 0 until n) {
                val a = la[i] * alpha * a0
                if (a < 0.01f || skipGlyph(sp, i)) continue
                paint.alpha = (255 * a).toInt()
                drawLetter(c, sp, i, ox, oy, Float.MAX_VALUE, mask)
            }
        }
        for (i in 0 until n) {
            val a = la[i] * alpha
            if (a < 0.01f || skipGlyph(sp, i)) continue
            val l = sp.glyphLeft[i]
            val r = sp.glyphRight[i]
            val k = ((litX - l) / (r - l).coerceAtLeast(1f)).coerceIn(0f, 1f)
            val alpha255 = (255 * a.coerceAtMost(1f)).toInt()
            if (k < 1f) {
                paint.color = base
                paint.alpha = alpha255
                drawLetter(c, sp, i, 0f, 0f, Float.MAX_VALUE, mask)
            }
            if (k > 0f) {
                paint.color = lit
                paint.alpha = alpha255
                drawLetter(c, sp, i, 0f, 0f, litX, mask)
            }
        }
        c.restore()
    }

    /**
     * Letter [i] in its pose, offset by ([ox], [oy]). [cutRight]: sprite x where it stops (the karaoke edge).
     * [mask]: nothing below the mask line shows (the rise entrance), cut from the source so no clip is needed.
     */
    private fun drawLetter(c: Canvas, sp: WordSprite, i: Int, ox: Float, oy: Float, cutRight: Float, mask: Boolean) {
        val h = sp.height
        val l = sp.glyphLeft[i].toInt().coerceIn(0, sp.width)
        var r = ceil(sp.glyphRight[i]).toInt().coerceIn(l, sp.width)
        if (cutRight < r) r = ceil(cutRight).toInt().coerceIn(l, r)
        if (r <= l) return
        var bottom = h
        if (mask) {
            bottom = (h * MASK_Y - ly[i]).toInt().coerceIn(0, h)
            if (bottom <= 0) return
        }
        val px = (sp.glyphLeft[i] + sp.glyphRight[i]) / 2f
        val py = h * lpy[i]
        c.save()
        c.translate(px + lx[i] + ox, py + ly[i] + oy)
        if (lrot[i] != 0f) c.rotate(lrot[i])
        if (lsx[i] != 1f || lsy[i] != 1f) c.scale(lsx[i], lsy[i])
        srcRect.set(l, 0, r, bottom)
        dstRect.set(l - px, -py, r - px, bottom - py)
        c.drawBitmap(sp.text, srcRect, dstRect, paint)
        c.restore()
    }

    /** A rectangle of the sprite at its place, shifted by ([dx], [dy]): halves of a sliced word. */
    private fun piece(c: Canvas, bitmap: Bitmap, l: Int, t: Int, r: Int, b: Int, dx: Float, dy: Float) {
        if (r <= l || b <= t) return
        srcRect.set(l, t, r, b)
        dstRect.set(l + dx, t + dy, r + dx, b + dy)
        c.drawBitmap(bitmap, srcRect, dstRect, paint)
    }

    private fun drawWhole(c: Canvas, sp: WordSprite, cx: Float, cy: Float, scale: Float, rot: Float, color: Int, alpha: Float) {
        if (alpha <= 0.01f || scale <= 0f) return
        c.save()
        c.translate(cx, cy)
        if (rot != 0f) c.rotate(rot)
        c.scale(scale, scale)
        paint.color = color
        paint.alpha = (255 * alpha.coerceAtMost(1f)).toInt()
        drawWord(c, sp, -sp.width / 2f, -sp.height / 2f, paint)
        c.restore()
    }

    /** Very long words, as one piece: shadow, base, and the karaoke fill as a wipe. */
    private fun drawWholeSung(c: Canvas, sp: WordSprite, cx: Float, cy: Float, scale: Float, rot: Float, litX: Float, ink: StageInk) {
        if (wAlpha <= 0.01f) return
        val sw = sp.width.toFloat()
        val sh = sp.height.toFloat()
        val a = (255 * wAlpha.coerceAtMost(1f)).toInt()
        c.save()
        c.translate(cx, cy)
        if (rot != 0f) c.rotate(rot)
        c.scale(scale, scale)
        paint.color = shadowColor
        paint.alpha = (a * SHADOW_ALPHA).toInt()
        drawWord(c, sp, -sw / 2f + sh * 0.03f, -sh / 2f + sh * 0.05f, paint)
        if (litX < sw) {
            paint.color = ink.light
            paint.alpha = a
            drawWord(c, sp, -sw / 2f, -sh / 2f, paint)
        }
        if (litX > 0f) {
            paint.color = ink.primary
            paint.alpha = a
            drawWordPart(c, sp, -sw / 2f, -sh / 2f, litX / sw, paint)
        }
        c.restore()
    }

    /** The typewriter's cursor: a solid album-colour block after the last key, blinking once the word is typed. */
    private fun drawCursor(c: Canvas, s: SceneContext, sp: WordSprite, n: Int, cx: Float, cy: Float, scale: Float, rot: Float, since: Float) {
        if (n == 0) return
        val step = curDur / n
        val typed = min(n, (since / step).toInt() + 1)
        val on = since < curDur || (s.clockMs / 530L) % 2L == 0L
        if (!on) return
        val sw = sp.width.toFloat()
        val sh = sp.height.toFloat()
        val x = sp.glyphRight[typed - 1] + sh * 0.04f
        enterWord(c, cx, cy, scale, rot, sw, sh)
        fill.color = s.ink.primary
        fill.alpha = (242 * wAlpha.coerceAtMost(1f)).toInt()
        c.drawRect(x, sh * 0.22f, x + sh * 0.1f, sh * 0.82f, fill)
        c.restore()
    }

    /** The rise entrance's line: shoots out from the middle, the letters come up from behind it, then it fades. */
    private fun drawMaskLine(c: Canvas, sp: WordSprite, cx: Float, cy: Float, scale: Float, rot: Float, since: Float, color: Int) {
        val e = easeOutExpo((since / (curDur * 0.55f)).coerceIn(0f, 1f))
        val a = 1f - ((since - curDur) / 260f).coerceIn(0f, 1f)
        if (e <= 0f || a <= 0f) return
        val sw = sp.width.toFloat()
        val sh = sp.height.toFloat()
        enterWord(c, cx, cy, scale, rot, sw, sh)
        fill.color = color
        fill.alpha = (242 * a).toInt()
        val half = sw * 0.54f * e
        c.drawRect(sw / 2f - half, sh * (MASK_Y - 0.03f), sw / 2f + half, sh * MASK_Y, fill)
        c.restore()
    }

    private fun drawIcon(c: Canvas, s: SceneContext, icon: Bitmap, x: Float, y: Float, scale: Float, alpha: Float, rot: Float) {
        if (scale <= 0.01f || alpha <= 0.01f) return
        val ip = s.ink.icon
        ip.alpha = (255 * alpha.coerceAtMost(1f)).toInt()
        c.save()
        c.translate(x, y)
        if (rot != 0f) c.rotate(rot)
        c.scale(scale, scale)
        c.drawBitmap(icon, -icon.width / 2f, -icon.height / 2f, ip)
        c.restore()
    }

    private companion object {
        const val MAX_GLYPHS = 32

        /** Longer words are animated as one piece, to stay inside the draw budget. */
        const val LETTER_CAP = 24

        /** Words show this much before they're sung. */
        const val LEAD_MS = 100L

        /** A pause between lines longer than this is an instrumental break: the word goes, the dots come. */
        const val GAP_MS = 3_500L
        const val LINGER_MS = 1_200L
        const val TEASE_MS = 2_400f
        const val COUNT_MS = 1_500f
        const val POP_MS = 220f
        const val HELD_MS = 650L
        const val HELD_ESTIMATED_MS = 1_100L

        /** The fit size, a touch over; growth stops at the most a sprite can be enlarged before it goes soft. */
        const val BASE_SCALE = 1.06f
        const val MAX_SCALE = 1.2f
        const val SOLO_WORD_Y = 0.5f
        const val ROW_WORD_Y = 0.56f

        /** Sentence row: sprite height as a share of the screen's, so every word in it is the same type size. */
        const val ROW_HEIGHT = 0.054f
        const val ROW_ALPHA = 0.9f
        const val OLD_ROW_MS = 380f
        const val ICON_GAP = 0.14f

        /** Roughly where the baseline sits in a sprite, as a share of its height: letters squash onto it. */
        const val BASELINE = 0.78f
        const val MASK_Y = 0.985f
        const val SLAM_HIT = 0.42f
        const val SHADOW_ALPHA = 0.5f
        const val BEAT_KICK = 0.7f
        const val PUNCH_ATTACK = 45f
        const val PUNCH_DECAY = 260f
        const val SHAKE_DECAY = 170f
        const val FLASH_DECAY = 120f
        const val BLACK = 0xFF000000.toInt()

        const val ORDER_LTR = 0
        const val ORDER_RTL = 1
        const val ORDER_CENTER = 2
        const val ORDER_RANDOM = 3

        /** A word's resting offset from the centre, as shares of the screen (the teaser uses it too). */
        fun offX(li: Int, wi: Int) = (rand(li, wi, 71) - 0.5f) * 0.06f
        fun offY(li: Int, wi: Int) = (rand(li, wi, 72) - 0.5f) * 0.035f

        fun rowScale(sp: WordSprite, rowH: Float) = min(1.15f, rowH / sp.height)

        fun skipGlyph(sp: WordSprite, i: Int) =
            glyphIsBlank(sp, i) || (i > 0 && sp.glyphLeft[i] == sp.glyphLeft[i - 1] && sp.glyphRight[i] == sp.glyphRight[i - 1])

        /** A hit's shape: a quick rise over [attack] ms, an exponential fall over [decay]. */
        fun kick(age: Float, attack: Float, decay: Float): Float = when {
            age < 0f -> 0f
            age < attack -> age / attack
            else -> exp(-(age - attack) / decay)
        }

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        fun easeInOutCubic(t: Float) = if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f

        fun mix(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            val aa = (a ushr 24) + (((b ushr 24) - (a ushr 24)) * u).toInt()
            val ar = ((a shr 16) and 0xFF) + ((((b shr 16) and 0xFF) - ((a shr 16) and 0xFF)) * u).toInt()
            val ag = ((a shr 8) and 0xFF) + ((((b shr 8) and 0xFF) - ((a shr 8) and 0xFF)) * u).toInt()
            val ab = (a and 0xFF) + (((b and 0xFF) - (a and 0xFF)) * u).toInt()
            return (aa shl 24) or (ar shl 16) or (ag shl 8) or ab
        }
    }
}
