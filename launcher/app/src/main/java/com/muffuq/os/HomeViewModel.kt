package com.muffuq.os

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.muffuq.os.data.AppEntry
import com.muffuq.os.data.AppRepository
import com.muffuq.os.data.ArtworkLoader
import com.muffuq.os.data.LauncherPrefs
import com.muffuq.os.data.LauncherSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val loaded: Boolean = false,
    val favorites: List<AppEntry> = emptyList(),
    val recents: List<AppEntry> = emptyList(),
    val apps: List<AppEntry> = emptyList(),
    val hidden: List<AppEntry> = emptyList(),
    val accent: Int = 0,
    val ambient: Boolean = true,
) {
    val favoritePackages: Set<String> = favorites.mapTo(HashSet()) { it.packageName }
}

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AppRepository(application)
    private val prefs = LauncherPrefs(application)
    val artwork = ArtworkLoader(application)

    private val installed = MutableStateFlow<List<AppEntry>?>(null)
    private val settings = MutableStateFlow(prefs.load())

    val state: StateFlow<HomeState> = combine(installed, settings, ::buildState)
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialState(settings.value))

    init {
        viewModelScope.launch {
            repository.changes()
                .onEach { artwork.evict(it) }
                .map { }
                .onStart { emit(Unit) }
                .conflate()
                .collect { refresh() }
        }
    }

    fun onLaunched(app: AppEntry) = update { s ->
        s.copy(recents = (listOf(app.packageName) + (s.recents - app.packageName)).take(MAX_RECENTS))
    }

    fun toggleFavorite(app: AppEntry) = update { s ->
        val pkg = app.packageName
        s.copy(favorites = if (pkg in s.favorites) s.favorites - pkg else s.favorites + pkg)
    }

    fun moveFavorite(app: AppEntry, delta: Int) = update { s ->
        val from = s.favorites.indexOf(app.packageName)
        val to = from + delta
        if (from < 0 || to !in s.favorites.indices) s
        else s.copy(favorites = s.favorites.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun hide(app: AppEntry) = update { s ->
        val pkg = app.packageName
        s.copy(hidden = s.hidden + pkg, favorites = s.favorites - pkg, recents = s.recents - pkg)
    }

    fun unhide(app: AppEntry) = update { it.copy(hidden = it.hidden - app.packageName) }

    fun setAccent(index: Int) = update { it.copy(accent = index) }

    fun setAmbient(enabled: Boolean) = update { it.copy(ambient = enabled) }

    private suspend fun refresh() {
        val apps = withContext(Dispatchers.IO) { repository.loadApps() }
        if (!settings.value.seeded) {
            val present = apps.mapTo(HashSet()) { it.packageName }
            update { it.copy(favorites = DEFAULT_FAVORITES.filter(present::contains).take(MAX_DEFAULT_FAVORITES), seeded = true) }
        }
        installed.value = apps
    }

    private fun update(transform: (LauncherSettings) -> LauncherSettings) {
        prefs.save(settings.updateAndGet(transform))
    }

    private fun initialState(s: LauncherSettings) = HomeState(accent = s.accent, ambient = s.ambient)

    private fun buildState(apps: List<AppEntry>?, s: LauncherSettings): HomeState {
        if (apps == null) return initialState(s)
        val visible = apps.filter { it.packageName !in s.hidden }
        val byPackage = visible.associateBy { it.packageName }
        return HomeState(
            loaded = true,
            favorites = s.favorites.mapNotNull(byPackage::get),
            recents = s.recents.asSequence()
                .filter { it !in s.favorites }
                .mapNotNull(byPackage::get)
                .take(RECENTS_SHOWN)
                .toList(),
            apps = visible,
            hidden = apps.filter { it.packageName in s.hidden },
            accent = s.accent,
            ambient = s.ambient,
        )
    }

    private companion object {
        const val MAX_RECENTS = 16
        const val RECENTS_SHOWN = 8
        const val MAX_DEFAULT_FAVORITES = 8

        /** Picked as first-run favourites when installed, in this order. */
        val DEFAULT_FAVORITES = listOf(
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.wbd.stream",
            "com.exxen.android",
            "com.beonetv.tod",
            "com.dsmart.blu.android",
            "com.trt.tabii.android",
            "com.spotify.tv.android",
            "com.google.android.youtube.tvmusic",
            "org.xbmc.kodi",
            "com.plexapp.android",
        )
    }
}
