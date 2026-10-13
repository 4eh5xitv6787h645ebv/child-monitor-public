package de.rochefort.childmonitor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class SetupWizardActivity : Activity() {

    private lateinit var corePermissionsStatus: TextView
    private lateinit var multicastStatus: TextView
    private lateinit var notificationStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var autoStartStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup_wizard)
        ThemeManager.applyTheme(this)

        corePermissionsStatus = findViewById(R.id.wizardCorePermStatus)
        multicastStatus = findViewById(R.id.wizardMulticastStatus)
        notificationStatus = findViewById(R.id.wizardNotificationStatus)
        batteryStatus = findViewById(R.id.wizardBatteryStatus)
        autoStartStatus = findViewById(R.id.wizardAutoStartStatus)

        findViewById<Button>(R.id.wizardRequestCorePermsButton).setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                REQUEST_CORE_PERMISSIONS
            )
        }
        findViewById<Button>(R.id.wizardRequestMulticastButton).setOnClickListener {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE),
                REQUEST_MULTICAST_PERMISSION
            )
        }
        findViewById<Button>(R.id.wizardRequestNotificationButton).setOnClickListener {
            requestNotificationPermissionIfNeeded()
        }
        findViewById<Button>(R.id.wizardBatterySettingsButton).setOnClickListener {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
        }
        findViewById<Button>(R.id.wizardToggleAutoStartButton).setOnClickListener {
            toggleAutoStart()
        }
        findViewById<Button>(R.id.wizardMarkCompleteButton).setOnClickListener {
            getSharedPreferences(SETUP_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SETUP_COMPLETE, true)
                .apply()
            AppToast.show(this, "Setup marked complete.", Toast.LENGTH_SHORT)
            finish()
        }
        findViewById<Button>(R.id.wizardOpenChildButton).setOnClickListener {
            openChildMode()
        }
        findViewById<Button>(R.id.wizardOpenParentButton).setOnClickListener {
            startActivity(Intent(this, DiscoverActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        refreshStatus()
    }

    private fun refreshStatus() {
        corePermissionsStatus.text = getString(R.string.setupCorePermissionStatus, okOrMissing(hasCorePermissions()))
        multicastStatus.text = getString(R.string.setupMulticastPermissionStatus, okOrMissing(hasMulticastPermission()))
        notificationStatus.text = getString(R.string.setupNotificationPermissionStatus, okOrMissing(hasNotificationPermission()))
        batteryStatus.text = getString(R.string.setupBatteryStatus, okOrMissing(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)))
        autoStartStatus.text = getString(R.string.setupAutoStartStatus, okOrMissing(isAutoStartEnabled()))
    }

    private fun okOrMissing(ok: Boolean): String =
        getString(if (ok) R.string.setupStatusOk else R.string.setupStatusMissing)

    private fun hasCorePermissions(): Boolean {
        val hasAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        return hasAudio && hasCamera
    }

    private fun hasMulticastPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CHANGE_WIFI_MULTICAST_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AppToast.show(this, "Notification permission not required on this Android version.", Toast.LENGTH_SHORT)
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION
        )
    }

    private fun toggleAutoStart() {
        val prefs = getSharedPreferences("ListenPreferences", MODE_PRIVATE)
        // Disabling is always allowed; enabling goes through the gate.
        if (prefs.getBoolean(AutoStartGate.KEY_CHILD, false)) {
            prefs.edit().putBoolean(AutoStartGate.KEY_CHILD, false).apply()
            refreshStatus()
        } else {
            tryEnableChildAutoStart()
        }
    }

    /**
     * Gate child auto-start via the shared AutoStartGate so the wizard ALSO requires the overlay
     * permission (previously skipped — boot-launch then silently failed on Android 10+). Re-invoked
     * from onActivityResult so it persists once the overlay grant returns (no manual re-tap needed,
     * matching StartActivity/Settings).
     */
    private fun tryEnableChildAutoStart() {
        val prefs = getSharedPreferences("ListenPreferences", MODE_PRIVATE)
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        when (AutoStartGate.decideEnable(AutoStartGate.Role.CHILD, hasCorePermissions(), hasSavedChild = true, canDrawOverlays = canOverlay)) {
            AutoStartGate.Decision.PERSIST ->
                prefs.edit().putBoolean(AutoStartGate.KEY_CHILD, true).apply()
            AutoStartGate.Decision.REQUEST_PERMISSIONS ->
                AppToast.show(this, "Enable mic and camera permissions first.", Toast.LENGTH_SHORT)
            AutoStartGate.Decision.REQUEST_OVERLAY -> {
                AppToast.show(this, R.string.autoStartOverlayNeeded, Toast.LENGTH_LONG)
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQUEST_AUTOSTART_OVERLAY
                )
            }
            AutoStartGate.Decision.BLOCK_NO_SAVED_CHILD -> { /* not reachable for the child role */ }
        }
        refreshStatus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Returned from the overlay grant screen — re-run the gate so auto-start persists if granted.
        if (requestCode == REQUEST_AUTOSTART_OVERLAY) tryEnableChildAutoStart()
    }

    private fun isAutoStartEnabled(): Boolean {
        return getSharedPreferences("ListenPreferences", MODE_PRIVATE)
            .getBoolean(AutoStartGate.KEY_CHILD, false)
    }

    private fun openChildMode() {
        if (hasCorePermissions()) {
            startActivity(Intent(this, MonitorActivity::class.java))
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
            REQUEST_CORE_PERMISSIONS
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
        if (requestCode == REQUEST_CORE_PERMISSIONS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                AppToast.show(this, "Core permissions granted.", Toast.LENGTH_SHORT)
            }
        }
    }

    companion object {
        private const val SETUP_PREFS = "SetupPreferences"
        private const val KEY_SETUP_COMPLETE = "setupWizardCompleted"
        private const val REQUEST_CORE_PERMISSIONS = 4201
        private const val REQUEST_MULTICAST_PERMISSION = 4202
        private const val REQUEST_NOTIFICATION_PERMISSION = 4203
        private const val REQUEST_AUTOSTART_OVERLAY = 4204
    }
}
