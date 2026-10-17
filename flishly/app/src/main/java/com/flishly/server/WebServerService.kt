package com.flishly.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class WebServerService : Service() {

    private var httpServer: BridgeHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        var isRunning: Boolean = false
            private set
        var activeHost: String? = null
            private set
        // Notifies the main screen the moment server state actually changes,
        // so the Start/Stop button never feels delayed.
        var stateListener: (() -> Unit)? = null
        const val CHANNEL_ID = "flishly_channel"
        const val NOTIF_ID = 1
        const val EXTRA_HOST = "bind_host"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (prefs().getBoolean(ServerConfig.WAKE_LOCK_PREF, true)) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Flishly::ServerWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (prefs().getBoolean(ServerConfig.WIFI_LOCK_PREF, true)) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Flishly::ServerWifiLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        startServer(loadSavedHost())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedHost = intent?.getStringExtra(EXTRA_HOST)
        if (intent != null && requestedHost != activeHost) {
            saveHost(requestedHost)
            if (isRunning) restartServer(requestedHost)
            else startServer(requestedHost)
        }
        return START_STICKY
    }

    private fun prefs() = getSharedPreferences(ServerConfig.PREFS, MODE_PRIVATE)

    private fun startServer(host: String?) {
        try {
            httpServer?.stop()
            httpServer = BridgeHttpServer(applicationContext, host, ServerConfig.currentPort(this))
            httpServer?.start()
            activeHost = host
            isRunning = true
            updateNotification()
            stateListener?.invoke()
        } catch (e: Exception) {
            isRunning = false
            activeHost = null
            stateListener?.invoke()
            stopSelf()
        }
    }

    private fun restartServer(host: String?) {
        httpServer?.stop()
        httpServer = null
        isRunning = false
        startServer(host)
    }

    private fun loadSavedHost(): String? {
        return prefs().getString(ServerConfig.HOST_PREF, "")
            ?.trim()
            ?.ifEmpty { null }
    }

    private fun saveHost(host: String?) {
        prefs().edit().putString(ServerConfig.HOST_PREF, host ?: "").apply()
    }

    override fun onDestroy() {
        httpServer?.stop()
        httpServer = null
        isRunning = false
        activeHost = null
        stateListener?.invoke()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Flishly Server", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val hostText = activeHost ?: "all network interfaces"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Flishly is running")
            .setContentText("Serving on $hostText:${ServerConfig.currentPort(this)}")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification())
    }
}
