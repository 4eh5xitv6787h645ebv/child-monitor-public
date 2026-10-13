package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.ChildProfile
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.TrackerTime
import de.rochefort.childmonitor.tracker.guidance.DiaperThresholds
import de.rochefort.childmonitor.tracker.guidance.FeedingTargets
import de.rochefort.childmonitor.tracker.guidance.SafeSleepChecklist
import de.rochefort.childmonitor.tracker.guidance.VaccineSchedule
import de.rochefort.childmonitor.util.CsvSafe
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BabyDashboardActivity : Activity() {

    private lateinit var profileStore: ChildProfileStore
    private lateinit var trackerStore: TrackerStore

    private lateinit var nameView: TextView
    private lateinit var ageView: TextView
    private lateinit var summaryView: TextView
    private lateinit var vaccineView: TextView
    private lateinit var guidanceView: TextView
    private lateinit var childSpinner: android.widget.Spinner

    // #426: one quick-entry dialog at a time. A per-invocation savedGuard only stops a double-save
    // within ONE dialog; rapidly double-tapping a quick-add button creates two stacked dialogs that
    // could each be saved. This class-level flag blocks opening a second dialog until the first closes.
    private val dialogShowing = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_baby_dashboard)
        ThemeManager.applyTheme(this)

        profileStore = ChildProfileStore.get(applicationContext)
        trackerStore = TrackerStore.get(applicationContext)

        nameView = findViewById(R.id.babyNameText)
        ageView = findViewById(R.id.babyAgeText)
        summaryView = findViewById(R.id.todaySummaryText)
        vaccineView = findViewById(R.id.nextVaccineText)
        guidanceView = findViewById(R.id.guidanceText)
        childSpinner = findViewById(R.id.childSpinner)

        findViewById<Button>(R.id.btnFeed).setOnClickListener { startEntry(FeedEntryActivity::class.java) }
        findViewById<Button>(R.id.btnDiaper).setOnClickListener { startEntry(DiaperEntryActivity::class.java) }
        findViewById<Button>(R.id.btnSleep).setOnClickListener { startEntry(SleepEntryActivity::class.java) }
        findViewById<Button>(R.id.btnGrowth).setOnClickListener { startEntry(GrowthEntryActivity::class.java) }
        findViewById<Button>(R.id.btnMed).setOnClickListener { startEntry(MedicationEntryActivity::class.java) }
        findViewById<Button>(R.id.btnAllergen).setOnClickListener { startEntry(AllergenActivity::class.java) }
        findViewById<Button>(R.id.btnTimeline).setOnClickListener { startEntry(TrackerTimelineActivity::class.java) }
        findViewById<Button>(R.id.btnVaccine).setOnClickListener { startEntry(VaccineActivity::class.java) }
        findViewById<Button>(R.id.btnMilestones).setOnClickListener { startEntry(MilestonesActivity::class.java) }
        findViewById<Button>(R.id.btnSources).setOnClickListener {
            startActivity(Intent(this, SourcesActivity::class.java))
        }
        findViewById<Button>(R.id.btnProfile).apply {
            setOnClickListener {
                startActivity(Intent(this@BabyDashboardActivity, ChildProfileActivity::class.java))
            }
            setOnLongClickListener {
                startActivity(Intent(this@BabyDashboardActivity, ChildProfileActivity::class.java)
                    .putExtra(ChildProfileActivity.EXTRA_ADD_NEW, true))
                true
            }
        }
        findViewById<Button>(R.id.btnExportEvents).setOnClickListener {
            exportLast30DaysCsv()
        }
        findViewById<Button>(R.id.btnPumping).setOnClickListener {
            promptDurationAndSave(
                titleRes = R.string.tracker_pumping_title,
                askVolume = true,
                kind = TrackerEvent.Kind.FEED_PUMP
            )
        }
        findViewById<Button>(R.id.btnTummy).setOnClickListener {
            promptDurationAndSave(
                titleRes = R.string.tracker_tummy_title,
                askVolume = false,
                kind = TrackerEvent.Kind.ACTIVITY_TUMMY
            )
        }

        maybeShowDisclaimer()
    }

    /**
     * #156 minimal: dialog-based duration entry for kinds that don't need a
     * full Activity (pumping, tummy time). The FEED_PUMP and ACTIVITY_TUMMY
     * enums already exist in TrackerEvent.Kind; this just adds the data path.
     */
    private fun promptDurationAndSave(titleRes: Int, askVolume: Boolean, kind: TrackerEvent.Kind) {
        val profile = profileStore.activeProfile()
        if (profile == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            return
        }
        val durationEdit = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.tracker_field_duration_min)
        }
        val volumeEdit = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.tracker_field_volume_ml)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(durationEdit)
            if (askVolume) addView(volumeEdit)
        }
        // #426: refuse to open a second quick-entry dialog while one is already up (rapid double-tap).
        // CAS here — after the views are built, right before show() — so a failure constructing the
        // views above can't leave the flag stuck true and permanently block future dialogs.
        if (!dialogShowing.compareAndSet(false, true)) return
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.tracker_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val durationRaw = durationEdit.text.toString().trim()
                if (!de.rochefort.childmonitor.tracker.TrackerInputValidation.requiredInt(durationRaw, 1..360)) {
                    Toast.makeText(this, R.string.tracker_field_duration_min, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val minutes = durationRaw.toInt()
                val now = System.currentTimeMillis()
                val started = now - minutes * 60_000L
                // Validate volume same way duration is bounded — accept only
                // finite positives in a realistic pumping range. Otherwise a
                // stray NaN / negative / 1e308 corrupts daily-feed sums and
                // the CSV export (Excel rejects literal NaN/Infinity).
                val volumeRaw = volumeEdit.text.toString().trim()
                val volumeMl = if (askVolume) volumeRaw.toDoubleOrNull() else null
                if (askVolume && !de.rochefort.childmonitor.tracker.TrackerInputValidation
                        .optionalFiniteDouble(volumeRaw, 0.0..2000.0)) {
                    Toast.makeText(this, R.string.tracker_feed_bad_volume, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val event = TrackerEvent(
                    childId = profile.id,
                    kind = kind,
                    tsWallMs = now,
                    startedMs = started,
                    endedMs = now,
                    valueNum = minutes.toDouble(),
                    valueNum2 = volumeMl
                )
                saveButton.isEnabled = false
                TrackerWriteRunner.run(this,
                    write = { trackerStore.insert(event) },
                    onSuccess = { id ->
                        dialog.dismiss()
                        TrackerSaveUndo.show(
                            activity = this,
                            store = trackerStore,
                            childId = profile.id,
                            insertedIds = listOf(id),
                            message = getString(R.string.tracker_saved_with_min, minutes),
                            onUndone = { refresh() },
                        )
                    },
                    onFailure = {
                        saveButton.isEnabled = true
                        Toast.makeText(this, R.string.tracker_save_failed, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
        dialog.setOnDismissListener { dialogShowing.set(false) }
        dialog.show()
    }

    /**
     * #157 partial: write tracker_events for the active child over the past 30
     * days as CSV and hand off to the system share-sheet. The fuller PDF
     * summary + Health Connect bridge from the issue remain follow-ups.
     */
    private fun exportLast30DaysCsv() {
        val profile = profileStore.activeProfile()
        if (profile == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            return
        }
        exportExecutor.execute {
            val since = System.currentTimeMillis() - 30L * TrackerTime.DAY_MS
            val events = try {
                trackerStore.since(profile.id, since)
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.tracker_export_failed, Toast.LENGTH_LONG).show() }
                return@execute
            }
            if (events.isEmpty()) {
                runOnUiThread { Toast.makeText(this, R.string.tracker_export_no_events, Toast.LENGTH_LONG).show() }
                return@execute
            }
            val outDir = java.io.File(filesDir, "exports").also { it.mkdirs() }
            val safeName = profile.name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(30)
            val outFile = java.io.File(outDir, "tracker-$safeName-${System.currentTimeMillis()}.csv")
            try {
                // Sweep old exports (older than 24h) before writing so the
                // exports/ dir doesn't accumulate forever (review #5).
                outDir.listFiles()?.forEach { f ->
                    if (f.lastModified() < System.currentTimeMillis() - 86_400_000L) f.delete()
                }
                outFile.bufferedWriter(Charsets.UTF_8).use { w ->
                    w.write("ts_iso,ts_wall_ms,kind,subkind,value_num,value_num2,started_ms,ended_ms,note\n")
                    // `XXX` is unavailable below API 24. UTC `Z` is unambiguous ISO-8601 and works
                    // across the declared API-21 floor.
                    val iso = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                        timeZone = java.util.TimeZone.getTimeZone("UTC")
                    }
                    for (ev in events) {
                        w.write(CsvSafe.cell(iso.format(Date(ev.tsWallMs))))
                        w.write(",")
                        w.write(ev.tsWallMs.toString()); w.write(",")
                        w.write(CsvSafe.cell(ev.kind.name)); w.write(",")
                        w.write(CsvSafe.cell(ev.subkind)); w.write(",")
                        w.write(ev.valueNum?.toString().orEmpty()); w.write(",")
                        w.write(ev.valueNum2?.toString().orEmpty()); w.write(",")
                        w.write(ev.startedMs?.toString().orEmpty()); w.write(",")
                        w.write(ev.endedMs?.toString().orEmpty()); w.write(",")
                        w.write(CsvSafe.cell(ev.note)); w.write("\n")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, R.string.tracker_export_failed, Toast.LENGTH_LONG).show() }
                return@execute
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        this, "${applicationContext.packageName}.fileprovider", outFile
                    )
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Tracker CSV — ${profile.name}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, getString(R.string.tracker_export_share_title)))
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.tracker_export_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun maybeShowDisclaimer() {
        val prefs = getSharedPreferences(DISCLAIMER_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_ACK, false)) return
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.tracker_disclaimer_title)
            .setMessage(R.string.tracker_disclaimer_body)
            .setCancelable(false)
            .setPositiveButton(R.string.tracker_disclaimer_confirm) { _, _ ->
                prefs.edit().putBoolean(KEY_DISCLAIMER_ACK, true).apply()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        populateChildSpinner()
        refresh()
    }

    private fun populateChildSpinner() {
        val profiles = profileStore.list()
        if (profiles.size < 2) {
            childSpinner.visibility = android.view.View.GONE
            return
        }
        childSpinner.visibility = android.view.View.VISIBLE
        val labels = profiles.map { "${it.name}" }
        val adapter = android.widget.ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )
        childSpinner.adapter = adapter
        val activeId = profileStore.getActiveId()
        val activeIdx = profiles.indexOfFirst { it.id == activeId }.coerceAtLeast(0)
        childSpinner.setSelection(activeIdx, false)
        childSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                // Re-read the list in case it changed (deletion in
                // ChildProfileActivity) between snapshot and select.
                val fresh = profileStore.list()
                val selected = fresh.getOrNull(position) ?: return
                if (selected.id != profileStore.getActiveId()) {
                    profileStore.setActiveId(selected.id)
                    refresh()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun startEntry(cls: Class<*>) {
        val active = ensureActiveProfile() ?: return
        val intent = Intent(this, cls).apply {
            putExtra(EXTRA_CHILD_ID, active.id)
        }
        startActivity(intent)
    }

    private fun ensureActiveProfile(): ChildProfile? {
        val active = profileStore.activeProfile()
        if (active != null) return active
        Toast.makeText(this, getString(R.string.tracker_create_profile_first), Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, ChildProfileActivity::class.java))
        return null
    }

    private fun refresh() {
        val profile = profileStore.activeProfile()
        if (profile == null) {
            nameView.text = getString(R.string.tracker_dashboard_title)
            ageView.text = getString(R.string.tracker_no_profile)
            summaryView.text = ""
            vaccineView.text = ""
            guidanceView.text = ""
            return
        }

        val now = System.currentTimeMillis()
        nameView.text = profile.name
        val ageMonths = profile.chronologicalAgeMonths(now)
        val correctedMonths = profile.correctedAgeMonths(now)
        val ageText = if (profile.isPreterm() && ageMonths != correctedMonths) {
            getString(
                R.string.tracker_age_with_corrected,
                resources.getQuantityString(R.plurals.tracker_months_count, ageMonths, ageMonths),
                resources.getQuantityString(R.plurals.tracker_months_count, correctedMonths, correctedMonths),
            )
        } else {
            resources.getQuantityString(R.plurals.tracker_age_months, ageMonths, ageMonths)
        }
        ageView.text = ageText

        // #171: buildTodaySummary alone issues 7 SQLite queries; buildVaccineSummary
        // does one more; buildGuidance can do a 100-row scan. Running all of these
        // on the main thread blocks onResume for ~10-50ms on cold disk, which is an
        // ANR risk on slow devices and noticeable jank on every dashboard open.
        // Compute the strings on a background thread and post the results back to
        // the UI. Show a placeholder while loading so the previous values don't
        // linger.
        summaryView.text = getString(R.string.tracker_loading)
        vaccineView.text = ""
        guidanceView.text = ""
        dashboardRefreshExecutor.execute {
            val summary = try { buildTodaySummary(profile, now) } catch (e: Exception) {
                android.util.Log.w("BabyDashboard", "buildTodaySummary failed", e); ""
            }
            val vaccine = try { buildVaccineSummary(profile, now) } catch (e: Exception) {
                android.util.Log.w("BabyDashboard", "buildVaccineSummary failed", e); ""
            }
            val guidance = try { buildGuidance(profile, now) } catch (e: Exception) {
                android.util.Log.w("BabyDashboard", "buildGuidance failed", e); ""
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                // Late-arriving query result for an active profile change — drop it.
                if (profileStore.activeProfile()?.id != profile.id) return@runOnUiThread
                summaryView.text = summary
                vaccineView.text = vaccine
                guidanceView.text = guidance
            }
        }
    }

    /** Single-threaded so writes/reads serialise and a fast tap-then-flip doesn't queue
     *  hundreds of refreshes. */
    private val dashboardRefreshExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Separate single-thread pool for CSV exports so a multi-second 30-day write
     *  doesn't stall dashboard refresh taps (review-pass-6 MED). */
    private val exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "BabyDashboard-export").apply { isDaemon = true }
    }

    // #368: CSV cell escaping + injection guard now lives in the shared util.CsvSafe.

    override fun onDestroy() {
        // Drain pending refresh tasks; isFinishing/isDestroyed checks inside the
        // runnable already guard against late posts to views, but we shouldn't
        // leave a worker thread alive after the activity is gone.
        dashboardRefreshExecutor.shutdownNow()
        // Export executor: shutdown() (not shutdownNow) so an in-flight CSV write
        // gets to fsync before the activity dies — interrupting bufferedWriter
        // mid-flush would hand the pediatrician a truncated file (review-pass-6 MED).
        exportExecutor.shutdown()
        super.onDestroy()
    }

    private fun buildTodaySummary(profile: ChildProfile, now: Long): String {
        // "Today" uses the calendar-day anchor (local midnight), not a rolling
        // 24-hour window, so the numbers roll over when the parent notices at
        // 11:59 pm instead of silently shifting at midnight.
        val startOfCalendarDay = TrackerTime.startOfLocalDayMs(now)
        val feedCount = trackerStore.countSince(profile.id, TrackerEvent.Kind.FEED_BREAST, startOfCalendarDay) +
            trackerStore.countSince(profile.id, TrackerEvent.Kind.FEED_BOTTLE, startOfCalendarDay) +
            trackerStore.countSince(profile.id, TrackerEvent.Kind.FEED_SOLID, startOfCalendarDay)
        val wetCount = trackerStore.countSince(profile.id, TrackerEvent.Kind.DIAPER_WET, startOfCalendarDay)
        val dirtyCount = trackerStore.countSince(profile.id, TrackerEvent.Kind.DIAPER_DIRTY, startOfCalendarDay) +
            trackerStore.countSince(profile.id, TrackerEvent.Kind.DIAPER_MIXED, startOfCalendarDay)
        val sleepCount = trackerStore.countSince(profile.id, TrackerEvent.Kind.SLEEP_START, startOfCalendarDay) +
            trackerStore.countSince(profile.id, TrackerEvent.Kind.NAP_START, startOfCalendarDay)
        val lastFeed = trackerStore.lastByKind(profile.id, TrackerEvent.Kind.FEED_BREAST)
            ?: trackerStore.lastByKind(profile.id, TrackerEvent.Kind.FEED_BOTTLE)
        val ageMonths = profile.chronologicalAgeMonths(now)
        val feedTarget = FeedingTargets.feedsPerDayTarget(ageMonths)
        val interval = FeedingTargets.intervalHoursTarget(ageMonths)
        val lastFeedText = lastFeed?.let {
            val hrs = (now - it.tsWallMs) / 3600_000
            val mins = ((now - it.tsWallMs) / 60_000) % 60
            getString(R.string.tracker_last_feed_ago, hrs, mins)
        } ?: getString(R.string.tracker_no_feed_logged)

        // Day-of-life wet-count evaluation (CM-TRK-REV-013).
        // #192-4: anchor both ends to local-midnight so dayOfLife flips at
        // local midnight rather than 24h after birth-instant. See DiaperEntryActivity
        // for the longer rationale — short version: DOB is stored at noon-local,
        // raw subtraction rounded to 0 for most of day-of-birth and shifted the
        // dehydration red-flag thresholds by ~12 h.
        val startOfToday = TrackerTime.startOfLocalDayMs(now)
        val startOfDob = TrackerTime.startOfLocalDayMs(profile.dateOfBirthMs)
        val dayOfLife = ((startOfToday - startOfDob) / TrackerTime.DAY_MS).toInt().coerceAtLeast(0) + 1
        val wetFinding = DiaperThresholds.evaluateWetCount(dayOfLife, wetCount)
        val wetSeverityLabel = when (wetFinding.severity) {
            DiaperThresholds.Severity.OK -> getString(R.string.tracker_wet_ok)
            DiaperThresholds.Severity.WATCH -> getString(R.string.tracker_wet_watch)
            DiaperThresholds.Severity.URGENT -> getString(R.string.tracker_wet_urgent)
        }

        return buildString {
            append(getString(R.string.tracker_summary_feeds, feedCount, feedTarget.first, feedTarget.last))
            append('\n')
            append(getString(R.string.tracker_summary_interval, interval.first, interval.last))
            append('\n')
            append(lastFeedText)
            append('\n')
            append(getString(R.string.tracker_summary_diapers, wetCount, dirtyCount))
            append(' ').append(wetSeverityLabel)
            append('\n')
            append(getString(R.string.tracker_summary_sleeps, sleepCount))
        }
    }

    private fun buildVaccineSummary(profile: ChildProfile, now: Long): String {
        val given = trackerStore.recentByKind(profile.id, TrackerEvent.Kind.VACCINE, limit = 100)
            .mapNotNull { ev ->
                val name = ev.subkind ?: return@mapNotNull null
                val doseNum = ev.valueNum?.toInt() ?: 1
                VaccineSchedule.Given(name, doseNum, ev.tsWallMs)
            }
        val next = VaccineSchedule.nextDue(profile, given, now).firstOrNull()
        return if (next == null) getString(R.string.tracker_vaccine_upToDate)
        else {
            val date = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(next.dueAtMs))
            val status = when {
                next.overdueByDays > 0 -> resources.getQuantityString(
                    R.plurals.tracker_vaccine_overdue,
                    next.overdueByDays,
                    next.overdueByDays,
                )
                next.overdueByDays >= -14 -> getString(R.string.tracker_vaccine_due_soon)
                else -> getString(R.string.tracker_vaccine_scheduled)
            }
            getString(R.string.tracker_vaccine_line,
                next.dose.antigen, next.dose.doseNumber, date, status)
        }
    }

    private fun buildGuidance(profile: ChildProfile, now: Long): String {
        val safeSleep = SafeSleepChecklist.ITEMS.first().prompt
        return buildString {
            append(getString(R.string.tracker_guidance_tagline))
            append('\n')
            append("• ").append(safeSleep)
            append('\n')
            append("• ").append(getString(R.string.tracker_guidance_sids_citation))
            append('\n')
            append("• ").append(getString(R.string.tracker_guidance_fever))
            append('\n')
            append("• ").append(getString(R.string.tracker_guidance_peanut))
            append('\n')
            append(getString(R.string.tracker_guidance_disclaimer))
        }
    }

    companion object {
        const val EXTRA_CHILD_ID = "child_id"
        private const val DISCLAIMER_PREFS = "TrackerDisclaimer"
        private const val KEY_DISCLAIMER_ACK = "ack_v1"
    }
}
