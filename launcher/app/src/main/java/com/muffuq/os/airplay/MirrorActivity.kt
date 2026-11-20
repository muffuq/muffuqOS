package com.muffuq.os.airplay

import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.muffuq.os.R
import com.muffuq.os.data.LauncherPrefs
import com.muffuq.os.ui.Wordmark
import com.muffuq.os.ui.theme.MuffuqTheme
import com.muffuq.os.ui.theme.Palette
import com.muffuq.os.ui.theme.accentAt
import kotlinx.coroutines.delay

/** Full-screen mirroring: the sender's screen on a SurfaceView, with muffuqOS-styled connecting/paused states. */
class MirrorActivity : ComponentActivity() {
    private var sawSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onBackPressedDispatcher.addCallback(this) { stopMirroring() }
        val accent = accentAt(LauncherPrefs(this).load().accent)

        setContent {
            val state by MirrorBus.state.collectAsStateWithLifecycle()
            LaunchedEffect(state) {
                if (state is MirrorBus.State.Idle) {
                    if (sawSession) finish()
                } else {
                    sawSession = true
                }
            }
            MuffuqTheme(accent) { MirrorScreen(state) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Home pressed or another app came up: mirroring is no longer visible, so end it. Not when we are the
        // ones finishing (session over, or replaced by a new sender): that would stop the *next* session.
        if (!isFinishing && !isChangingConfigurations) stopMirroring()
    }

    private fun stopMirroring() {
        MirrorBus.attachSurface(null)
        MirrorBus.stop()
        finish()
    }
}

@Composable
private fun MirrorScreen(state: MirrorBus.State) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val mirroring = state as? MirrorBus.State.Mirroring
        if (mirroring != null) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = MirrorBus.attachSurface(holder.surface)
                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                            override fun surfaceDestroyed(holder: SurfaceHolder) = MirrorBus.attachSurface(null)
                        })
                    }
                },
                modifier = Modifier.aspectRatio(mirroring.width.toFloat() / mirroring.height),
            )
            if (mirroring.showing) {
                StopHint()
                if (mirroring.paused) Paused(mirroring.client)
            } else {
                // The surface has to exist for the decoder, but stays covered until a frame is really on it.
                Connecting(mirroring.client)
            }
        } else {
            Connecting((state as? MirrorBus.State.Connecting)?.client.orEmpty())
        }
    }
}

@Composable
private fun Connecting(client: String) {
    val accent = MaterialTheme.colorScheme.primary
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "scale",
    )
    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Palette.Background)
            drawRect(
                Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.42f),
                    radius = size.width * 0.45f,
                ),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .background(accent.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_cast), contentDescription = null, tint = accent, modifier = Modifier.size(44.dp))
            }
            Text(
                text = client,
                fontSize = 40.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp,
                color = Palette.TextPrimary,
                modifier = Modifier.padding(top = 32.dp),
            )
            Text(
                text = stringResource(R.string.mirror_connecting),
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Wordmark(20.sp, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp))
    }
}

@Composable
private fun BoxScope.StopHint() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(4000)
        visible = false
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(600), label = "hint")
    Text(
        text = stringResource(R.string.mirror_stop_hint),
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 32.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

@Composable
private fun Paused(client: String) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.mirror_paused, client),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.TextPrimary,
            textAlign = TextAlign.Center,
        )
    }
}
