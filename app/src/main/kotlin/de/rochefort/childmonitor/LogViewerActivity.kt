package de.rochefort.childmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Activity for viewing diagnostic logs.
 * Allows users to view recent logs and share all logs for analysis.
 */
class LogViewerActivity : Activity() {

    private lateinit var logTextView: TextView
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create UI programmatically
        scrollView = ScrollView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(16, 16, 16, 16)
        }

        logTextView = TextView(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 10f
            setTextIsSelectable(true)
        }

        scrollView.addView(logTextView)
        setContentView(scrollView)

        actionBar?.setDisplayHomeAsUpEnabled(true)
        actionBar?.title = "Diagnostic Logs"

        loadLogs()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_REFRESH, 0, "Refresh").apply {
            setIcon(android.R.drawable.ic_menu_rotate)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add(0, MENU_SHARE, 0, "Share All Logs").apply {
            setIcon(android.R.drawable.ic_menu_share)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        menu.add(0, MENU_CLEAR, 0, "Clear Logs").apply {
            setIcon(android.R.drawable.ic_menu_delete)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            MENU_REFRESH -> {
                loadLogs()
                AppToast.show(this, "Logs refreshed", Toast.LENGTH_SHORT)
                true
            }
            MENU_SHARE -> {
                shareAllLogs()
                true
            }
            MENU_CLEAR -> {
                clearLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun loadLogs() {
        Thread {
            val logs = LogManager.getRecentLogs(1000)
            val logFiles = LogManager.getLogFiles()

            val fileRange = if (logFiles.isEmpty()) "" else getString(
                R.string.logViewerFileRange,
                logFiles.last().name,
                logFiles.first().name,
            )
            val header = getString(R.string.logViewerHeader, logFiles.size, fileRange)

            runOnUiThread {
                // #424: the user may have left while this background load ran — touching the
                // destroyed Activity's views/Toast would crash. isFinishing alone misses
                // config-change destroys, so check both.
                if (isFinishing || isDestroyed) return@runOnUiThread
                logTextView.text = getString(R.string.logViewerContent, header, logs)
                scrollView.post {
                    scrollView.fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }.start()
    }

    private fun shareAllLogs() {
        AppToast.show(this, "Preparing logs for sharing...", Toast.LENGTH_SHORT)

        Thread {
            try {
                // Export all logs to a single file. #206: write under the
                // dedicated shared_exports/ subdir that the FileProvider maps, so
                // the provider no longer exposes the whole cache root.
                val exportedLogs = LogManager.exportAllLogs()
                val exportDir = File(cacheDir, "shared_exports").also { it.mkdirs() }
                val exportFile = File(exportDir, "child_monitor_diagnostic_logs.txt")
                exportFile.writeText(exportedLogs)

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread // #424
                    try {
                        val uri = FileProvider.getUriForFile(
                            this,
                            "${applicationContext.packageName}.fileprovider",
                            exportFile
                        )

                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Child Monitor Diagnostic Logs")
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Diagnostic logs from Child Monitor app. " +
                                        "These logs can help diagnose connection issues."
                            )
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }

                        startActivity(Intent.createChooser(shareIntent, "Share logs via"))
                    } catch (e: Exception) {
                        AppToast.show(
                            this,
                            "Failed to share logs: ${e.message}",
                            Toast.LENGTH_LONG
                        )
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread // #424
                    AppToast.show(
                        this,
                        "Failed to export logs: ${e.message}",
                        Toast.LENGTH_LONG
                    )
                }
            }
        }.start()
    }

    private fun clearLogs() {
        AlertDialog.Builder(this)
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to delete all diagnostic logs? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                Thread {
                    val logFiles = LogManager.getLogFiles()
                    var deletedCount = 0
                    logFiles.forEach { file ->
                        if (file.delete()) {
                            deletedCount++
                        }
                    }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread // #424
                        AppToast.show(
                            this,
                            "Deleted $deletedCount log file(s)",
                            Toast.LENGTH_SHORT
                        )
                        loadLogs()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val MENU_REFRESH = 1
        private const val MENU_SHARE = 2
        private const val MENU_CLEAR = 3
    }
}
