package com.muffuq.os.stage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tunnel ("Tünel"): the camera flies forward through 3D space and the lyrics hang in it.
 *
 * The track. Every word of the song is a stop on one long flight path, [SPACING] focal lengths apart in depth
 * (a line change adds a little more distance, an instrumental break a lot more). How far along the track the
 * camera is, is a pure function of the song position ([progressAt]), shaped so each word reaches the reading
 * plane (depth = focal length: full size, near the middle, fully lit) exactly when it's sung: the camera brakes
 * into the word, hangs there drifting a little closer while it's sung, then hops on to the next one, surging past
 * the old word, which swells past the screen edges, bursts apart letter by letter and fades. Being a function of
 * the position, seeking and pausing need no special cases.
 *
 * The paths. Each line lays its words out along its own path, picked so neighbouring lines differ: a helix the
 * camera corkscrews along (the word being read is always upright), a corridor with words on alternating walls,
 * a rising staircase, a scattered cloud, a zipper above and below the axis. Every line's path is centred
 * somewhere else in space, so between lines the next path swings into view and the camera banks and yaws into
 * the turn (via [SceneContext.camera], on springs).
 *
 * The look. Words ahead are small, dim, tinted with the album colour and still a loose cloud of letters that
 * pulls together as they arrive; the word being read fills with light as it's sung (karaoke). Fast moves leave
 * ghost copies behind, a starfield of light streaks streams past the walls (faster on hops and kicks), held notes
 * and line endings hang bigger, punch in and throw sparks. Intros and instrumental breaks are a cruise through
 * the stars with the next line far ahead.
 */
class TunnelScene : Scene {
    // The song as a flight path, rebuilt when the lyrics change. Lateral positions are in screen widths, y down.
    private var track: Lyrics? = null
    private var count = 0
    /** When each word reaches the reading plane: its start, kept strictly increasing. */
    private var arrive = LongArray(0)
    private var sungMs = FloatArray(0)
    /** Each word's distance along the track, in stops. */
    private var stopAt = FloatArray(0)
    private var lineOf = IntArray(0)
    private var wordOf = IntArray(0)
    /** Held notes and line endings: hang bigger, punch in, burst. */
    private var emph = BooleanArray(0)
    private var wordX = FloatArray(0)
    private var wordY = FloatArray(0)
    /** The word's own roll in the world, degrees; on screen it's this minus the camera's roll. */
    private var wordRoll = FloatArray(0)
    /** Where the camera is, and how it's rolled, while each word is being read. */
    private var camX = FloatArray(0)
    private var camY = FloatArray(0)
    private var camRoll = FloatArray(0)
    /** Per interval after a word: the hop's length (short intervals), or the hold before a long break (> 0). */
    private var hopMs = FloatArray(0)
    private var holdMs = FloatArray(0)
    /** Share of the distance covered while hanging on the word: a slow push-in instead of a dead stop. */
    private var drift = FloatArray(0)
    private var introMs = 0f
    private var introDist = 0f

    // cameraAt()'s result, in fields so frames allocate nothing.
    private var outX = 0f
    private var outY = 0f
    private var outRoll = 0f

    // The stage camera moves on springs: weight in the turns, a snap in the beat kick.
    private val bank = Spring(4.5f)
    private val yaw = Spring(3.2f)
    private val pitch = Spring(3.2f)
    private val zoom = Spring(15f)
    private val starSpeed = Spring(7f)
    private var lastClock = 0L

    // The starfield: persistent particles on the tunnel wall, recycled to the far end when they pass.
    private val starX = FloatArray(STARS)
    private val starY = FloatArray(STARS)
    /** Depth in focal lengths. */
    private val starZ = FloatArray(STARS)
    private val starSize = FloatArray(STARS)
    private val starTint = IntArray(STARS)
    private var starSeed = 0
    private var starsReady = false

    // This frame's visible words, far to near.
    private val placedWord = IntArray(MAX_PLACED)
    private val placedSprite = arrayOfNulls<WordSprite>(MAX_PLACED)
    private val placedRx = FloatArray(MAX_PLACED)
    private val placedRy = FloatArray(MAX_PLACED)
    private val placedX = FloatArray(MAX_PLACED)
    private val placedY = FloatArray(MAX_PLACED)
    private val placedRest = FloatArray(MAX_PLACED)
    private val placedScale = FloatArray(MAX_PLACED)
    private val placedDist = FloatArray(MAX_PLACED)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val burstColors = IntArray(3)
    /** Soft round light (alpha only): star streaks and the halo behind the word being read. Made once. */
    private val glow: Bitmap by lazy(LazyThreadSafetyMode.NONE) { glowDot().asAndroidBitmap() }

    override fun draw(c: Canvas, s: SceneContext) {
        val w = s.width
        val h = s.height
        if (w <= 0f || h <= 0f) return
        if (s.lyrics !== track) build(s.lyrics)
        val now = s.clockMs
        val dt = if (lastClock == 0L) 0f else ((now - lastClock) / 1000f).coerceIn(0f, 0.05f)
        lastClock = now
        val t = s.positionMs
        val beat = if (s.playing) s.beat.coerceIn(0f, 1f) else 0f

        // Where the camera is on the track, how fast it's flying (stops/s) and where the path heads next.
        // Velocities come from the pure track function, not frame deltas, so a seek never reads as a surge.
        val pNow = progressAt(t)
        val speed = ((pNow - progressAt(t - 40)) / 0.04f).coerceAtLeast(0f)
        val rush = (speed / 5f).coerceIn(0f, 1f)
        cameraAt(progressAt(t - 50))
        val prevX = outX
        cameraAt(pNow + LOOK_AHEAD)
        val aheadX = outX
        val aheadY = outY
        cameraAt(pNow)
        val nowX = outX
        val nowY = outY
        // Kicks push the whole tunnel a touch closer: a surge, not a flash.
        val p = pNow + BEAT_PUSH * beat
        cameraAt(p)
        val camPxX = outX * w
        val camPxY = outY * w
        val roll = outRoll

        // The stage camera: bank against sideways moves like an aircraft, look into the turn, kick on beats and
        // widen the field of view at speed (warp). Slow unrelated sways keep it alive when paused.
        val sec = now / 1000f
        val cam = s.camera
        cam.rotZ = bank.update(((prevX - nowX) / 0.05f * BANK).coerceIn(-9f, 9f) + 1.2f * sin(sec / 5.3f), now)
        cam.rotY = yaw.update(((aheadX - nowX) * YAW).coerceIn(-10f, 10f) + 1.5f * sin(sec / 7.7f), now)
        cam.rotX = pitch.update(((nowY - aheadY) * PITCH).coerceIn(-7f, 7f) + sin(sec / 9.1f), now)
        cam.zoom = zoom.update(1f + 0.035f * beat - 0.04f * rush, now)
        cam.shiftX = 0f
        cam.shiftY = 0f

        val vpX = w / 2f
        val vpY = h * VP_Y
        val cosR = cos(roll * DEG)
        val sinR = sin(roll * DEG)
        val cruise = if (s.playing) 0.45f + 0.35f * s.level.coerceIn(0f, 1f) else 0.1f
        val flow = starSpeed.update(cruise + speed * SPACING + 1.5f * beat, now)
        drawStars(c, s, dt, flow, vpX, vpY, camPxX, camPxY, cosR, sinR, beat)
        if (count == 0) return
        drawWords(c, s, t, p, progressAt(t - 30), progressAt(t - 60), camPxX, camPxY, roll, cosR, sinR, vpX, vpY, beat, rush)
    }

    // ---- The track -------------------------------------------------------------------------------------------

    /** Lays the whole song out as a flight path: once per lyrics, never per frame. */
    private fun build(lyrics: Lyrics) {
        track = lyrics
        val lines = lyrics.lines
        val n = lines.sumOf { it.words.size }
        count = n
        arrive = LongArray(n)
        sungMs = FloatArray(n)
        stopAt = FloatArray(n)
        lineOf = IntArray(n)
        wordOf = IntArray(n)
        emph = BooleanArray(n)
        wordX = FloatArray(n)
        wordY = FloatArray(n)
        wordRoll = FloatArray(n)
        camX = FloatArray(n)
        camY = FloatArray(n)
        camRoll = FloatArray(n)
        hopMs = FloatArray(n)
        holdMs = FloatArray(n)
        drift = FloatArray(n)
        if (n == 0) return

        var g = 0
        var path = -1
        var heading = rand(lines.size, 50) * 360f
        var roll = 0f
        for (li in lines.indices) {
            val words = lines[li].words
            if (words.isEmpty()) continue
            // A different path than the line before, centred elsewhere: far enough round that the swing shows.
            path = if (path < 0) (rand(li, 49) * PATHS).toInt() % PATHS else (path + 1 + (rand(li, 49) * (PATHS - 1)).toInt()) % PATHS
            heading += 110f + 140f * rand(li, 51)
            val reach = 0.32f + 0.26f * rand(li, 52)
            val ox = reach * cos(heading * DEG)
            val oy = 0.5f * reach * sin(heading * DEG)
            val dir = if (rand(li, 53) < 0.5f) -1f else 1f
            val step = 46f + 20f * rand(li, 54)
            val theta0 = rand(li, 55) * 360f
            val side0 = if (rand(li, 56) < 0.5f) 0 else 1
            for (j in words.indices) {
                val word = words[j]
                val side = if ((j + side0) % 2 == 0) -1f else 1f
                val x: Float
                val y: Float
                val want: Float
                var tilt = 0f
                // Where the word sits on screen (relative to the vanishing point) when it's read.
                var readX = 0f
                var readY = READ_Y
                when (path) {
                    HELIX -> {
                        // Round the axis; the camera stays on the axis and rolls so the read word is upright below it.
                        val theta = theta0 + dir * step * j
                        x = ox + HELIX_R * cos(theta * DEG)
                        y = oy + HELIX_R * sin(theta * DEG)
                        want = theta - 90f
                        readY = HELIX_R
                    }
                    CORRIDOR -> {
                        // Alternating walls; the camera weaves between them, so the next word comes from the other side.
                        x = ox + side * 0.3f
                        y = oy + (rand(li, j, 61) - 0.5f) * 0.08f
                        want = side * 2.5f
                        tilt = -side * 3f
                        readX = side * 0.085f
                    }
                    STAIRS -> {
                        // Each word a step up and across: the camera climbs.
                        val m = j - (words.size - 1) / 2f
                        x = ox + m * 0.2f * dir
                        y = oy - m * 0.11f
                        want = dir * 4f
                        tilt = -dir * 5f
                        readX = -dir * 0.07f
                    }
                    CLOUD -> {
                        val a = rand(li, j, 62) * TAU
                        val r = 0.08f + 0.2f * sqrt(rand(li, j, 63))
                        x = ox + r * cos(a)
                        y = oy + 0.7f * r * sin(a)
                        want = (rand(li, j, 64) - 0.5f) * 10f
                        tilt = (rand(li, j, 65) - 0.5f) * 14f
                        // The camera follows most of the way: the scatter still shows on screen.
                        readX = (x - ox) * 0.3f
                        readY = (y - oy) * 0.3f + 0.09f
                    }
                    else -> {
                        // Zipper: above and below the axis in turn, the camera bobbing along.
                        x = ox + (rand(li, j, 66) - 0.5f) * 0.06f
                        y = oy + side * 0.12f
                        want = side * 3f
                        tilt = -side * 5f
                        readY = READ_Y + side * 0.035f
                    }
                }
                // Roll carries on from the previous word: the helix keeps turning its way, the rest take the
                // short way round, so a line change is at most a half barrel roll.
                roll = if (path == HELIX && j > 0) roll + dir * step else want + 360f * ((roll - want) / 360f).roundToInt()
                val rr = roll * DEG
                wordX[g] = x
                wordY[g] = y
                wordRoll[g] = roll + tilt
                camRoll[g] = roll
                // Place the camera so this word lands at its reading spot: cam = word - R(roll) * readOffset.
                camX[g] = x - (readX * cos(rr) - readY * sin(rr))
                camY[g] = y - (readX * sin(rr) + readY * cos(rr))

                val a = if (g == 0) word.startMs else max(word.startMs, arrive[g - 1] + MIN_STEP_MS)
                arrive[g] = a
                sungMs[g] = (word.endMs - a).coerceAtLeast(80L).toFloat()
                lineOf[g] = li
                wordOf[g] = j
                emph[g] = word.endMs - word.startMs >= HELD_MS || j == words.lastIndex
                g++
            }
        }

        stopAt[0] = 0f
        for (k in 0 until n) {
            drift[k] = if (emph[k]) 0.04f else 0.1f
            if (k == n - 1) {
                holdMs[k] = sungMs[k] + OUTRO_LINGER_MS
                break
            }
            val span = (arrive[k + 1] - arrive[k]).toFloat()
            val boundary = lineOf[k + 1] != lineOf[k]
            val hold = sungMs[k] + if (emph[k]) EMPH_LINGER_MS else LINGER_MS
            val extra = if (boundary) LINE_GAP_STOPS else 0f
            if (span - hold > LONG_MS) {
                // A break: fly past the word, then cruise at a steady speed, so the gap is as long in space as in time.
                holdMs[k] = hold
                val cruiseMs = span - hold - EXIT_MS
                stopAt[k + 1] = stopAt[k] + EXIT_STOPS + extra + (CRUISE * (cruiseMs - SETTLE_MS / 2f) / 1000f).coerceIn(1.2f, 14f)
            } else {
                // Hop late, so the word stays readable while it's sung; line changes swoop longer and further.
                val hop = if (boundary) {
                    (span * 0.6f).coerceIn(220f, 650f).coerceAtMost(max(220f, span - sungMs[k]))
                } else {
                    (span * 0.42f).coerceIn(140f, 380f).coerceAtMost(max(140f, span - sungMs[k] * 0.75f))
                }
                hopMs[k] = hop.coerceIn(1f, span.coerceAtLeast(1f))
                stopAt[k + 1] = stopAt[k] + 1f + extra
            }
        }
        // The intro cruises in from far enough that nothing shows until the stars have streamed a while.
        introMs = arrive[0].toFloat().coerceIn(400f, INTRO_MAX_MS)
        introDist = (CRUISE * (introMs - SETTLE_MS / 2f) / 1000f).coerceIn(1.3f, 12f)
    }

    /** Camera progress along the track, in stops, at song time [t]. */
    private fun progressAt(t: Long): Float {
        if (count == 0) return 0f
        val k = arrivedBy(t)
        if (k < 0) {
            // Intro: cruise in from far away, braking onto the first word as it's sung.
            val since = (t - arrive[0]).toFloat() + introMs
            return stopAt[0] - introDist + if (since <= 0f) 0f else cruise(since, introMs, introDist)
        }
        val since = (t - arrive[k]).toFloat()
        val hold = holdMs[k]
        if (k == count - 1) {
            // The song's last word lingers, then flies past and leaves only the stars.
            if (since <= hold) return stopAt[k] + HOLD_DRIFT * since / hold
            val exit = OUTRO_STOPS - HOLD_DRIFT
            val u = ((since - hold) / OUTRO_MS).coerceAtMost(1f)
            return stopAt[k] + HOLD_DRIFT + exit * hermite(u, HOLD_DRIFT / hold * OUTRO_MS / exit, 0f)
        }
        val span = (arrive[k + 1] - arrive[k]).toFloat()
        val dist = stopAt[k + 1] - stopAt[k]
        if (hold <= 0f) {
            // Hang on the word (drifting closer), then hop, arriving at the next word exactly as it starts.
            val hop = hopMs[k]
            val d = drift[k]
            val v = ((since - (span - hop)) / hop).coerceIn(0f, 1f)
            return stopAt[k] + dist * (d * since / span + (1f - d) * hopEase(v))
        }
        // A long break: hold, fly past the word (speeding up into the cruise), cruise, brake onto the next word.
        if (since <= hold) return stopAt[k] + HOLD_DRIFT * since / hold
        val cruiseMs = span - hold - EXIT_MS
        val cruiseDist = dist - EXIT_STOPS
        if (since <= hold + EXIT_MS) {
            val exit = EXIT_STOPS - HOLD_DRIFT
            val cruiseSpeed = cruiseDist / (cruiseMs - SETTLE_MS / 2f)
            val m0 = HOLD_DRIFT / hold * EXIT_MS / exit
            val m1 = (cruiseSpeed * EXIT_MS / exit).coerceAtMost(2.5f)
            return stopAt[k] + HOLD_DRIFT + exit * hermite((since - hold) / EXIT_MS, m0, m1)
        }
        return stopAt[k] + EXIT_STOPS + cruise(since - hold - EXIT_MS, cruiseMs, cruiseDist)
    }

    /** Camera position and roll at track progress [p], into [outX]/[outY]/[outRoll]. */
    private fun cameraAt(p: Float) {
        if (count == 0) {
            outX = 0f
            outY = 0f
            outRoll = 0f
            return
        }
        val k = stopBefore(p)
        if (k < 0 || k >= count - 1) {
            val i = if (k < 0) 0 else count - 1
            outX = camX[i]
            outY = camY[i]
            outRoll = camRoll[i]
            return
        }
        // Smoothstep between stops: the camera is still sideways while a word is read and swings during the hop,
        // and the motion stays continuous in velocity across stops.
        val f = ((p - stopAt[k]) / (stopAt[k + 1] - stopAt[k])).coerceIn(0f, 1f)
        val e = f * f * (3f - 2f * f)
        outX = camX[k] + (camX[k + 1] - camX[k]) * e
        outY = camY[k] + (camY[k + 1] - camY[k]) * e
        outRoll = camRoll[k] + (camRoll[k + 1] - camRoll[k]) * e
    }

    /** Index of the last word that has arrived by [t], -1 before the first. */
    private fun arrivedBy(t: Long): Int {
        var lo = 0
        var hi = count - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (arrive[mid] <= t) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** Index of the last stop at or behind track progress [p], -1 before the first. */
    private fun stopBefore(p: Float): Int {
        var lo = 0
        var hi = count - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (stopAt[mid] <= p) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    // ---- Drawing ---------------------------------------------------------------------------------------------

    private fun drawStars(c: Canvas, s: SceneContext, dt: Float, flow: Float, vpX: Float, vpY: Float, camPxX: Float, camPxY: Float, cosR: Float, sinR: Float, beat: Float) {
        if (!starsReady) {
            for (i in 0 until STARS) spawn(i, STAR_NEAR + (STAR_FAR - STAR_NEAR) * noise(i, 7, 80))
            starsReady = true
        }
        val w = s.width
        val h = s.height
        val margin = w * 0.05f
        val glow = glow
        // How far back the streak's tail was a moment ago: streaks stretch with speed (warp).
        val trail = flow * STAR_TRAIL_S
        for (i in 0 until STARS) {
            val z = starZ[i] - flow * dt
            if (z < STAR_NEAR) {
                spawn(i, STAR_FAR - 0.8f * noise(starSeed, i, 86))
                continue
            }
            starZ[i] = z
            // A little parallax with the camera's sideways swing, and the camera's roll: the field turns with it.
            val lx = starX[i] * w - camPxX * PARALLAX
            val ly = starY[i] * w - camPxY * PARALLAX
            val rx = lx * cosR + ly * sinR
            val ry = -lx * sinR + ly * cosR
            val hx = vpX + rx / z
            val hy = vpY + ry / z
            if (hx < -margin || hx > w + margin || hy < -margin || hy > h + margin) {
                spawn(i, STAR_FAR - 0.8f * noise(starSeed, i, 87))
                continue
            }
            val tz = z + trail
            val tx = vpX + rx / tz
            val ty = vpY + ry / tz
            val k = starSize[i]
            val fade = ((STAR_FAR - z) / 1.2f).coerceIn(0f, 1f) * ((z - STAR_NEAR) / 0.25f).coerceIn(0f, 1f)
            val alpha = fade * (0.3f + 0.55f * k) * (0.8f + 0.2f * beat)
            if (alpha < 0.02f) continue
            val thick = (1.1f + 2.2f * k) * s.density * (0.6f + 0.55f / z).coerceAtMost(2.4f)
            starPaint.color = when (starTint[i]) {
                0 -> s.ink.light
                1 -> s.ink.primary
                else -> s.ink.secondary
            }
            starPaint.alpha = (255 * alpha.coerceAtMost(1f)).toInt()
            // The soft dot's visible core is about a third of its box, so pad the box around the streak.
            val pad = thick * 1.6f
            val dx = hx - tx
            val dy = hy - ty
            val len = sqrt(dx * dx + dy * dy)
            if (len < thick) {
                rect.set(hx - pad, hy - pad, hx + pad, hy + pad)
                c.drawBitmap(glow, null, rect, starPaint)
            } else {
                val half = len / 2f + pad
                c.save()
                c.translate((hx + tx) / 2f, (hy + ty) / 2f)
                c.rotate(atan2(dy, dx) * RAD)
                rect.set(-half, -pad, half, pad)
                c.drawBitmap(glow, null, rect, starPaint)
                c.restore()
            }
        }
    }

    /** A star on the tunnel wall at depth [z], clear of the middle where the words fly. */
    private fun spawn(i: Int, z: Float) {
        val seed = ++starSeed
        val a = noise(seed, i, 82) * TAU
        val r = 0.3f + sqrt(noise(seed, i, 83))
        starX[i] = r * cos(a)
        starY[i] = 0.75f * r * sin(a)
        starZ[i] = z
        starSize[i] = noise(seed, i, 84)
        val tint = noise(seed, i, 85)
        starTint[i] = if (tint < 0.66f) 0 else if (tint < 0.88f) 1 else 2
    }

    private fun drawWords(
        c: Canvas,
        s: SceneContext,
        t: Long,
        p: Float,
        ghost1: Float,
        ghost2: Float,
        camPxX: Float,
        camPxY: Float,
        roll: Float,
        cosR: Float,
        sinR: Float,
        vpX: Float,
        vpY: Float,
        beat: Float,
        rush: Float,
    ) {
        val w = s.width
        val focal = FOCAL * w
        val depth = SPACING * focal
        val k = stopBefore(p)

        // Project the words in view, far to near, so nearer ones cover farther ones.
        var n = 0
        var g = min(count - 1, k + 4)
        val first = max(0, k - 1)
        while (g >= first && n < MAX_PLACED) {
            val dist = stopAt[g] - p
            val z = focal + dist * depth
            val sprite = if (dist > AHEAD || dist < -BEHIND || z < focal * NEAR) null else s.sprite(lineOf[g], wordOf[g])
            if (sprite != null) {
                val persp = focal / z
                val lx = wordX[g] * w - camPxX
                val ly = wordY[g] * w - camPxY
                val rx = lx * cosR + ly * sinR
                val ry = -lx * sinR + ly * cosR
                val since = (t - arrive[g]).toFloat()
                val pulse = if (since >= 0f && since <= sungMs[g]) 1f + 0.04f * beat else 1f
                // Emphasis lands with a punch: a touch over size, settling as the camera brakes.
                val punch = if (emph[g] && since >= 0f && since < PUNCH_MS) 1f + 0.13f * (1f - easeOutCubic(since / PUNCH_MS)) else 1f
                val rest = min(if (emph[g]) EMPH_SCALE else READ_SCALE, w * MAX_WIDTH / sprite.width) * pulse * punch
                placedWord[n] = g
                placedSprite[n] = sprite
                placedRx[n] = rx
                placedRy[n] = ry
                placedX[n] = vpX + rx * persp
                placedY[n] = vpY + ry * persp
                placedRest[n] = rest
                placedScale[n] = persp * rest
                placedDist[n] = dist
                n++
            }
            g--
        }

        // The word being read: words further down the tunnel dim where they'd show through it.
        var reading = -1
        for (i in 0 until n) {
            val d = placedDist[i]
            if (d >= -0.15f && d <= 0.6f && (reading < 0 || abs(d) < abs(placedDist[reading]))) reading = i
        }

        for (i in 0 until n) {
            val sprite = placedSprite[i] ?: continue
            val wordIndex = placedWord[i]
            val dist = placedDist[i]
            val since = (t - arrive[wordIndex]).toFloat()
            var alpha = if (dist >= 0f) {
                (1f - dist / AHEAD).coerceIn(0f, 1f).let { it * it }
            } else {
                1f - smoothstep(0.1f, 0.68f, -dist)
            }
            if (reading >= 0 && i != reading && dist > 0.25f && overlaps(i, reading)) alpha *= 0.35f
            if (alpha < 0.02f) continue
            val sw = sprite.width.toFloat()
            val sh = sprite.height.toFloat()
            val angle = wordRoll[wordIndex] - roll
            val fill = if (since <= 0f) 0f else (since / sungMs[wordIndex]).coerceIn(0f, 1f)
            // How much this is "the" word: halo, shadow, icon and burst belong to it.
            val focus = 1f - smoothstep(0f, 0.35f, abs(dist))
            // Far words lean to the second colour, so depth reads as colour too; the read word's unsung part is a
            // lighter tint, so it stands off the album-coloured halo behind it.
            val tint = mix(s.ink.primary, s.ink.secondary, 0.45f * (dist / 1.2f).coerceIn(0f, 1f))
            val dim = if (focus > 0f) mix(tint, s.ink.light, 0.3f * focus) else tint
            val lit = s.ink.light

            // Speed ghosts: the word where it was a few frames ago, trailing back towards the vanishing point.
            // Only near words: far ones are still a scatter of letters and tiny anyway.
            if (dist > -0.12f && dist < 0.7f && rush > 0.12f) {
                for (step in 1..2) {
                    val gd = stopAt[wordIndex] - (if (step == 1) ghost1 else ghost2)
                    if (gd - dist < 0.04f || gd > AHEAD) continue
                    val gp = focal / (focal + gd * depth)
                    val gs = placedRest[i] * gp
                    paint.color = dim
                    paint.alpha = (255 * alpha * rush * (if (step == 1) 0.32f else 0.16f)).toInt()
                    c.save()
                    c.translate(vpX + placedRx[i] * gp, vpY + placedRy[i] * gp)
                    c.rotate(angle)
                    c.scale(gs, gs)
                    drawWord(c, sprite, -sw / 2f, -sh / 2f, paint)
                    c.restore()
                }
            }

            if (focus > 0.5f && emph[wordIndex] && since >= 0f && since < BURST_WINDOW_MS && s.effects.landOnce(burstKey(wordIndex))) {
                burstColors[0] = s.ink.primary
                burstColors[1] = s.ink.light
                burstColors[2] = s.ink.secondary
                s.effects.burst(placedX[i], placedY[i], burstColors, s.density, wordIndex * 31 + 7)
            }

            val scale = placedScale[i]
            c.save()
            c.translate(placedX[i], placedY[i])
            c.rotate(angle)
            c.scale(scale, scale)
            val left = -sw / 2f
            val top = -sh / 2f
            if (focus > 0.01f) {
                val flash = if (emph[wordIndex] && since >= 0f && since < FLASH_MS) 1f - since / FLASH_MS else 0f
                glowPaint.color = s.ink.primary
                glowPaint.alpha = (255 * (alpha * focus * (0.14f + 0.18f * beat + 0.3f * flash)).coerceAtMost(1f)).toInt()
                rect.set(left - sw * 0.15f, top - sh * 0.55f, -left + sw * 0.15f, -top + sh * 0.55f)
                c.drawBitmap(glow, null, rect, glowPaint)
            }
            // Approaching: letters still loosely scattered, pulling together. Passing: letters blown apart.
            val scatter = if (dist > 0f) smoothstep(0.08f, 1.3f, dist) else smoothstep(0.12f, 0.7f, -dist)
            if (scatter < 0.015f) {
                if (focus > 0.01f) {
                    // A soft drop shadow keeps the read word clear over bright album art.
                    val off = 3f * s.density / scale
                    paint.color = Color.BLACK
                    paint.alpha = (255 * 0.3f * alpha * focus).toInt()
                    drawWord(c, sprite, left + off * 0.4f, top + off, paint)
                }
                // Karaoke: the sung part in light, the rest in the album colour.
                if (fill < 1f) {
                    paint.color = dim
                    paint.alpha = (255 * alpha).toInt()
                    drawWordPart(c, sprite, left, top, 1f - fill, paint, fromRight = true)
                }
                if (fill > 0f) {
                    paint.color = lit
                    paint.alpha = (255 * alpha).toInt()
                    drawWordPart(c, sprite, left, top, fill, paint)
                }
                val icon = sprite.icon
                if (icon != null && focus > 0.01f && since >= 0f) {
                    val gap = sh * 0.12f
                    val ix = if (sprite.iconLeft) left - gap - icon.width else -left + gap
                    val pop = easeOutBack((since / 380f).coerceIn(0f, 1f))
                    s.ink.icon.alpha = (255 * alpha * focus).toInt()
                    c.save()
                    c.scale(pop, pop, ix + icon.width / 2f, 0f)
                    c.drawBitmap(icon, ix, -icon.height / 2f, s.ink.icon)
                    c.restore()
                }
            } else {
                drawLetters(c, sprite, wordIndex, scatter, dist < 0f, fill, alpha, dim, lit)
            }
            c.restore()
        }
        // Drop sprite references so dropped lines can be collected.
        for (i in 0 until n) placedSprite[i] = null
    }

    /**
     * The word letter by letter around the canvas origin. [passing] blows the letters outwards, spinning and
     * swelling as they fly past the camera; otherwise they're a looser version of the word that tightens as it nears.
     */
    private fun drawLetters(c: Canvas, sprite: WordSprite, g: Int, scatter: Float, passing: Boolean, fill: Float, alpha: Float, dim: Int, lit: Int) {
        val sw = sprite.width.toFloat()
        val sh = sprite.height.toFloat()
        val fillX = fill * sw
        val spread = if (passing) 1f + 2.4f * scatter else 1f + 0.5f * scatter
        val reach = sh * scatter * (if (passing) 1.5f else 0.75f)
        val turn = scatter * (if (passing) 150f else 70f)
        for (i in 0 until sprite.glyphCount) {
            if (glyphIsBlank(sprite, i)) continue
            val mid = glyphCenter(sprite, i)
            val a = noise(g, i, 71) * TAU
            val ox = (mid - sw / 2f) * spread + cos(a) * reach
            val oy = sin(a) * reach * (if (passing) 1f else 0.8f)
            val half = (sprite.glyphRight[i] - sprite.glyphLeft[i]) / 2f
            paint.color = if (mid <= fillX) lit else dim
            paint.alpha = (255 * alpha * (if (passing) 1f else 1f - 0.35f * scatter * noise(g, i, 73))).toInt()
            c.save()
            c.translate(ox, oy)
            c.rotate((noise(g, i, 72) - 0.5f) * turn)
            if (passing) {
                val grow = 1f + 0.6f * scatter * noise(g, i, 74)
                c.scale(grow, grow)
            }
            drawGlyph(c, sprite, i, -half, -sh / 2f, paint)
            c.restore()
        }
    }

    /** Whether placed word [i]'s centre falls behind placed word [r] on screen (rotation ignored: it's a fade cue). */
    private fun overlaps(i: Int, r: Int): Boolean {
        val si = placedSprite[i] ?: return false
        val sr = placedSprite[r] ?: return false
        val halfW = sr.width * placedScale[r] / 2f + si.width * placedScale[i] / 4f
        val halfH = sr.height * placedScale[r] / 2f + si.height * placedScale[i] / 4f
        return abs(placedX[i] - placedX[r]) < halfW && abs(placedY[i] - placedY[r]) < halfH
    }

    /** Keys for [Effects.landOnce], tagged so they can't collide with other scenes' keys. */
    private fun burstKey(g: Int): Long = (0x54L shl 48) or (lineOf[g].toLong() shl 20) or wordOf[g].toLong()

    private companion object {
        /** Focal length as a share of the screen width: a ~64° horizontal field of view. */
        const val FOCAL = 0.8f
        /** Depth between consecutive words, in focal lengths. */
        const val SPACING = 1.2f
        /** Words are drawn this many stops ahead, fading in from the dark. */
        const val AHEAD = 2.7f
        /** ...and until this many stops behind the reading plane, by when they've flown past the camera. */
        const val BEHIND = 0.75f
        /** Nothing nearer than this share of the focal length is drawn. */
        const val NEAR = 0.12f
        /** The vanishing point sits above the read word, like looking down a road. */
        const val VP_Y = 0.42f
        /** Below the vanishing point, in screen widths, where most paths put the read word. */
        const val READ_Y = 0.1f
        const val HELIX_R = 0.12f
        /** Size of a word at the reading plane (sprites are made at their biggest, keep it ≲ 1.2 with the push-in). */
        const val READ_SCALE = 0.8f
        const val EMPH_SCALE = 0.96f
        /** No word wider than this share of the screen at the reading plane. */
        const val MAX_WIDTH = 0.62f
        const val HELD_MS = 650L
        const val MIN_STEP_MS = 40L
        /** Extra track at a line change: the swoop to the next path is longer. */
        const val LINE_GAP_STOPS = 0.5f
        /** Hang on a word after it's sung before hopping on (longer for emphasis). */
        const val LINGER_MS = 250f
        const val EMPH_LINGER_MS = 450f
        const val OUTRO_LINGER_MS = 1500f
        /** Time left after the hold above which it's a break: fly past, cruise, brake. */
        const val LONG_MS = 1800f
        const val EXIT_MS = 650f
        const val EXIT_STOPS = 1f
        const val HOLD_DRIFT = 0.07f
        const val SETTLE_MS = 800f
        const val OUTRO_MS = 900f
        const val OUTRO_STOPS = 1.3f
        const val INTRO_MAX_MS = 9000f
        /** Cruise speed through breaks and intros, in stops per second. */
        const val CRUISE = 0.8f
        const val BEAT_PUSH = 0.045f
        const val PUNCH_MS = 300f
        const val FLASH_MS = 320f
        const val BURST_WINDOW_MS = 380f
        const val LOOK_AHEAD = 1.2f
        /** Stage camera response: degrees of bank per screen width/s sideways, of yaw/pitch per screen width ahead. */
        const val BANK = 3f
        const val YAW = 14f
        const val PITCH = 10f

        const val STARS = 42
        const val STAR_NEAR = 0.15f
        const val STAR_FAR = 6f
        const val STAR_TRAIL_S = 0.05f
        const val PARALLAX = 0.35f
        const val MAX_PLACED = 8

        const val PATHS = 5
        const val HELIX = 0
        const val CORRIDOR = 1
        const val STAIRS = 2
        const val CLOUD = 3

        const val TAU = (2.0 * PI).toFloat()
        const val DEG = (PI / 180.0).toFloat()
        const val RAD = (180.0 / PI).toFloat()

        /** 0..1 from a quick ease into the next word, braking hard as it lands: fast takeoff, long settle. */
        fun hopEase(v: Float): Float {
            val q = 1f - v * v
            return 1f - q * q * q
        }

        /** Cubic Hermite 0→1 with start/end slopes [m0]/[m1] (monotonic while they're below ~3). */
        fun hermite(u: Float, m0: Float, m1: Float): Float {
            val u2 = u * u
            val u3 = u2 * u
            return (-2f * u3 + 3f * u2) + (u3 - 2f * u2 + u) * m0 + (u3 - u2) * m1
        }

        /** [dist] stops over [total] ms at a steady speed, braking to a stop over the last [SETTLE_MS]. */
        fun cruise(since: Float, total: Float, dist: Float): Float {
            if (total <= 1f) return dist
            val brake = min(SETTLE_MS, total)
            val v = dist / (total - brake / 2f)
            val steady = total - brake
            if (since <= steady) return v * since.coerceAtLeast(0f)
            val b = min(since - steady, brake)
            return v * steady + v * (b - b * b / (2f * brake))
        }

        fun smoothstep(a: Float, b: Float, x: Float): Float {
            val k = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return k * k * (3f - 2f * k)
        }

        fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )

        /** Same as [rand] for three keys, without the vararg array: safe to call per letter per frame. */
        fun noise(a: Int, b: Int, salt: Int): Float {
            var h = 0x2545F491
            h = (h xor a) * 0x5bd1e995
            h = h xor (h ushr 15)
            h = (h xor b) * 0x5bd1e995
            h = h xor (h ushr 15)
            h = (h xor salt) * 0x5bd1e995
            h = h xor (h ushr 15)
            return (h and 0x7fffffff) % 100_000 / 100_000f
        }
    }
}
