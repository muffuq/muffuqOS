package com.muffuq.os.stage

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.muffuq.os.R
import com.muffuq.os.data.LauncherPrefs
import com.muffuq.os.ui.theme.MuffuqTheme
import com.muffuq.os.ui.theme.Palette
import com.muffuq.os.ui.theme.accentAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.cos
import kotlin.math.sin

/**
 * "Sahne": the song playing on the TV (Spotify, wherever it's controlled from) as a full screen of word-by-word
 * lyrics with light around the edges that follows the beat. Verci + Lumn, for a TV.
 *
 * Remote: ◀ ▶ scene (Otomatik = the director edits the song across scenes) · OK play/pause · ⏮ ⏭ previous/next
 * · Back leaves.
 */
class StageActivity : ComponentActivity() {
    private val source by lazy { NowPlayingSource.get(this) }
    private val pulse = AudioPulse()
    private var mode by mutableStateOf(StageMode.Director)
    private val lastKey = mutableLongStateOf(SystemClock.elapsedRealtime())
    private val styleChangedAt = mutableLongStateOf(0L)
    private val prefs by lazy { getSharedPreferences("stage", MODE_PRIVATE) }
    private val askAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pulse.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mode = StageMode.entries.getOrNull(prefs.getInt(KEY_MODE, 0)) ?: StageMode.Director
        val accent = accentAt(LauncherPrefs(this).load().accent)
        setContent {
            MuffuqTheme(accent) {
                StageScreen(source, mode, pulse, lastKey, styleChangedAt, accent)
            }
        }
        // The stage paints every pixel itself; a window background underneath would be one more full-screen pass.
        window.setBackgroundDrawable(null)
    }

    override fun onStart() {
        super.onStart()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pulse.start()
        } else if (!prefs.getBoolean(KEY_ASKED_AUDIO, false)) {
            // The visualizer API needs it to hear the TV's own output; asked once, the glow breathes without it.
            prefs.edit().putBoolean(KEY_ASKED_AUDIO, true).apply()
            askAudio.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        pulse.stop()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        lastKey.longValue = SystemClock.elapsedRealtime()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> cycleStyle(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> cycleStyle(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> if (event.repeatCount == 0) source.playPause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> source.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> source.previous()
            // Up/down only wake the header; nothing else to scroll here.
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun cycleStyle(delta: Int) {
        val all = StageMode.entries
        mode = all[(mode.ordinal + delta + all.size) % all.size]
        styleChangedAt.longValue = SystemClock.elapsedRealtime()
        prefs.edit().putInt(KEY_MODE, mode.ordinal).apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_ASKED_AUDIO = "asked_audio"
    }
}

/** Highlight a touch early: the eye expects the word slightly before the voice. */
private const val LEAD_MS = 120L
private const val BEAT_ATTACK_MS = 70L
/** The palette turns at most this often. */
private const val COLOR_TURN_MS = 6_000L

/** Which palette variant leads, and when it last turned. */
private class ColorTurn {
    var index = 0
    var at = 0L
    var seenBursts = 0
}

@Composable
private fun StageScreen(
    source: NowPlayingSource,
    mode: StageMode,
    pulse: AudioPulse,
    lastKey: MutableLongState,
    styleChangedAt: MutableLongState,
    accent: Color,
) {
    val media by source.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val repository = remember { LyricsRepository(context) }
    val glow = remember { EdgeGlow() }

    // One clock for everything that moves; read only in draw/layer blocks, so frames don't recompose.
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) withFrameMillis { now.longValue = SystemClock.elapsedRealtime() }
    }

    val nowPlaying = (media as? MediaState.Active)?.nowPlaying
    val track = nowPlaying?.track
    val trackKey = track?.key
    val lyrics by produceState<LyricsResult?>(null, trackKey) {
        value = null
        if (track == null) return@produceState
        while (true) {
            value = repository.load(track)
            if (value != LyricsResult.Unavailable) break
            delay(30_000)
        }
    }
    val palette = remember(track?.art, accent) { StagePalette.from(track?.art, accent) }
    val ink = remember(palette) { StageInk(palette) }
    // The palette turns at explosive moments (see below); every variant is made up front, switching is free.
    val inks = remember(palette) { List(StageInk.VARIANTS) { StageInk(palette, it) } }
    val colorTurn = remember(palette) { ColorTurn() }
    val trackChangedAt = remember(trackKey) { SystemClock.elapsedRealtime() }

    val density = LocalDensity.current
    val screenWidth = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val fonts = LocalFontFamilyResolver.current
    val found = (lyrics as? LyricsResult.Found)?.lyrics
    val playback = rememberUpdatedState(nowPlaying?.playback)
    fun position(at: Long = now.longValue): Long = (playback.value?.positionAt(at) ?: 0L) + LEAD_MS

    // Word textures for the line being sung and its neighbours, rendered a line ahead in the background.
    val sprites = remember(found, screenWidth) { found?.let { SpriteCache(it, screenWidth * 0.5f, density, fonts) } }
    var spritesReady by remember(sprites) { mutableStateOf(false) }
    LaunchedEffect(sprites) {
        val cache = sprites ?: return@LaunchedEffect
        val song = found ?: return@LaunchedEffect
        while (true) {
            val line = song.lineAt(position(SystemClock.elapsedRealtime()) + 800).coerceAtLeast(0)
            cache.keep((line - 1)..(line + 1))
            spritesReady = true
            delay(250)
        }
    }

    fun beat(t: Long): Float {
        if (playback.value?.playing != true) return 0f
        if (!pulse.working) return 0.3f * (0.5f + 0.5f * sin(t / 380f)) // no visualizer: breathe, don't fake beats
        // A quick swell into the beat and a soft fall after, not a hard flash.
        val age = pulse.beatAgeMs(t)
        val envelope = if (age < BEAT_ATTACK_MS) age / BEAT_ATTACK_MS.toFloat() else exp(-(age - BEAT_ATTACK_MS) / 300f)
        return pulse.beatStrength * envelope
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val t = now.longValue
            drawBackdrop(palette.backdrop, t / 1000f)
            val playing = playback.value?.playing == true
            val b = beat(t)
            val strength = if (playing) 0.55f + 0.4f * b + 0.1f * pulse.level else 0.3f
            val lit = inks[colorTurn.index]
            glow.draw(this, lit.glowPrimary, lit.glowSecondary, t / 24_000f, strength, b)
        }

        when {
            media == MediaState.NoAccess -> Message(stringResource(R.string.stage_no_access_title), stringResource(R.string.stage_no_access_hint))
            nowPlaying == null -> Message(stringResource(R.string.stage_idle_title), stringResource(R.string.stage_idle_hint))
            found != null && sprites != null && spritesReady -> {
                val shownAt = remember(mode, found) { SystemClock.elapsedRealtime() }
                val dot = remember { glowDot().asAndroidBitmap() }
                val effects = remember(found) { Effects().also { it.dot = dot } }
                val bokeh = remember { Bokeh(9) }
                val scene = remember(mode, found) { mode.scene() }
                val frame = remember(found, sprites, ink, effects) { SceneContext(found, sprites, ink, effects) }
                val tints = remember(ink) { intArrayOf(ink.primary, ink.secondary, ink.light) }
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // A new song or scene settles in rather than cutting.
                            val k = ((now.longValue - shownAt) / 450f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
                            alpha = k
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                            // The scene's camera: the whole lyrics layer seen in 3D.
                            val cam = frame.camera
                            rotationX = cam.rotX
                            rotationY = cam.rotY
                            rotationZ = cam.rotZ
                            scaleX = cam.zoom * (0.94f + 0.06f * k)
                            scaleY = scaleX
                            translationX = cam.shiftX
                            translationY = cam.shiftY
                            cameraDistance = 9f * density.density
                        },
                ) {
                    val t = now.longValue
                    frame.width = size.width
                    frame.height = size.height
                    frame.density = density.density
                    frame.positionMs = position(t)
                    frame.clockMs = t
                    frame.beat = beat(t)
                    frame.level = pulse.level
                    frame.playing = playback.value?.playing == true
                    // A burst (a word slamming in) turns the palette, at most every few seconds so it never strobes.
                    if (effects.bursts != colorTurn.seenBursts) {
                        colorTurn.seenBursts = effects.bursts
                        if (t - colorTurn.at > COLOR_TURN_MS) {
                            colorTurn.index = (colorTurn.index + 1) % inks.size
                            colorTurn.at = t
                        }
                    }
                    frame.ink = inks[colorTurn.index]
                    drawIntoCanvas { canvas ->
                        val c = canvas.nativeCanvas
                        bokeh.draw(c, dot, size.width, size.height, t / 1000f, frame.beat, tints, density.density)
                        scene.draw(c, frame)
                        effects.draw(c, t, density.density, size.width)
                    }
                }
                Header(nowPlaying, found.source, now, lastKey, trackChangedAt)
            }
            else -> TitleCard(
                nowPlaying,
                when (lyrics) {
                    LyricsResult.Instrumental -> stringResource(R.string.stage_instrumental)
                    LyricsResult.NotFound -> stringResource(R.string.stage_not_found)
                    LyricsResult.Unavailable -> stringResource(R.string.stage_unavailable)
                    null, is LyricsResult.Found -> stringResource(R.string.stage_loading)
                },
            )
        }

        if (nowPlaying != null && !nowPlaying.playback.playing) {
            Text(
                stringResource(R.string.stage_paused),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.TextPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 40.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
        StyleToast(mode, now, styleChangedAt, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * The album art as the background, alive: two copies of the blurred art drifting and breathing on unrelated
 * slow periods (one flipped, faint), so the colours slowly move like light through glass.
 */
private fun DrawScope.drawBackdrop(image: ImageBitmap, t: Float) {
    val w = size.width
    val h = size.height
    fun layer(scale: Float, dx: Float, dy: Float, alpha: Float, flip: Boolean) {
        val dw = w * scale
        val dh = h * scale
        val draw: DrawScope.() -> Unit = {
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset(((w - dw) / 2f + dx).roundToInt(), ((h - dh) / 2f + dy).roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                alpha = alpha,
                filterQuality = FilterQuality.Low,
            )
        }
        if (flip) withTransform({ scale(-1f, -1f, Offset(w / 2f, h / 2f)) }, draw) else draw()
    }
    layer(1.2f + 0.04f * sin(t / 17f), w * 0.04f * sin(t / 23f), h * 0.04f * cos(t / 19f), 1f, flip = false)
    layer(1.5f + 0.06f * sin(t / 13f), w * 0.05f * cos(t / 21f), h * 0.05f * sin(t / 27f), 0.35f, flip = true)
}

/** Artwork, title and artist in the corner: shown when the song changes or a key is pressed, then it fades. */
@Composable
private fun Header(nowPlaying: NowPlaying, lyricsSource: String?, now: MutableLongState, lastKey: MutableLongState, trackChangedAt: Long) {
    val art = remember(nowPlaying.track.art) { nowPlaying.track.art?.asImageBitmap() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(start = 40.dp, top = 28.dp)
            .graphicsLayer {
                val since = now.longValue - maxOf(lastKey.longValue, trackChangedAt)
                alpha = (1f - (since - 5_000) / 700f).coerceIn(0f, 1f)
                // Per draw call, not an offscreen buffer (see the launcher's perf rules).
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        if (art != null) Artwork(art, 56)
        Column(Modifier.padding(start = 14.dp).widthIn(max = 520.dp)) {
            Text(nowPlaying.track.shortTitle, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(nowPlaying.track.artist.takeIf { it.isNotBlank() }, lyricsSource?.let { stringResource(R.string.stage_source, it) }).joinToString("  ·  "),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Shown while there's no lyrics to sing along: big artwork like a now-playing card. */
@Composable
private fun TitleCard(nowPlaying: NowPlaying, status: String) {
    val art = remember(nowPlaying.track.art) { nowPlaying.track.art?.asImageBitmap() }
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (art != null) Artwork(art, 220)
        Text(
            nowPlaying.track.shortTitle,
            fontSize = 36.sp,
            lineHeight = 42.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Palette.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 26.dp).widthIn(max = 760.dp),
        )
        Text(nowPlaying.track.artist, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        Text(status, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.TextTertiary, modifier = Modifier.padding(top = 22.dp))
    }
}

@Composable
private fun Artwork(art: ImageBitmap, sizeDp: Int) {
    Image(
        art,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape((sizeDp / 9).dp)),
    )
}

@Composable
private fun Message(title: String, hint: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 120.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("♪", fontSize = 64.sp, color = Palette.TextSecondary)
        Text(title, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, color = Palette.TextPrimary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        Text(hint, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, color = Palette.TextSecondary, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
    }
}

/** The style names, with the current one lit: briefly after switching, and as a hint when the screen opens. */
@Composable
private fun StyleToast(mode: StageMode, now: MutableLongState, styleChangedAt: MutableLongState, modifier: Modifier) {
    val opened = remember { SystemClock.elapsedRealtime() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(bottom = 30.dp)
            .graphicsLayer {
                val shownAt = maxOf(styleChangedAt.longValue, opened)
                alpha = (1f - (now.longValue - shownAt - 2_200) / 500f).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
    ) {
        // Too many scenes for a row of pills: the current one, its neighbours dimmed either side.
        val all = StageMode.entries
        val previous = all[(mode.ordinal - 1 + all.size) % all.size]
        val next = all[(mode.ordinal + 1) % all.size]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text("◀  " + previous.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextTertiary, modifier = Modifier.padding(start = 8.dp))
            Text(
                mode.label,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Palette.Background,
                modifier = Modifier
                    .background(Palette.TextPrimary, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Text(next.label + "  ▶", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextTertiary, modifier = Modifier.padding(end = 8.dp))
        }
        Text(stringResource(R.string.stage_hint), fontSize = 13.sp, color = Palette.TextTertiary, modifier = Modifier.padding(top = 8.dp))
    }
}
