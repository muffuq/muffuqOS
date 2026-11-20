package com.muffuq.os.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.muffuq.os.R
import com.muffuq.os.data.AppEntry
import com.muffuq.os.data.Artwork
import com.muffuq.os.data.ArtworkLoader
import com.muffuq.os.ui.theme.Palette

private val CardRadius = 14.dp
private val CardShape = RoundedCornerShape(CardRadius)
private const val FOCUSED_SCALE = 1.1f

/** (spread, alpha) rings that stack into a soft glow. */
private val HaloRings = listOf(2.dp to 0.30f, 5.dp to 0.18f, 9.dp to 0.10f, 14.dp to 0.05f, 20.dp to 0.025f)

/**
 * Accent glow around the focused card, faked with a few stacked translucent rounded rects. A real blur
 * (tv-material Glow) makes Skia cache a blurred mask for every card size: ~18 MB of GPU memory on a 1 GB TV.
 */
private fun DrawScope.drawHalo(color: Color, alpha: Float) {
    val radius = CardRadius.toPx()
    for ((spread, ringAlpha) in HaloRings) {
        val s = spread.toPx()
        drawRoundRect(
            color = color.copy(alpha = ringAlpha * alpha),
            topLeft = Offset(-s, -s),
            size = Size(size.width + 2 * s, size.height + 2 * s),
            cornerRadius = CornerRadius(radius + s),
        )
    }
}

/** Specular highlight across the focused card, the tvOS-style "light catching the glass" look. */
private val Sheen = Brush.linearGradient(
    0f to Color.White.copy(alpha = 0.20f),
    0.35f to Color.White.copy(alpha = 0.05f),
    0.6f to Color.Transparent,
    start = Offset.Zero,
    end = Offset.Infinite,
)

@Composable
fun AppCard(
    app: AppEntry,
    artwork: ArtworkLoader,
    width: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    moving: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = MaterialTheme.colorScheme.primary
    LaunchedEffect(focused) {
        if (focused) onFocused()
    }

    // The card animates its own scale; the halo behind it follows with the same curve.
    val haloScale by animateFloatAsState(if (focused) FOCUSED_SCALE else 1f, label = "halo")
    val haloAlpha by animateFloatAsState(if (focused) 1f else 0f, label = "haloAlpha")

    Box(Modifier.width(width)) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = haloScale
                    scaleY = haloScale
                }
                .drawBehind { if (haloAlpha > 0f) drawHalo(accent, haloAlpha) },
        )
        Card(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(CardShape),
            colors = CardDefaults.colors(
                containerColor = Palette.CardPlaceholder,
                focusedContainerColor = Palette.CardPlaceholder,
            ),
            scale = CardDefaults.scale(focusedScale = FOCUSED_SCALE),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(2.dp, Color.White.copy(alpha = 0.92f)), shape = CardShape),
            ),
            interactionSource = interaction,
        ) {
            Box(Modifier.fillMaxSize()) {
                AppArtwork(app, artwork)
                if (focused) Box(Modifier.matchParentSize().background(Sheen))
                if (isFavorite) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(20.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_star),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }
                if (moving) {
                    Box(
                        Modifier.matchParentSize().background(accent.copy(alpha = 0.6f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_swap),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(34.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppArtwork(app: AppEntry, loader: ArtworkLoader, modifier: Modifier = Modifier) {
    var artwork by remember(app.component) { mutableStateOf(loader.peek(app)) }
    LaunchedEffect(app.component) {
        if (artwork == null) artwork = loader.load(app)
    }

    when (val art = artwork) {
        is Artwork.Banner -> Image(
            bitmap = art.image,
            contentDescription = app.label,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
        )

        // Phone apps have no banner: icon + name on a backdrop tinted with the icon's own colour.
        is Artwork.Icon -> BoxWithConstraints(
            modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(lerp(art.backdrop, Color.White, 0.08f), lerp(art.backdrop, Color.Black, 0.5f))),
            ),
        ) {
            val height = maxHeight
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(bitmap = art.image, contentDescription = null, modifier = Modifier.size(height * 0.42f))
                Spacer(Modifier.height(height * 0.06f))
                Text(
                    text = app.label,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = if (height > 90.dp) 14.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        null -> Box(modifier.fillMaxSize().background(Palette.CardPlaceholder))
    }
}
