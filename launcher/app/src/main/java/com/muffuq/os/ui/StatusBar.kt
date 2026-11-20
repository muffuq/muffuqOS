package com.muffuq.os.ui

import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.muffuq.os.R
import com.muffuq.os.data.NetworkKind
import com.muffuq.os.data.networkKind
import com.muffuq.os.stage.MediaState
import com.muffuq.os.stage.NowPlayingSource
import com.muffuq.os.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.text.SimpleDateFormat
import java.util.Date

private val PillShape = RoundedCornerShape(50)

/** Emits now, then on every minute boundary. Restarted on resume, so the clock is right after standby. */
private val minuteTicks = flow {
    while (true) {
        val now = System.currentTimeMillis()
        emit(now)
        delay(60_000 - now % 60_000)
    }
}

@Composable
fun StatusBar(
    onStage: () -> Unit,
    onCast: () -> Unit,
    onNetwork: () -> Unit,
    onSettings: () -> Unit,
    onCustomize: () -> Unit,
    onSpotlight: (Spotlight) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val network by remember { context.networkKind() }.collectAsStateWithLifecycle(null)
    val media by remember { NowPlayingSource.get(context) }.state.collectAsStateWithLifecycle()
    val playing = (media as? MediaState.Active)?.nowPlaying

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Wordmark(22.sp)
        Spacer(Modifier.weight(1f))
        // Shows the song when something plays, so it reads as "now playing" rather than one more button.
        val stageTitle = playing?.track?.shortTitle ?: stringResource(R.string.stage_pill)
        val stageDescription = playing?.track?.artist?.takeIf { it.isNotBlank() }
            ?.let { stringResource(R.string.spotlight_stage_playing, it) }
            ?: stringResource(R.string.spotlight_stage_desc)
        var stageFocused by remember { mutableStateOf(false) }
        // The song can change while the pill has focus: keep the hero in step.
        LaunchedEffect(stageTitle, stageDescription) {
            if (stageFocused) onSpotlight(Spotlight.Text(stageTitle, stageDescription))
        }
        GlassPill(
            icon = R.drawable.ic_music,
            label = stageTitle,
            onClick = onStage,
            onFocused = { onSpotlight(Spotlight.Text(stageTitle, stageDescription)) },
            onFocusChange = { stageFocused = it },
        )
        Spacer(Modifier.width(10.dp))
        GlassPill(
            icon = R.drawable.ic_cast,
            label = stringResource(R.string.cast_pill),
            onClick = onCast,
            onFocused = { onSpotlight(Spotlight.Action(R.string.cast_pill, R.string.spotlight_cast_desc)) },
        )
        Spacer(Modifier.width(10.dp))
        network?.let { kind ->
            val (icon, label) = when (kind) {
                NetworkKind.Wifi -> R.drawable.ic_wifi to R.string.network_wifi
                NetworkKind.Ethernet -> R.drawable.ic_ethernet to R.string.network_ethernet
                NetworkKind.None -> R.drawable.ic_wifi to R.string.network_offline
            }
            GlassPill(
                icon = icon,
                label = stringResource(label),
                iconTint = if (kind == NetworkKind.None) Palette.Danger else Color.Unspecified,
                onClick = onNetwork,
                onFocused = { onSpotlight(Spotlight.Action(R.string.spotlight_network, R.string.spotlight_network_desc)) },
            )
            Spacer(Modifier.width(10.dp))
        }
        GlassPill(
            icon = R.drawable.ic_settings,
            onClick = onSettings,
            onFocused = { onSpotlight(Spotlight.Action(R.string.spotlight_settings, R.string.spotlight_settings_desc)) },
        )
        Spacer(Modifier.width(10.dp))
        GlassPill(
            icon = R.drawable.ic_tune,
            onClick = onCustomize,
            onFocused = { onSpotlight(Spotlight.Action(R.string.spotlight_customize, R.string.spotlight_customize_desc)) },
        )
        Spacer(Modifier.width(26.dp))
        Clock()
    }
}

@Composable
fun Wordmark(size: TextUnit, modifier: Modifier = Modifier) {
    Text(
        text = buildAnnotatedString {
            append("muffuq")
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("OS") }
        },
        fontSize = size,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp,
        color = Palette.TextPrimary,
        modifier = modifier,
    )
}

@Composable
private fun GlassPill(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    label: String? = null,
    iconTint: Color = Color.Unspecified,
    onFocusChange: ((Boolean) -> Unit)? = null,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged {
            onFocusChange?.invoke(it.isFocused)
            if (it.isFocused) onFocused()
        },
        shape = ClickableSurfaceDefaults.shape(PillShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Glass,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Palette.TextPrimary,
            focusedContentColor = Palette.Background,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Palette.GlassBorder), shape = PillShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Row(
            Modifier.padding(horizontal = if (label == null) 10.dp else 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = label,
                tint = if (iconTint.isSpecified) iconTint else LocalContentColor.current,
                modifier = Modifier.size(18.dp),
            )
            if (label != null) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp).widthIn(max = 190.dp),
                )
            }
        }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val now by remember { minuteTicks }.collectAsStateWithLifecycle(System.currentTimeMillis())
    val is24h = DateFormat.is24HourFormat(context)
    val time = remember(now, is24h, locale) {
        SimpleDateFormat(if (is24h) "HH:mm" else "h:mm", locale).format(Date(now))
    }
    val date = remember(now, locale) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(now))
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(time, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary, letterSpacing = (-0.5).sp)
        Text(date, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.TextSecondary)
    }
}
