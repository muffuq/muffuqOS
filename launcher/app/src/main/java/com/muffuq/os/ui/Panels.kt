package com.muffuq.os.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.muffuq.os.R
import com.muffuq.os.data.AppEntry
import com.muffuq.os.data.ArtworkLoader
import com.muffuq.os.ui.theme.AccentPresets
import com.muffuq.os.ui.theme.Palette

@Composable
fun AppOptionsPanel(
    app: AppEntry,
    isFavorite: Boolean,
    artwork: ArtworkLoader,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onMove: () -> Unit,
    onHide: () -> Unit,
    onInfo: () -> Unit,
    onUninstall: () -> Unit,
) {
    val first = remember { FocusRequester() }
    SidePanel(onDismiss, initialFocus = first) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 24.dp)) {
            Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
                AppArtwork(app, artwork)
            }
            Text(
                text = app.label,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                color = Palette.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
        PanelItem(R.drawable.ic_play, stringResource(R.string.action_open), onOpen, Modifier.focusRequester(first))
        if (isFavorite) {
            PanelItem(R.drawable.ic_star, stringResource(R.string.action_favorite_remove), onToggleFavorite)
            PanelItem(R.drawable.ic_swap, stringResource(R.string.action_move), onMove)
        } else {
            PanelItem(R.drawable.ic_star_border, stringResource(R.string.action_favorite_add), onToggleFavorite)
        }
        PanelItem(R.drawable.ic_visibility_off, stringResource(R.string.action_hide), onHide)
        PanelItem(R.drawable.ic_info, stringResource(R.string.action_info), onInfo)
        if (!app.isSystem) {
            PanelItem(R.drawable.ic_delete, stringResource(R.string.action_uninstall), onUninstall, danger = true)
        }
    }
}

@Composable
fun CustomizePanel(
    accent: Int,
    ambient: Boolean,
    hidden: List<AppEntry>,
    onAccent: (Int) -> Unit,
    onAmbient: (Boolean) -> Unit,
    onUnhide: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val version = remember {
        @Suppress("DEPRECATION")
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    val first = remember { FocusRequester() }

    SidePanel(onDismiss, initialFocus = first) {
        Wordmark(32.sp)
        if (version != null) {
            Text(stringResource(R.string.settings_version, version), fontSize = 13.sp, color = Palette.TextSecondary)
        }

        PanelLabel(stringResource(R.string.settings_accent))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(start = 4.dp)) {
            AccentPresets.forEachIndexed { index, color ->
                AccentSwatch(
                    color = color,
                    selected = index == accent,
                    onClick = { onAccent(index) },
                    modifier = if (index == accent) Modifier.focusRequester(first) else Modifier,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        ListItem(
            selected = false,
            onClick = { onAmbient(!ambient) },
            headlineContent = { Text(stringResource(R.string.settings_ambient), fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(stringResource(R.string.settings_ambient_desc), fontSize = 12.sp) },
            trailingContent = { Switch(checked = ambient, onCheckedChange = null) },
            shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
            colors = panelItemColors(),
        )

        PanelLabel(stringResource(R.string.settings_hidden))
        if (hidden.isEmpty()) {
            Text(stringResource(R.string.settings_hidden_empty), fontSize = 14.sp, color = Palette.TextSecondary)
        } else {
            Text(
                stringResource(R.string.settings_hidden_hint),
                fontSize = 13.sp,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            hidden.forEach { app -> PanelItem(R.drawable.ic_visibility, app.label, { onUnhide(app) }) }
        }
    }
}

/** How to put a phone, tablet or computer on the TV: AirPlay (built in), Moonlight, Chromecast. */
@Composable
fun CastPanel(
    receiverName: String,
    moonlight: AppEntry?,
    onOpenMoonlight: (AppEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val first = remember { FocusRequester() }
    SidePanel(onDismiss, initialFocus = first) {
        Text(
            text = stringResource(R.string.cast_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp,
            color = Palette.TextPrimary,
        )

        PanelLabel(stringResource(R.string.cast_apple))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Color(0xFF4ADE80), CircleShape))
            Text(
                stringResource(R.string.cast_apple_ready, receiverName),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.TextPrimary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Text(
            stringResource(R.string.cast_apple_steps, receiverName),
            fontSize = 14.sp,
            color = Palette.TextSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )

        PanelLabel(stringResource(R.string.cast_pc))
        if (moonlight != null) {
            PanelItem(R.drawable.ic_play, stringResource(R.string.cast_pc_open), { onOpenMoonlight(moonlight) }, Modifier.focusRequester(first))
            Text(stringResource(R.string.cast_pc_hint), fontSize = 13.sp, color = Palette.TextSecondary, modifier = Modifier.padding(start = 4.dp))
        } else {
            Text(stringResource(R.string.cast_pc_missing), fontSize = 14.sp, color = Palette.TextSecondary)
        }

        PanelLabel(stringResource(R.string.cast_android))
        Text(stringResource(R.string.cast_android_steps), fontSize = 14.sp, color = Palette.TextSecondary)
    }
}

/**
 * Right-hand overlay panel. Dialog rather than an in-tree overlay so D-pad focus is trapped inside it.
 */
@Composable
private fun SidePanel(
    onDismiss: () -> Unit,
    initialFocus: FocusRequester,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The panel usually opens from a long-press, so the OK key is still held. Its key-up would land here
        // and click the first item, so drop key-ups whose key-down we never saw.
        val pressed = remember { mutableSetOf<Key>() }
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            pressed += event.key
                            false
                        }
                        KeyEventType.KeyUp -> !pressed.remove(event.key)
                        else -> false
                    }
                },
            contentAlignment = Alignment.CenterEnd,
        ) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(400.dp)
                    .background(Palette.Panel)
                    .border(width = 1.dp, color = Palette.GlassBorder)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 28.dp, vertical = 36.dp),
                content = content,
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { initialFocus.requestFocus() }
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = Palette.TextTertiary,
        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp),
    )
}

@Composable
private fun PanelItem(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(22.dp)) },
        shape = ListItemDefaults.shape(RoundedCornerShape(12.dp)),
        colors = panelItemColors(danger),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun panelItemColors(danger: Boolean = false) = ListItemDefaults.colors(
    containerColor = Color.Transparent,
    contentColor = if (danger) Palette.Danger else Palette.TextPrimary,
    focusedContainerColor = MaterialTheme.colorScheme.primary,
    focusedContentColor = MaterialTheme.colorScheme.onPrimary,
)

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(40.dp),
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = color,
            focusedContainerColor = color,
            pressedContainerColor = color,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CircleShape),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.15f),
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = if (color.luminance() > 0.45f) Palette.Background else Color.White,
                modifier = Modifier.align(Alignment.Center).size(20.dp),
            )
        }
    }
}
