/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor.nighttimeline

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.events.EventStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Night timeline screen (#283): renders the last ~12 hours of per-minute noise + motion
 * recorded locally by [NightRecorder]. Read-only; all data is on-device.
 */
class NightTimelineActivity : Activity() {

    private lateinit var store: NightMetricsStore
    private lateinit var view: NightTimelineView
    private lateinit var summary: TextView
    private lateinit var sensitivityButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_night_timeline)
        ThemeManager.applyTheme(this)
        store = NightMetricsStore(this)
        view = findViewById(R.id.nightTimelineView)
        summary = findViewById(R.id.nightTimelineSummary)
        findViewById<Button>(R.id.nightTimelineRefresh).setOnClickListener { load() }
        sensitivityButton = findViewById(R.id.nightTimelineSensitivity)
        sensitivityButton.setOnClickListener {
            // #285: one-tap cycle Low → Medium → High; reclassify + redraw immediately.
            Sensitivity.save(this, Sensitivity.next(Sensitivity.load(this)))
            load()
        }
        findViewById<Button>(R.id.nightTimelineExport).setOnClickListener { showExportDialog() }
        // #314: open the multi-night sleep-trends view.
        findViewById<Button>(R.id.nightTimelineTrends).setOnClickListener {
            startActivity(Intent(this, MultiNightTrendsActivity::class.java))
        }
        load()
    }

    /** #309: let the parent pick a format, then export the on-device sleep + event log to share. */
    private fun showExportDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.nightTimelineExport)
            .setItems(
                arrayOf(
                    getString(R.string.nightTimelineExportCsv),
                    getString(R.string.nightTimelineExportJson),
                )
            ) { _, which -> exportLog(asJson = which == 1) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportLog(asJson: Boolean) {
        Thread {
            val now = System.currentTimeMillis()
            // Export everything retained (30 days) ending at the last recorded minute (or now).
            val end = (store.latestMinute() ?: now) + 60_000L
            val start = end - NightMetricsStore.RETENTION_DAYS * 24L * 60L * 60L * 1000L
            val buckets = try { store.range(start, end) } catch (_: Exception) { emptyList() }
            val events = try {
                // #424: applicationContext, not the Activity — SQLiteOpenHelper retains its Context for
                // the helper's lifetime, so passing `this` keeps the Activity alive on the background
                // export thread after the user leaves. Mirrors NightMetricsStore's applicationContext use.
                val es = EventStore(applicationContext)
                val arr = try { es.range(start, end) } finally { es.close() }
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ExportEvent(
                        tsMs = o.getLong("tsMs"),
                        type = o.getString("type"),
                        confidence = o.getDouble("confidence"),
                        durationMs = o.getLong("durationMs"),
                        peakDb = o.getDouble("peakDb"),
                    )
                }
            } catch (_: Exception) { emptyList() }

            if (buckets.isEmpty() && events.isEmpty()) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, R.string.nightTimelineExportEmpty, Toast.LENGTH_LONG).show()
                    }
                }
                return@Thread
            }

            val outFile: File
            try {
                val outDir = File(filesDir, "exports").also { it.mkdirs() }
                // Sweep exports older than 24h so the dir doesn't grow unbounded (mirrors tracker export).
                outDir.listFiles()?.forEach { if (it.lastModified() < now - 86_400_000L) it.delete() }
                val ext = if (asJson) "json" else "csv"
                outFile = File(outDir, "sleep-log-$now.$ext")
                val content = if (asJson) {
                    SleepLogExport.toJson(buckets, events, now, start, end)
                } else {
                    SleepLogExport.toCsv(buckets, events)
                }
                outFile.writeText(content, Charsets.UTF_8)
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(this, R.string.nightTimelineExportFailed, Toast.LENGTH_LONG).show()
                    }
                }
                return@Thread
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    val uri = FileProvider.getUriForFile(
                        this, "${applicationContext.packageName}.fileprovider", outFile
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = if (asJson) "application/json" else "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, getString(R.string.nightTimelineExportSubject))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.nightTimelineExportShareTitle)))
                } catch (_: Exception) {
                    Toast.makeText(this, R.string.nightTimelineExportFailed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        load()
    }

    private fun load() {
        Thread {
            val sens = Sensitivity.load(this)
            // #283: anchor the window to the most recent RECORDED minute (not wall-clock now), so
            // "open it the next morning" shows last night ending at the last sample — not a mostly-
            // empty 12h ending now with the night clipped.
            val end = (store.latestMinute() ?: System.currentTimeMillis()) + 60_000L
            val start = end - WINDOW_MS
            val raw = try { store.range(start, end) } catch (_: Exception) { emptyList() }
            // #285: reclassify at render with the current sensitivity so the band + threshold line
            // reflect the live setting (the stored state was captured at the then-current value).
            val buckets = raw.map { it.copy(state = SleepState.classify(it, sens.awakeDbfs, sens.motionThresholdPercent)) }
            val text = summarize(buckets, sens.motionThresholdPercent)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                view.setData(buckets, start, end, sens.awakeDbfs)
                summary.text = text
                sensitivityButton.text = getString(R.string.nightTimelineSensitivity, sens.label)
            }
        }.start()
    }

    override fun onDestroy() {
        try { store.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun summarize(buckets: List<MinuteBucket>, motionThreshold: Int): String {
        if (buckets.isEmpty()) {
            return getString(R.string.nightTimelineEmpty)
        }
        val asleep = buckets.count { it.state == NightState.ASLEEP }
        val noisy = buckets.count { it.state == NightState.NOISY }
        val awake = buckets.count { it.state == NightState.AWAKE }
        val motionMinutes = buckets.count { it.motion >= motionThreshold }
        var longest = 0
        var run = 0
        for (b in buckets) {
            if (b.state == NightState.ASLEEP) { run++; if (run > longest) longest = run } else run = 0
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val span = "${fmt.format(buckets.first().minuteEpochMs)}–${fmt.format(buckets.last().minuteEpochMs)}"
        return "$span\n" +
            "Asleep ${fmtMin(asleep)} · longest ${fmtMin(longest)}\n" +
            "Awake/movement ${fmtMin(awake)} · noisy ${fmtMin(noisy)}\n" +
            "Movement minutes: $motionMinutes"
    }

    private fun fmtMin(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    companion object {
        private const val WINDOW_MS = 12L * 60L * 60L * 1000L
    }
}
