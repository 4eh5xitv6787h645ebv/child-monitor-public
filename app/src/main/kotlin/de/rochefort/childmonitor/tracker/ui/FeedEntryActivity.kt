package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.TrackerInputValidation
import de.rochefort.childmonitor.tracker.guidance.FeedingTargets
import org.json.JSONObject

class FeedEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feed_entry)
        ThemeManager.applyTheme(this)

        val childId = intent.getStringExtra(BabyDashboardActivity.EXTRA_CHILD_ID)
            ?: ChildProfileStore.get(applicationContext).activeProfile()?.id
        if (childId == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }

        val profile = ChildProfileStore.get(applicationContext).list().firstOrNull { it.id == childId }
        if (profile == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val hintView = findViewById<TextView>(R.id.targetsHint)
        val ageMonths = profile.chronologicalAgeMonths(System.currentTimeMillis())
        val feeds = FeedingTargets.feedsPerDayTarget(ageMonths)
        val bottleTarget = FeedingTargets.bottleVolumeMlTarget(ageMonths)
        hintView.text = getString(
            R.string.tracker_feed_target_hint,
            feeds.first, feeds.last, bottleTarget.first, bottleTarget.last
        )

        val group = findViewById<RadioGroup>(R.id.feedKindGroup)
        val duration = findViewById<EditText>(R.id.durationEdit)
        val volume = findViewById<EditText>(R.id.volumeEdit)
        val note = findViewById<EditText>(R.id.noteEdit)
        val trackerStore = TrackerStore.get(applicationContext)

        val saveButton = findViewById<Button>(R.id.saveButton)
        saveButton.setOnClickListener {
            val (kind, subkind) = when (group.checkedRadioButtonId) {
                R.id.feedBreastL -> TrackerEvent.Kind.FEED_BREAST to TrackerEvent.FeedSubkind.BREAST_LEFT
                R.id.feedBreastR -> TrackerEvent.Kind.FEED_BREAST to TrackerEvent.FeedSubkind.BREAST_RIGHT
                R.id.feedBreastBoth -> TrackerEvent.Kind.FEED_BREAST to TrackerEvent.FeedSubkind.BREAST_BOTH
                R.id.feedBottleEbm -> TrackerEvent.Kind.FEED_BOTTLE to TrackerEvent.FeedSubkind.BOTTLE_EBM
                R.id.feedBottleFormula -> TrackerEvent.Kind.FEED_BOTTLE to TrackerEvent.FeedSubkind.BOTTLE_FORMULA
                R.id.feedSolid -> TrackerEvent.Kind.FEED_SOLID to TrackerEvent.FeedSubkind.SOLID_SPOON
                else -> TrackerEvent.Kind.FEED_BREAST to TrackerEvent.FeedSubkind.BREAST_LEFT
            }
            val durationRaw = duration.text.toString().trim()
            val volumeRaw = volume.text.toString().trim()
            val durMin = durationRaw.toIntOrNull()
            val volMl = volumeRaw.toDoubleOrNull()
            if (!TrackerInputValidation.optionalInt(durationRaw, 1..360)) {
                Toast.makeText(this, R.string.tracker_feed_bad_duration, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!TrackerInputValidation.optionalFiniteDouble(volumeRaw, 0.0..2000.0)) {
                Toast.makeText(this, R.string.tracker_feed_bad_volume, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            val extra = JSONObject()
            durMin?.let { extra.put("durationMinutes", it) }
            val event = TrackerEvent(
                childId = childId,
                kind = kind,
                subkind = subkind,
                tsWallMs = now,
                startedMs = durMin?.let { now - it * 60_000L },
                endedMs = now,
                valueNum = volMl,
                extra = if (extra.length() > 0) extra else null,
                note = note.text.toString().takeIf { it.isNotBlank() }
            )
            // CM-TRK-REV-029 next-feed nudge: show the expected next-feed window
            // based on AAP interval targets by age.
            val intervalHours = FeedingTargets.intervalHoursTarget(ageMonths)
            val nextEarliestMs = now + intervalHours.first * 3600_000L
            val nextLatestMs = now + intervalHours.last * 3600_000L
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            val nextText = getString(
                R.string.tracker_feed_next_due,
                fmt.format(java.util.Date(nextEarliestMs)),
                fmt.format(java.util.Date(nextLatestMs))
            )
            saveButton.isEnabled = false
            TrackerWriteRunner.run(this,
                write = { trackerStore.insert(event) },
                onSuccess = { id ->
                    TrackerSaveUndo.show(
                        activity = this,
                        store = trackerStore,
                        childId = childId,
                        insertedIds = listOf(id),
                        message = nextText,
                        onUndone = { saveButton.isEnabled = true },
                        onTimeout = { finish() },
                    )
                },
                onFailure = {
                    saveButton.isEnabled = true
                    Toast.makeText(this, R.string.tracker_save_failed, Toast.LENGTH_LONG).show()
                },
            )
        }
    }
}
