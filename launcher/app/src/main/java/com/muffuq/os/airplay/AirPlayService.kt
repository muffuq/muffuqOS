package com.muffuq.os.airplay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.muffuq.os.R
import com.muffuq.os.stage.NowPlayingSource

/**
 * Keeps the AirPlay receiver running in the background so the TV is always in the iPhone's Screen Mirroring
 * list, even while another app is on screen. Foreground service, otherwise Android stops it within a minute.
 */
class AirPlayService : Service() {
    private var server: AirPlayServer? = null
    private var advertiser: Advertiser? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        val identity = DeviceIdentity(this)
        val server = AirPlayServer(identity, RECEIVER_NAME, TvVolume(this), object : AirPlayServer.Events {
            override fun onMirroringStarted() = showMirrorScreen()
            override fun onSessionEnded() = Unit
        })
        try {
            server.start()
        } catch (e: Exception) {
            Log.e(TAG, "could not start the AirPlay receiver", e)
            stopSelf()
            return
        }
        this.server = server
        advertiser = Advertiser(this, identity, RECEIVER_NAME, server.port).also { it.register() }
        MirrorBus.stopRequest = server::disconnectActive
        // Keeps the music going when Home is pressed (this TV pauses it); see NowPlayingSource.undoHomePause.
        NowPlayingSource.get(this).guardHome()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        MirrorBus.stopRequest = null
        advertiser?.unregister()
        server?.stop()
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.airplay_channel), NotificationManager.IMPORTANCE_MIN),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.airplay_ready))
            .setContentText(getString(R.string.airplay_ready_hint, RECEIVER_NAME))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun showMirrorScreen() {
        startActivity(
            Intent(this, MirrorActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    /** Brings the receiver up after a reboot, since the TV may boot into Live TV instead of the launcher. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) start(context)
        }
    }

    companion object {
        private const val TAG = "MuffuqAirPlay"
        private const val CHANNEL_ID = "airplay"
        private const val NOTIFICATION_ID = 7000
        const val RECEIVER_NAME = "muffuqOS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AirPlayService::class.java))
        }
    }
}
