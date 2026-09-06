package com.flishly.server

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var statusLabel: TextView
    private lateinit var urlLabel: TextView
    private lateinit var toggleButton: Button
    private lateinit var passwordField: EditText
    private lateinit var networkSpinner: Spinner
    private lateinit var batteryButton: Button
    private var spinnerHosts: List<String> = emptyList()
    private var updatingSpinner = false

    private val permissions: Array<String>
        get() {
            val base = mutableListOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) base.add(Manifest.permission.POST_NOTIFICATIONS)
            return base.toTypedArray()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusLabel = findViewById(R.id.statusLabel)
        urlLabel = findViewById(R.id.urlLabel)
        toggleButton = findViewById(R.id.toggleButton)
        passwordField = findViewById(R.id.passwordField)
        networkSpinner = findViewById(R.id.networkSpinner)
        batteryButton = findViewById(R.id.batteryButton)
        requestNeededPermissions()
        refreshNetworkOptions()

        networkSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingSpinner || spinnerHosts.isEmpty()) return
                val host = spinnerHosts.getOrNull(position) ?: ""
                saveSelectedHost(host)
                if (WebServerService.isRunning) {
                    val intent = Intent(this@MainActivity, WebServerService::class.java)
                        .putExtra(WebServerService.EXTRA_HOST, host.ifEmpty { null })
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
                    Toast.makeText(this@MainActivity, "Network address changed; server restarted", Toast.LENGTH_SHORT).show()
                }
                refreshUi()
            }
        }

        toggleButton.setOnClickListener {
            if (WebServerService.isRunning) {
                stopService(Intent(this, WebServerService::class.java))
            } else {
                ServerConfig.password = passwordField.text.toString().trim()
                val host = selectedHost()
                saveSelectedHost(host)
                val intent = Intent(this, WebServerService::class.java)
                    .putExtra(WebServerService.EXTRA_HOST, host.ifEmpty { null })
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            }
            refreshUi()
        }
        batteryButton.setOnClickListener { openBatteryOptimizationSettings() }
    }

    override fun onResume() {
        super.onResume()
        refreshNetworkOptions()
        refreshUi()
        refreshBatteryButton()
    }

    private fun requestNeededPermissions() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    private fun refreshUi() {
        val running = WebServerService.isRunning
        statusLabel.text = if (running) "Server running" else "Server stopped"
        toggleButton.text = if (running) "Stop Server" else "Start Server"
        urlLabel.text = if (running) "http://${selectedHost().ifEmpty { getLocalIpAddress() }}:${ServerConfig.PORT}" else ""
    }

    private fun refreshNetworkOptions() {
        val addresses = getLocalIpAddresses()
        val options = listOf("Automatic (all network interfaces)") + addresses
        spinnerHosts = listOf("") + addresses
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val saved = getSharedPreferences(ServerConfig.PREFS, MODE_PRIVATE).getString(ServerConfig.HOST_PREF, "") ?: ""
        updatingSpinner = true
        networkSpinner.adapter = adapter
        val index = spinnerHosts.indexOf(saved).let { if (it >= 0) it else 0 }
        networkSpinner.setSelection(index, false)
        updatingSpinner = false
    }

    private fun selectedHost(): String = spinnerHosts.getOrNull(networkSpinner.selectedItemPosition) ?: ""

    private fun saveSelectedHost(host: String) {
        getSharedPreferences(ServerConfig.PREFS, MODE_PRIVATE).edit().putString(ServerConfig.HOST_PREF, host).apply()
    }

    private fun getLocalIpAddresses(): List<String> {
        val found = linkedSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) found.add(addr.hostAddress ?: "")
                }
            }
        } catch (_: Exception) {
            try {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val ip = wifiManager.connectionInfo.ipAddress
                if (ip != 0) found.add(Formatter.formatIpAddress(ip))
            } catch (_: Exception) { }
        }
        return found.filter { it.isNotBlank() }.sorted()
    }

    private fun getLocalIpAddress(): String = getLocalIpAddresses().firstOrNull() ?: "unknown"

    private fun refreshBatteryButton() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val exempt = Build.VERSION.SDK_INT < 23 || pm.isIgnoringBatteryOptimizations(packageName)
        batteryButton.text = if (exempt) "Background protection: allowed" else "Allow background running"
    }

    private fun openBatteryOptimizationSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:$packageName")))
            }
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this, "Open Android battery settings and allow Flishly to run in the background", Toast.LENGTH_LONG).show() }
        }
    }
}
