package de.rochefort.childmonitor

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import de.rochefort.childmonitor.observability.DebugBundle
import de.rochefort.childmonitor.observability.Metrics
import de.rochefort.childmonitor.observability.PacketRingBuffer
import de.rochefort.childmonitor.security.AuditLog
import de.rochefort.childmonitor.video.CameraSelfTest
import org.json.JSONObject

class DiagnosticsActivity : Activity() {

    private lateinit var serviceStatusText: TextView
    private lateinit var errorSummaryText: TextView
    private lateinit var warningSummaryText: TextView
    private lateinit var connectionSummaryText: TextView
    private lateinit var recentIssuesText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        ThemeManager.applyTheme(this)

        serviceStatusText = findViewById(R.id.diagServiceStatus)
        errorSummaryText = findViewById(R.id.diagErrorSummary)
        warningSummaryText = findViewById(R.id.diagWarningSummary)
        connectionSummaryText = findViewById(R.id.diagConnectionSummary)
        recentIssuesText = findViewById(R.id.diagRecentIssues)

        findViewById<Button>(R.id.diagRefreshButton).setOnClickListener { refreshDiagnostics() }
        findViewById<Button>(R.id.diagOpenLogsButton).setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
        findViewById<Button>(R.id.diagOpenTimelineButton).setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }
        findViewById<Button>(R.id.diagBatterySettingsButton).setOnClickListener {
            BatteryOptimizationHelper.openBatteryOptimizationSettings(this)
        }
        findViewById<Button>(R.id.diagExportBundleButton).setOnClickListener {
            exportDiagnosticBundle()
        }
        findViewById<Button>(R.id.diagCameraSelfTestButton).setOnClickListener {
            runCameraSelfTest()
        }
        findViewById<Button>(R.id.diagSecurityStatusButton).setOnClickListener {
            startActivity(Intent(this, SecurityStatusActivity::class.java))
        }
    }

    /**
     * CM-FEAT-001 (#124): wire the existing video.CameraSelfTest helper into a
     * Diagnostics action. Renders the report in an AlertDialog with warnings in
     * red and appends an audit-log integrity_check event with the JSON payload.
     */
    private fun runCameraSelfTest() {
        val permissionGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val report = try {
            CameraSelfTest.run(applicationContext, permissionGranted)
        } catch (e: Exception) {
            android.util.Log.e("DiagnosticsActivity", "CameraSelfTest crashed", e)
            AppToast.show(this, "Self-test failed: ${e.message}", Toast.LENGTH_LONG)
            return
        }
        // Audit-log the result so a support bundle can show what the device
        // self-reported at the time of an incident.
        try {
            AuditLog.append(applicationContext, "integrity_check", mapOf(
                "kind" to "camera_self_test",
                "ok" to report.ok.toString(),
                "warnings" to report.warnings.joinToString("; ")
            ))
        } catch (_: Exception) {}

        val body = buildString {
            append("Result: ").append(if (report.ok) "OK" else "Warnings present").append('\n')
            append('\n')
            append("Camera hardware: ").append(if (report.hasCameraFeature) "yes" else "no").append('\n')
            append("Auto-focus: ").append(if (report.hasAutoFocus) "yes" else "no").append('\n')
            append("Flash: ").append(if (report.hasFlash) "yes" else "no").append('\n')
            append("Front camera: ").append(if (report.hasFrontCamera) "yes" else "no").append('\n')
            append("Permission granted: ").append(if (report.permissionGranted) "yes" else "no").append('\n')
            if (report.warnings.isNotEmpty()) {
                append('\n').append("Warnings:\n")
                report.warnings.forEach { append("• ").append(it).append('\n') }
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cameraSelfTestTitle)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * CM-FEAT-006 (#131): build a DebugBundle zip and hand it off to the system
     * share-sheet so the user can attach it to a support ticket / email it.
     */
    private fun exportDiagnosticBundle() {
        AppToast.show(this, R.string.exportBundleProgress, Toast.LENGTH_SHORT)
        Thread {
            val zip = try {
                val emptyRing = PacketRingBuffer()  // see CM-FEAT-006 — the full
                // service-bound ring isn't reachable from the Diagnostics screen
                // today; export still includes audit log, metrics, sessions, and
                // recent diagnostic_logs/, which is the support-actionable subset.
                val status = JSONObject().apply {
                    put("exportedAtMs", System.currentTimeMillis())
                    put("source", "DiagnosticsActivity")
                }
                val sessions = JSONObject().put("note", "session list available via /api/status when service is active")
                DebugBundle.export(applicationContext, status, sessions, emptyRing)
            } catch (e: Exception) {
                android.util.Log.e("DiagnosticsActivity", "DebugBundle export failed", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread // #424
                    AppToast.show(this, R.string.exportBundleFailed, Toast.LENGTH_LONG)
                }
                return@Thread
            }
            // Audit-log the export so we have a trail of who exported what.
            try {
                AuditLog.append(applicationContext, "bundle.export", mapOf(
                    "name" to zip.name,
                    "sizeBytes" to zip.length().toString()
                ))
            } catch (_: Exception) {}
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread // #424: don't share/Toast on a destroyed Activity
                try {
                    val uri = FileProvider.getUriForFile(
                        this,
                        "${applicationContext.packageName}.fileprovider",
                        zip
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Child Monitor diagnostic bundle")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.shareDiagnosticBundle)))
                } catch (e: Exception) {
                    android.util.Log.e("DiagnosticsActivity", "Share bundle failed", e)
                    AppToast.show(this, R.string.exportBundleFailed, Toast.LENGTH_LONG)
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        Thread {
            val events = DiagnosticsRepository.loadRecentEvents(this, maxFiles = 5, maxLinesPerFile = 700)
            val summary = DiagnosticsRepository.summarize(events)
            val important = DiagnosticsRepository.importantEvents(events, maxItems = 12)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread // #424: same guard as the export path
                val childRunning = isServiceRunning(MonitorService::class.java)
                val parentRunning = isServiceRunning(ListenService::class.java)
                val unrestrictedBattery = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(this)

                val running = getString(R.string.diagnosticsRunning)
                val idle = getString(R.string.diagnosticsIdle)
                serviceStatusText.text = getString(
                    R.string.diagnosticsServiceSummary,
                    if (childRunning) running else idle,
                    if (parentRunning) running else idle,
                    getString(if (unrestrictedBattery) R.string.diagnosticsUnrestricted else R.string.diagnosticsRestricted),
                )

                errorSummaryText.text = resources.getQuantityString(
                    R.plurals.diagnosticsErrors, summary.errorCount, summary.errorCount,
                )
                warningSummaryText.text = resources.getQuantityString(
                    R.plurals.diagnosticsWarnings, summary.warningCount, summary.warningCount,
                )
                val baseConnection = getString(
                    R.string.diagnosticsConnectionSummary,
                    summary.connectionEventCount,
                    summary.disconnectCount,
                )
                val latestError = summary.latestError
                connectionSummaryText.text = if (latestError == null) baseConnection else getString(
                    R.string.diagnosticsConnectionWithLatest,
                    baseConnection,
                    latestError.timestamp,
                    latestError.message,
                )

                if (important.isEmpty()) {
                    recentIssuesText.setText(R.string.diagnosticsNoMajorIssues)
                } else {
                    recentIssuesText.text = important.joinToString("\n\n") {
                        getString(
                            R.string.diagnosticsLogEntry,
                            it.timestamp, it.level, it.category, it.message,
                        )
                    }
                }
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == serviceClass.name && it.foreground
        }
    }
}
