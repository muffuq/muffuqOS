package com.muffuq.os.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.muffuq.os.R
import com.muffuq.os.data.AppEntry
import com.muffuq.os.data.ArtworkLoader
import com.muffuq.os.ui.theme.Palette
import kotlinx.coroutines.delay

/** What the hero area describes: the focused app, or a status-bar action. */
sealed interface Spotlight {
    data class App(val app: AppEntry) : Spotlight
    data class Action(@param:StringRes val title: Int, @param:StringRes val description: Int) : Spotlight
    /** Text known only at runtime, e.g. the song playing. */
    data class Text(val title: String, val description: String) : Spotlight
}

private val ChipShape = RoundedCornerShape(50)

/** Don't decode an ambient for every card we fly past while the D-pad is held. */
private const val AMBIENT_DEBOUNCE_MS = 140L

@Composable
fun Hero(
    spotlight: Spotlight?,
    favorites: Set<String>,
    moving: Boolean,
    modifier: Modifier = Modifier,
) {
    FadeSwap(
        target = spotlight,
        key = { s ->
            when (s) {
                is Spotlight.App -> s.app.packageName
                is Spotlight.Action -> s.title
                is Spotlight.Text -> s.title
                null -> 0
            }
        },
        modifier = modifier,
    ) { s ->
        when (s) {
            is Spotlight.App -> Column {
                HeroTitle(s.app.label)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 14.dp),
                ) {
                    if (s.app.packageName in favorites) {
                        Chip(stringResource(R.string.chip_favorite), icon = R.drawable.ic_star, accented = true)
                    }
                    Chip(stringResource(if (s.app.isLeanback) R.string.chip_tv_app else R.string.chip_phone_app))
                    Text(
                        text = stringResource(if (moving) R.string.hint_move else R.string.hint_options),
                        fontSize = 13.sp,
                        fontWeight = if (moving) FontWeight.Bold else FontWeight.Medium,
                        color = if (moving) MaterialTheme.colorScheme.primary else Palette.TextTertiary,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }

            is Spotlight.Action -> Column {
                HeroTitle(stringResource(s.title))
                Text(
                    text = stringResource(s.description),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            is Spotlight.Text -> Column {
                HeroTitle(s.title)
                Text(
                    text = s.description,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            null -> Box(Modifier)
        }
    }
}

/**
 * Fades the old content out and the new one in with a small rise. Alpha is applied per draw call
 * (ModulateAlpha) instead of through an offscreen buffer: AnimatedContent's fades allocate a GPU render target
 * per transition, which adds up on a TV with 1 GB of RAM.
 */
@Composable
private fun <T> FadeSwap(
    target: T,
    key: (T) -> Any?,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    var shown by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    val targetKey = key(target)
    // Same item, new details (e.g. it just became a favourite): update in place, no animation.
    if (targetKey == key(shown) && target != shown) shown = target
    LaunchedEffect(targetKey) {
        if (targetKey == key(shown)) return@LaunchedEffect
        progress.animateTo(0f, tween(110))
        shown = target
        progress.animateTo(1f, tween(280))
    }
    val rise = with(LocalDensity.current) { 14.dp.toPx() }
    Box(
        modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * rise
            compositingStrategy = CompositingStrategy.ModulateAlpha
        },
        contentAlignment = Alignment.BottomStart,
    ) {
        content(shown)
    }
}

@Composable
private fun HeroTitle(text: String) {
    Text(
        text = text,
        fontSize = 50.sp,
        lineHeight = 56.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-1.2).sp,
        color = Palette.TextPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun Chip(text: String, @DrawableRes icon: Int? = null, accented: Boolean = false) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(if (accented) accent.copy(alpha = 0.22f) else Palette.Glass, ChipShape)
            .border(1.dp, if (accented) accent.copy(alpha = 0.45f) else Palette.GlassBorder, ChipShape)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        val color = if (accented) lerp(accent, Color.White, 0.35f) else Palette.TextPrimary
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(13.dp).padding(end = 1.dp))
        }
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = color,
            modifier = Modifier.padding(start = if (icon != null) 6.dp else 0.dp),
        )
    }
}

/**
 * Full-screen colour field taken from the focused app, cross-faded on change, with scrims on top so text
 * stays readable whatever the app's colours are. [app] null = the calm accent-tinted default.
 */
@Composable
fun AmbientBackdrop(app: AppEntry?, loader: ArtworkLoader) {
    // null = the default accent glow
    var from by remember { mutableStateOf<ImageBitmap?>(null) }
    var to by remember { mutableStateOf<ImageBitmap?>(null) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(app?.component) {
        val next = if (app == null) null else {
            delay(AMBIENT_DEBOUNCE_MS)
            loader.ambient(app)
        }
        if (next === to) return@LaunchedEffect
        from = to
        to = next
        mix.snapTo(0f)
        mix.animateTo(1f, tween(900))
    }

    val accent = MaterialTheme.colorScheme.primary
    // Drawn straight onto the canvas with per-draw alpha. A Crossfade would put each image in an alpha layer,
    // i.e. two full-screen offscreen buffers for every change of focus.
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .drawBehind {
                val t = mix.value
                fun layer(image: ImageBitmap?, alpha: Float) {
                    if (alpha <= 0f) return
                    if (image != null) drawAmbient(image, alpha) else drawAccentGlow(accent, alpha)
                }
                // The outgoing layer stays opaque underneath while the new one comes in on top, so the room never
                // dips darker mid-fade. The glow is mostly transparent, so when it's the target the old image fades.
                if (t < 1f) layer(from, if (to == null) 1f - t else 1f)
                layer(to, t)

                drawRect(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.45f),
                        0.6f to Color.Black.copy(alpha = 0.12f),
                        1f to Color.Transparent,
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.25f),
                        0.3f to Color.Transparent,
                        0.62f to Color.Black.copy(alpha = 0.3f),
                        1f to Palette.Background.copy(alpha = 0.9f),
                    ),
                )
            },
    )
}

private fun DrawScope.drawAmbient(image: ImageBitmap, alpha: Float) {
    // Centre-crop the 16:9-ish source onto the screen.
    val scale = maxOf(size.width / image.width, size.height / image.height)
    val srcW = (size.width / scale).toInt().coerceAtMost(image.width)
    val srcH = (size.height / scale).toInt().coerceAtMost(image.height)
    drawImage(
        image = image,
        srcOffset = IntOffset((image.width - srcW) / 2, (image.height - srcH) / 2),
        srcSize = IntSize(srcW, srcH),
        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        alpha = alpha,
        filterQuality = FilterQuality.High,
    )
}

/** The calm default when there is no app to take colours from: a soft accent light from the top-left. */
private fun DrawScope.drawAccentGlow(accent: Color, alpha: Float) {
    drawRect(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.28f * alpha), Color.Transparent),
            center = Offset(size.width * 0.12f, size.height * 0.05f),
            radius = size.width * 0.75f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.10f * alpha), Color.Transparent),
            center = Offset(size.width * 0.95f, size.height),
            radius = size.width * 0.5f,
        ),
    )
}
