package com.flishly.server

import android.content.Context

object ServerConfig {
    const val DEFAULT_PORT = 4444
    const val PREFS = "flishly_settings"
    const val HOST_PREF = "bind_host"
    const val PORT_PREF = "server_port"
    const val WAKE_LOCK_PREF = "wake_lock_enabled"
    const val WIFI_LOCK_PREF = "wifi_lock_enabled"

    fun currentPort(context: Context): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(PORT_PREF, DEFAULT_PORT)
        return if (saved in 1024..65535) saved else DEFAULT_PORT
    }
}
