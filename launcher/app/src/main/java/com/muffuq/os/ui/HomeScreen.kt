package com.muffuq.os.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.muffuq.os.HomeState
import com.muffuq.os.R
import com.muffuq.os.airplay.AirPlayService
import com.muffuq.os.data.AppEntry
import com.muffuq.os.data.ArtworkLoader
import com.muffuq.os.ui.theme.Palette
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.min

/** Overscan-safe horizontal margin from the Android TV design guidelines. */
private val SafeHorizontal = 48.dp
private val HeroHeight = 124.dp
private val FavoriteGap = 22.dp
private val AppGap = 18.dp
private const val FAVORITE_COLUMNS = 4
private const val APP_COLUMNS = 6
private const val MOONLIGHT_PACKAGE = "com.limelight"

interface HomeActions {
    fun launch(app: AppEntry)
    fun openStage()
    fun openSettings()
    fun openNetworkSettings()
    fun openAppInfo(app: AppEntry)
    fun uninstall(app: AppEntry)
    fun toggleFavorite(app: AppEntry)
    fun moveFavorite(app: AppEntry, delta: Int)
    fun hide(app: AppEntry)
    fun unhide(app: AppEntry)
    fun setAccent(index: Int)
    fun setAmbient(enabled: Boolean)
}

private enum class Section { Favorites, Recents, Apps }

private data class OptionsTarget(val app: AppEntry, val fromFavorites: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    state: HomeState,
    artwork: ArtworkLoader,
    homePresses: Flow<Unit>,
    actions: HomeActions,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val favoritesState = rememberLazyListState()
    val recentsState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }

    var options by remember { mutableStateOf<OptionsTarget?>(null) }
    var customizing by remember { mutableStateOf(false) }
    var casting by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<String?>(null) }
    var refocusPending by remember { mutableStateOf(false) }
    var spotlight by remember { mutableStateOf<Spotlight?>(null) }
    var ambientApp by remember { mutableStateOf<AppEntry?>(null) }

    val firstSection = when {
        state.favorites.isNotEmpty() -> Section.Favorites
        state.recents.isNotEmpty() -> Section.Recents
        else -> Section.Apps
    }

    fun Modifier.firstFocusIf(section: Section, index: Int): Modifier =
        if (section == firstSection && index == 0) focusRequester(firstFocus) else this

    fun onAppFocused(app: AppEntry) {
        spotlight = Spotlight.App(app)
        ambientApp = app
    }

    suspend fun focusFirst() {
        // requestScrollToItem doesn't suspend: rows that aren't on screen yet (e.g. an empty recents row)
        // would make scrollToItem wait for a first layout that never comes.
        listState.requestScrollToItem(0)
        favoritesState.requestScrollToItem(0)
        recentsState.requestScrollToItem(0)
        withFrameNanos { }
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    LaunchedEffect(state.loaded) {
        if (state.loaded) focusFirst()
    }
    LaunchedEffect(homePresses) {
        homePresses.collect {
            moving = null
            options = null
            customizing = false
            casting = false
            focusFirst()
        }
    }
    // The focused card was removed from under us (unfavourited / hidden): put focus somewhere sensible.
    LaunchedEffect(state.favorites, state.apps) {
        if (refocusPending) {
            refocusPending = false
            focusFirst()
        }
    }
    BackHandler {
        if (moving != null) moving = null else scope.launch { focusFirst() }
    }

    fun onMoveKey(event: KeyEvent): Boolean {
        val pkg = moving ?: return false
        val index = state.favorites.indexOfFirst { it.packageName == pkg }
        if (index < 0) {
            moving = null
            return false
        }
        val down = event.type == KeyEventType.KeyDown
        return when (event.key) {
            Key.DirectionLeft, Key.DirectionRight -> {
                if (down) {
                    val delta = if (event.key == Key.DirectionLeft) -1 else 1
                    val target = (index + delta).coerceIn(state.favorites.indices)
                    actions.moveFavorite(state.favorites[index], delta)
                    scope.launch { favoritesState.keepVisible(target) }
                }
                true
            }
            // Finish on key-up so the card underneath doesn't also see a click.
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                if (!down) moving = null
                true
            }
            Key.DirectionUp, Key.DirectionDown -> true
            else -> false
        }
    }

    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(app = if (state.ambient) ambientApp else null, loader = artwork)

        val density = LocalDensity.current
        // Rows keep the platform's TV behaviour; the page itself only scrolls as much as it has to,
        // so the hero doesn't get shoved under the status bar when the first row gains focus.
        val rowSpec = LocalBringIntoViewSpec.current
        val pageSpec = remember(density) {
            EdgeMarginSpec(top = with(density) { 20.dp.toPx() }, bottom = with(density) { 24.dp.toPx() })
        }

        Column(Modifier.fillMaxSize()) {
        StatusBar(
            onStage = actions::openStage,
            onCast = { casting = true },
            onNetwork = actions::openNetworkSettings,
            onSettings = actions::openSettings,
            onCustomize = { customizing = true },
            onSpotlight = { spotlight = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = SafeHorizontal, end = SafeHorizontal, top = 24.dp),
        )
        Hero(
            spotlight = spotlight,
            favorites = state.favoritePackages,
            moving = moving != null,
            modifier = Modifier
                .padding(horizontal = SafeHorizontal)
                .fillMaxWidth()
                .height(HeroHeight),
        )

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val contentWidth = maxWidth - SafeHorizontal * 2
            val favoriteWidth = (contentWidth - FavoriteGap * (FAVORITE_COLUMNS - 1)) / FAVORITE_COLUMNS
            val appWidth = (contentWidth - AppGap * (APP_COLUMNS - 1)) / APP_COLUMNS
            val appRows = remember(state.apps) { state.apps.chunked(APP_COLUMNS) }

            fun openOptions(app: AppEntry, fromFavorites: Boolean) {
                if (moving == null) options = OptionsTarget(app, fromFavorites)
            }

            CompositionLocalProvider(LocalBringIntoViewSpec provides pageSpec) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 40.dp),
                ) {
                    if (!state.loaded) return@LazyColumn

                    item(key = "favorites") {
                        Box(
                            Modifier
                                .padding(top = 8.dp, bottom = 22.dp)
                                // Coming back up to the first row always shows the whole hero again.
                                .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } },
                        ) {
                            if (state.favorites.isEmpty()) {
                                EmptyFavorites()
                            } else {
                                CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                    LazyRow(
                                        state = favoritesState,
                                        contentPadding = PaddingValues(horizontal = SafeHorizontal, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(FavoriteGap),
                                        modifier = Modifier.onPreviewKeyEvent(::onMoveKey),
                                    ) {
                                        itemsIndexed(state.favorites, key = { _, app -> app.packageName }) { index, app ->
                                            AppCard(
                                                app = app,
                                                artwork = artwork,
                                                width = favoriteWidth,
                                                moving = moving == app.packageName,
                                                onClick = { if (moving == null) actions.launch(app) },
                                                onLongClick = { openOptions(app, fromFavorites = true) },
                                                onFocused = { onAppFocused(app) },
                                                modifier = Modifier.firstFocusIf(Section.Favorites, index),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (state.recents.isNotEmpty()) {
                        item(key = "recents-title") { SectionTitle(stringResource(R.string.row_recents)) }
                        item(key = "recents") {
                            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                                LazyRow(
                                    state = recentsState,
                                    contentPadding = PaddingValues(horizontal = SafeHorizontal, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(AppGap),
                                    modifier = Modifier.padding(bottom = 14.dp),
                                ) {
                                    itemsIndexed(state.recents, key = { _, app -> app.packageName }) { index, app ->
                                        AppCard(
                                            app = app,
                                            artwork = artwork,
                                            width = appWidth,
                                            isFavorite = app.packageName in state.favoritePackages,
                                            onClick = { actions.launch(app) },
                                            onLongClick = { openOptions(app, fromFavorites = false) },
                                            onFocused = { onAppFocused(app) },
                                            modifier = Modifier.firstFocusIf(Section.Recents, index),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "apps-title") {
                        SectionTitle(stringResource(R.string.row_all_apps), trailing = state.apps.size.toString())
                    }
                    items(appRows.size, key = { "apps-$it" }) { rowIndex ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(AppGap),
                            modifier = Modifier.padding(start = SafeHorizontal, end = SafeHorizontal, top = 10.dp, bottom = 10.dp),
                        ) {
                            appRows[rowIndex].forEachIndexed { column, app ->
                                key(app.packageName) {
                                    AppCard(
                                        app = app,
                                        artwork = artwork,
                                        width = appWidth,
                                        isFavorite = app.packageName in state.favoritePackages,
                                        onClick = { actions.launch(app) },
                                        onLongClick = { openOptions(app, fromFavorites = false) },
                                        onFocused = { onAppFocused(app) },
                                        modifier = Modifier.firstFocusIf(Section.Apps, rowIndex * APP_COLUMNS + column),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    options?.let { target ->
        val app = target.app
        AppOptionsPanel(
            app = app,
            isFavorite = app.packageName in state.favoritePackages,
            artwork = artwork,
            onDismiss = { options = null },
            onOpen = {
                options = null
                actions.launch(app)
            },
            onToggleFavorite = {
                options = null
                refocusPending = target.fromFavorites
                actions.toggleFavorite(app)
            },
            onMove = {
                options = null
                moving = app.packageName
            },
            onHide = {
                options = null
                refocusPending = true
                actions.hide(app)
            },
            onInfo = {
                options = null
                actions.openAppInfo(app)
            },
            onUninstall = {
                options = null
                actions.uninstall(app)
            },
        )
    }

    if (casting) {
        CastPanel(
            receiverName = AirPlayService.RECEIVER_NAME,
            moonlight = state.apps.firstOrNull { it.packageName == MOONLIGHT_PACKAGE },
            onOpenMoonlight = {
                casting = false
                actions.launch(it)
            },
            onDismiss = { casting = false },
        )
    }

    if (customizing) {
        CustomizePanel(
            accent = state.accent,
            ambient = state.ambient,
            hidden = state.hidden,
            onAccent = actions::setAccent,
            onAmbient = actions::setAmbient,
            onUnhide = actions::unhide,
            onDismiss = { customizing = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String, trailing: String? = null) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.padding(start = SafeHorizontal, end = SafeHorizontal, top = 6.dp, bottom = 4.dp),
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Palette.TextPrimary,
        )
        if (trailing != null) {
            Text(
                text = trailing,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.TextTertiary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}

@Composable
private fun EmptyFavorites() {
    Box(
        Modifier
            .padding(horizontal = SafeHorizontal, vertical = 12.dp)
            .fillMaxWidth()
            .height(96.dp)
            .background(Palette.Glass, RoundedCornerShape(16.dp))
            .border(1.dp, Palette.GlassBorder, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.favorites_empty),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Palette.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

/** Only scroll when the focused row would end up too close to the top or bottom edge of the shelf. */
@OptIn(ExperimentalFoundationApi::class)
private class EdgeMarginSpec(private val top: Float, private val bottom: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        return when {
            offset < top -> offset - top
            end > containerSize - bottom -> min(end - (containerSize - bottom), offset - top)
            else -> 0f
        }
    }
}

/** Scrolls just enough to keep [index] on screen while it's being dragged along the favourites row. */
private suspend fun LazyListState.keepVisible(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return
    if (index <= visible.first().index || index >= visible.last().index) {
        animateScrollToItem((index - 1).coerceAtLeast(0))
    }
}
