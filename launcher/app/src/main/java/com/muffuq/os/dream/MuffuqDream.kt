package com.muffuq.os.dream

import android.os.SystemClock
import android.service.dreams.DreamService
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.muffuq.os.data.LauncherPrefs
import com.muffuq.os.ui.Wordmark
import com.muffuq.os.ui.theme.MuffuqTheme
import com.muffuq.os.ui.theme.Palette
import com.muffuq.os.ui.theme.accentAt
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * muffuqOS screensaver: a big clock over slowly drifting colour light. The clock itself wanders a little over the
 * minutes so nothing sits on the same pixels long enough to mark the panel.
 *
 * DreamService isn't a ComponentActivity, so it provides the lifecycle/saved-state owners Compose needs itself.
 */
class MuffuqDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        val accent = accentAt(LauncherPrefs(this).load().accent)
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MuffuqDream)
            setViewTreeSavedStateRegistryOwner(this@MuffuqDream)
            setContent { MuffuqTheme(accent) { DreamScreen() } }
        }
        setContentView(view)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDetachedFromWindow()
    }
}

@Composable
private fun DreamScreen() {
    val accent = MaterialTheme.colorScheme.primary
    val baseHsv = remember(accent) { accentHsv(accent) }
    // The light moves ~20 px/s at most, so a dozen frames a second look exactly like 60 and keep the idle TV cool
    // (60 fps cost a third of a CPU core). Only the draw and layout phases read it: no recomposition per tick.
    val clock = produceState(0L) {
        val start = SystemClock.uptimeMillis()
        while (true) {
            value = SystemClock.uptimeMillis() - start
            delay(FRAME_MS)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val t = clock.value
                // Two very slow, unrelated cycles: the light never repeats in an obvious loop.
                val a = cycle(t, 70_000)
                val b = cycle(t, 110_000)
                // The second light walks the whole colour wheel every ten minutes.
                val hue = (baseHsv[0] + 140f + 360f * (t % 600_000) / 600_000f) % 360f
                val companion = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.9f)))
                drawRect(Palette.Background)
                orb(accent, 0.42f, Offset(0.30f + 0.18f * cos(a), 0.35f + 0.20f * sin(b)), 0.62f)
                orb(companion, 0.30f, Offset(0.72f + 0.15f * sin(a * 0.7f), 0.65f + 0.18f * cos(b)), 0.55f)
                orb(accent, 0.18f, Offset(0.55f + 0.25f * cos(b * 1.3f), 0.15f + 0.10f * sin(a)), 0.40f)
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Palette.Background.copy(alpha = 0.6f))))
            },
    ) {
        Clock(
            Modifier
                .align(Alignment.Center)
                .offset {
                    val t = clock.value
                    // Back and forth across 120 dp every four minutes, plus a gentle bob.
                    val phase = (t % 480_000) / 240_000f
                    val wander = if (phase <= 1f) phase else 2f - phase
                    IntOffset((-60 + 120 * wander).dp.roundToPx(), (40 * sin(cycle(t, 70_000))).dp.roundToPx())
                },
        )
        Wordmark(18.sp, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 32.dp))
    }
}

private const val FRAME_MS = 80L

/** Position in a cycle of [periodMs], as an angle. */
private fun cycle(t: Long, periodMs: Long): Float = 2f * PI.toFloat() * (t % periodMs) / periodMs

private fun DrawScope.orb(color: Color, alpha: Float, center: Offset, radius: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(color.copy(alpha = alpha), Color.Transparent),
            center = Offset(center.x * size.width, center.y * size.height),
            radius = radius * size.width,
        ),
    )
}

private fun accentHsv(color: Color): FloatArray = FloatArray(3).also {
    android.graphics.Color.colorToHSV(
        android.graphics.Color.argb(255, (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt()),
        it,
    )
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(60_000 - value % 60_000)
        }
    }
    val is24h = DateFormat.is24HourFormat(context)
    val time = remember(now, is24h, locale) { SimpleDateFormat(if (is24h) "HH:mm" else "h:mm", locale).format(Date(now)) }
    val date = remember(now, locale) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(now))
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(time, fontSize = 132.sp, lineHeight = 132.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-4).sp, color = Palette.TextPrimary)
        Text(date, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary, modifier = Modifier.padding(top = 4.dp))
    }
}
