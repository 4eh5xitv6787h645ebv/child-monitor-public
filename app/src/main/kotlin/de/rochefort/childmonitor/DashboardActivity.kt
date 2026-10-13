package de.rochefort.childmonitor

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class DashboardActivity : Activity() {

    private lateinit var dashboardSummary: TextView
    private lateinit var dashboardServiceStatus: TextView
    private lateinit var dashboardLastEndpoint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        ThemeManager.applyTheme(this)

        dashboardSummary = findViewById(R.id.dashboardSummary)
        dashboardServiceStatus = findViewById(R.id.dashboardServiceStatus)
        dashboardLastEndpoint = findViewById(R.id.dashboardLastEndpoint)

        findViewById<Button>(R.id.dashboardOpenChildButton).setOnClickListener {
            openChildMode()
        }
        findViewById<Button>(R.id.dashboardOpenParentButton).setOnClickListener {
            startActivity(Intent(this, DiscoverActivity::class.java))
        }
        findViewById<Button>(R.id.dashboardSetupButton).setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        findViewById<Button>(R.id.dashboardPairingButton).setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        findViewById<Button>(R.id.dashboardDiagnosticsButton).setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<Button>(R.id.dashboardTimelineButton).setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }
        findViewById<Button>(R.id.dashboardAudioCalibrationButton).setOnClickListener {
            startActivity(Intent(this, AudioCalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.dashboardLogsButton).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        refreshStatus()
    }

    private fun refreshStatus() {
        val childRunning = isServiceRunning(MonitorService::class.java)
        val parentRunning = isServiceRunning(ListenService::class.java)
        val unrestricted = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)

        dashboardSummary.text = buildString {
            append("Battery mode: ")
            append(if (unrestricted) "Unrestricted" else "Restricted")
            append("\n")
            append("Quick reconnect entries: ")
            append(connectedHistoryCount())
        }

        dashboardServiceStatus.text = buildString {
            append("Child session: ")
            append(if (childRunning) "Running" else "Idle")
            append("\n")
            append("Parent session: ")
            append(if (parentRunning) "Running" else "Idle")
        }

        dashboardLastEndpoint.text = buildString {
            append("Most recent target:\n")
            append(lastConnectedEndpoint() ?: "No saved endpoints")
        }
    }

    private fun openChildMode() {
        if (hasChildPermissions()) {
            startActivity(Intent(this, MonitorActivity::class.java))
            return
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
            REQUEST_CHILD_PERMISSIONS
        )
    }

    private fun hasChildPermissions(): Boolean {
        val hasAudio = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        return hasAudio && hasCamera
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == serviceClass.name && it.foreground
        }
    }

    private fun connectedHistoryCount(): Int {
        val prefs = getSharedPreferences("ListenPreferences", MODE_PRIVATE)
        val raw = prefs.getString("ipHistory", "") ?: ""
        if (raw.isBlank()) return 0
        return raw.split(",").count { it.isNotBlank() }
    }

    private fun lastConnectedEndpoint(): String? {
        val prefs = getSharedPreferences("ListenPreferences", MODE_PRIVATE)
        val raw = prefs.getString("ipHistory", "") ?: return null
        if (raw.isBlank()) return null
        return raw.split(",").firstOrNull { it.isNotBlank() }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CHILD_PERMISSIONS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                startActivity(Intent(this, MonitorActivity::class.java))
            } else {
                AppToast.show(this, "Microphone and camera permissions are required.", Toast.LENGTH_SHORT)
            }
        }
    }

    companion object {
        private const val REQUEST_CHILD_PERMISSIONS = 4101
    }
}
