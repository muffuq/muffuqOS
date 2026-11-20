package com.muffuq.os.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

data class LauncherSettings(
    val favorites: List<String> = emptyList(),
    val hidden: Set<String> = emptySet(),
    /** Most recent first. */
    val recents: List<String> = emptyList(),
    val accent: Int = 0,
    /** Full-screen colour backdrop that follows the focused app. Off = plain dark background for weak TVs. */
    val ambient: Boolean = true,
    /** False until the default favourites have been picked on first launch. */
    val seeded: Boolean = false,
)

class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun load() = LauncherSettings(
        favorites = prefs.getList(KEY_FAVORITES),
        hidden = prefs.getList(KEY_HIDDEN).toSet(),
        recents = prefs.getList(KEY_RECENTS),
        accent = prefs.getInt(KEY_ACCENT, 0),
        ambient = prefs.getBoolean(KEY_AMBIENT, true),
        seeded = prefs.getBoolean(KEY_SEEDED, false),
    )

    fun save(settings: LauncherSettings) = prefs.edit {
        putString(KEY_FAVORITES, settings.favorites.joinToString("\n"))
        putString(KEY_HIDDEN, settings.hidden.joinToString("\n"))
        putString(KEY_RECENTS, settings.recents.joinToString("\n"))
        putInt(KEY_ACCENT, settings.accent)
        putBoolean(KEY_AMBIENT, settings.ambient)
        putBoolean(KEY_SEEDED, settings.seeded)
    }

    private fun SharedPreferences.getList(key: String): List<String> =
        getString(key, null)?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val KEY_FAVORITES = "favorites"
        const val KEY_HIDDEN = "hidden"
        const val KEY_RECENTS = "recents"
        const val KEY_ACCENT = "accent"
        const val KEY_AMBIENT = "ambient"
        const val KEY_SEEDED = "seeded"
    }
}
