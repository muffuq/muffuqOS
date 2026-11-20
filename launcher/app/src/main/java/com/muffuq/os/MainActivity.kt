package com.muffuq.os

import android.content.ActivityNotFoundException
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.muffuq.os.airplay.AirPlayService
import com.muffuq.os.data.AppEntry
import com.muffuq.os.stage.NowPlayingSource
import com.muffuq.os.stage.StageActivity
import com.muffuq.os.ui.HomeActions
import com.muffuq.os.ui.HomeScreen
import com.muffuq.os.ui.theme.MuffuqTheme
import com.muffuq.os.ui.theme.accentAt
import kotlinx.coroutines.flow.MutableSharedFlow

class MainActivity : ComponentActivity(), HomeActions {
    private val viewModel: HomeViewModel by viewModels()
    private val homePresses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AirPlayService.start(this)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) keepMusicPlaying()
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            MuffuqTheme(accent = accentAt(state.accent)) {
                HomeScreen(state = state, artwork = viewModel.artwork, homePresses = homePresses, actions = this)
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The TV is short on RAM while something else is on screen: give back the artwork, it reloads in a blink.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) viewModel.artwork.evict(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: jump back to the start, like the stock launchers do.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            homePresses.tryEmit(Unit)
            keepMusicPlaying()
        }
    }

    /** Home on this TV stops the music playing in the background; the guard puts it back (NowPlayingSource). */
    private fun keepMusicPlaying() = NowPlayingSource.get(this).undoHomePause()

    override fun launch(app: AppEntry) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(if (app.isLeanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(app.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (start(intent)) {
            viewModel.onLaunched(app)
        } else {
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    override fun openSettings() {
        start(Intent(Settings.ACTION_SETTINGS))
    }

    override fun openStage() {
        start(Intent(this, StageActivity::class.java))
    }

    override fun openNetworkSettings() {
        if (!start(Intent(Settings.ACTION_WIFI_SETTINGS))) openSettings()
    }

    override fun openAppInfo(app: AppEntry) {
        start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(app)))
    }

    override fun uninstall(app: AppEntry) {
        @Suppress("DEPRECATION")
        start(Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri(app)))
    }

    override fun toggleFavorite(app: AppEntry) = viewModel.toggleFavorite(app)
    override fun moveFavorite(app: AppEntry, delta: Int) = viewModel.moveFavorite(app, delta)
    override fun hide(app: AppEntry) = viewModel.hide(app)
    override fun unhide(app: AppEntry) = viewModel.unhide(app)
    override fun setAccent(index: Int) = viewModel.setAccent(index)
    override fun setAmbient(enabled: Boolean) = viewModel.setAmbient(enabled)

    private fun packageUri(app: AppEntry) = Uri.fromParts("package", app.packageName, null)

    private fun start(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
