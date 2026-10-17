package com.flishly.server

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    private lateinit var statusLabel: TextView
    private lateinit var statusDot: View
    private lateinit var urlLabel: TextView
    private lateinit var toggleButton: Button
    private lateinit var passwordField: EditText
    private lateinit var networkSpinner: AutoCompleteTextView
    private lateinit var batteryButton: Button
    private lateinit var authStatusLabel: TextView
    private lateinit var settingsButton: Button
    private val authStore by lazy { AuthStore(this) }
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
        statusDot = findViewById(R.id.statusDot)
        urlLabel = findViewById(R.id.urlLabel)
        toggleButton = findViewById(R.id.toggleButton)
        passwordField = findViewById(R.id.passwordField)
        networkSpinner = findViewById(R.id.networkSpinner)
        batteryButton = findViewById(R.id.batteryButton)
        authStatusLabel = findViewById(R.id.authStatusLabel)
        settingsButton = findViewById(R.id.settingsButton)

        WebServerService.stateListener = { runOnUiThread { refreshUi(true) } }
        requestNeededPermissions()
        refreshNetworkOptions()

        networkSpinner.setOnItemClickListener { _, _, position, _ ->
            if (updatingSpinner || spinnerHosts.isEmpty()) return@setOnItemClickListener
            val host = spinnerHosts.getOrNull(position) ?: ""
            saveSelectedHost(host)
            if (WebServerService.isRunning) {
                val intent = Intent(this, WebServerService::class.java)
                    .putExtra(WebServerService.EXTRA_HOST, host.ifEmpty { null })
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "Network address changed; server restarted", Toast.LENGTH_SHORT).show()
            }
            refreshUi()
        }

        toggleButton.setOnClickListener {
            // Optimistic update: the button reacts instantly, then we confirm
            // with the real state as soon as the service reports back.
            val wasRunning = WebServerService.isRunning
            toggleButton.isEnabled = false
            toggleButton.text = if (wasRunning) "Stopping…" else "Starting…"
            if (wasRunning) {
                stopService(Intent(this, WebServerService::class.java))
            } else {
                val newPassword = passwordField.text.toString().trim()
                if (newPassword.isEmpty()) authStore.clear() else authStore.setPassword(newPassword)
                val host = selectedHost()
                saveSelectedHost(host)
                val intent = Intent(this, WebServerService::class.java)
                    .putExtra(WebServerService.EXTRA_HOST, host.ifEmpty { null })
                ContextCompat.startForegroundService(this, intent)
                passwordField.text.clear()
            }
            toggleButton.postDelayed({
                toggleButton.isEnabled = true
                refreshUi(true)
            }, 900)
        }
        batteryButton.setOnClickListener { openBatteryOptimizationSettings() }
        settingsButton.setOnClickListener { showSettingsDialog() }
    }

    override fun onResume() {
        super.onResume()
        WebServerService.stateListener = { runOnUiThread { refreshUi(true) } }
        refreshNetworkOptions()
        refreshUi()
        refreshBatteryButton()
    }

    override fun onDestroy() {
        if (WebServerService.stateListener != null) WebServerService.stateListener = null
        super.onDestroy()
    }

    private fun requestNeededPermissions() {
        val missing = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
    }

    private fun refreshUi(animate: Boolean = false) {
        val running = WebServerService.isRunning
        toggleButton.isEnabled = true
        statusLabel.text = if (running) "Server running" else "Server stopped"
        toggleButton.text = if (running) "Stop Server" else "Start Server"
        urlLabel.text = if (running) "http://${selectedHost().ifEmpty { getLocalIpAddress() }}:${ServerConfig.currentPort(this)}" else ""
        authStatusLabel.text = if (authStore.hasPassword()) "Password protection: on" else "Password protection: off — anyone on this network can access your files"
        if (animate) {
            val set = AnimatorSet().playTogether(
                ObjectAnimator.ofFloat(statusLabel, "alpha", 0.4f, 1f),
                ObjectAnimator.ofFloat(statusDot, "scaleX", 0.6f, 1f),
                ObjectAnimator.ofFloat(statusDot, "scaleY", 0.6f, 1f)
            )
            set.duration = 250
            set.start()
        }
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences(ServerConfig.PREFS, MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val portInput = view.findViewById<EditText>(R.id.portInput)
        val wakeSwitch = view.findViewById<Switch>(R.id.wakeLockSwitch)
        val wifiSwitch = view.findViewById<Switch>(R.id.wifiLockSwitch)
        portInput.setText(prefs.getInt(ServerConfig.PORT_PREF, ServerConfig.DEFAULT_PORT).toString())
        wakeSwitch.isChecked = prefs.getBoolean(ServerConfig.WAKE_LOCK_PREF, true)
        wifiSwitch.isChecked = prefs.getBoolean(ServerConfig.WIFI_LOCK_PREF, true)

        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(view)
            .setPositiveButton("Save") { d, _ ->
                val port = portInput.text.toString().toIntOrNull() ?: ServerConfig.DEFAULT_PORT
                val valid = port in 1024..65535
                prefs.edit()
                    .putInt(ServerConfig.PORT_PREF, if (valid) port else ServerConfig.DEFAULT_PORT)
                    .putBoolean(ServerConfig.WAKE_LOCK_PREF, wakeSwitch.isChecked)
                    .putBoolean(ServerConfig.WIFI_LOCK_PREF, wifiSwitch.isChecked)
                    .apply()
                if (!valid) Toast.makeText(this, "Invalid port — reset to ${ServerConfig.DEFAULT_PORT}", Toast.LENGTH_SHORT).show()
                else Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                if (WebServerService.isRunning) {
                    Toast.makeText(this, "Restart the server to apply changes", Toast.LENGTH_LONG).show()
                }
                refreshUi()
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun refreshNetworkOptions() {
        val addresses = getLocalIpAddresses()
        val options = listOf("Automatic (all network interfaces)") + addresses
        spinnerHosts = listOf("") + addresses
        val saved = getSharedPreferences(ServerConfig.PREFS, MODE_PRIVATE).getString(ServerConfig.HOST_PREF, "") ?: ""
        updatingSpinner = true
        networkSpinner.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, options))
        val index = spinnerHosts.indexOf(saved)
        networkSpinner.setText(options.getOrNull(if (index >= 0) index else 0), false)
        updatingSpinner = false
    }

    private fun selectedHost(): String = spinnerHosts.getOrNull(optionsIndex()) ?: ""

    private fun optionsIndex(): Int {
        val text = networkSpinner.text.toString()
        val idx = (listOf("Automatic (all network interfaces)") + getLocalIpAddresses()).indexOf(text)
        return if (idx <= 0) 0 else idx
    }

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
