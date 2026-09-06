package com.filebridge.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var statusLabel: TextView
    private lateinit var urlLabel: TextView
    private lateinit var toggleButton: Button
    private lateinit var passwordField: EditText

    private val permissions: Array<String>
        get() {
            val base = mutableListOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (Build.VERSION.SDK_INT >= 33) {
                base.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            return base.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusLabel = findViewById(R.id.statusLabel)
        urlLabel = findViewById(R.id.urlLabel)
        toggleButton = findViewById(R.id.toggleButton)
        passwordField = findViewById(R.id.passwordField)

        requestNeededPermissions()

        toggleButton.setOnClickListener {
            if (WebServerService.isRunning) {
                stopService(Intent(this, WebServerService::class.java))
            } else {
                ServerConfig.password = passwordField.text.toString().trim()
                val intent = Intent(this, WebServerService::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            }
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun requestNeededPermissions() {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun refreshUi() {
        val running = WebServerService.isRunning
        statusLabel.text = if (running) "Server running" else "Server stopped"
        toggleButton.text = if (running) "Stop Server" else "Start Server"
        if (running) {
            val ip = getLocalIpAddress()
            urlLabel.text = "http://$ip:${ServerConfig.PORT}"
        } else {
            urlLabel.text = ""
        }
    }

    private fun getLocalIpAddress(): String {
        return try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            val ipInt = wifiManager.connectionInfo.ipAddress
            if (ipInt != 0) {
                Formatter.formatIpAddress(ipInt)
            } else {
                fallbackIp()
            }
        } catch (e: Exception) {
            fallbackIp()
        }
    }

    private fun fallbackIp(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            var found = "unknown"
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        found = addr.hostAddress ?: found
                    }
                }
            }
            found
        } catch (e: Exception) {
            "unknown"
        }
    }
}
