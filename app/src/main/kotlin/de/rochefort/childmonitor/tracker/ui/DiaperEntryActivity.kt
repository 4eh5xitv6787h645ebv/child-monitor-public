package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.guidance.DiaperThresholds
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DiaperEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diaper_entry)
        ThemeManager.applyTheme(this)

        val childId = intent.getStringExtra(BabyDashboardActivity.EXTRA_CHILD_ID)
            ?: ChildProfileStore.get(applicationContext).activeProfile()?.id
        if (childId == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val profileStore = ChildProfileStore.get(applicationContext)
        val profile = profileStore.list().firstOrNull { it.id == childId }
        if (profile == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val trackerStore = TrackerStore.get(applicationContext)

        val kindGroup = findViewById<RadioGroup>(R.id.diaperKindGroup)
        val colorGroup = findViewById<RadioGroup>(R.id.colorGroup)
        val hint = findViewById<TextView>(R.id.stoolHint)
        val hintGeneration = AtomicInteger()

        val updateHint = {
            val generation = hintGeneration.incrementAndGet()
            val now = System.currentTimeMillis()
            // #192-4: anchor both ends to local-midnight so dayOfLife flips at
            // local midnight rather than 24h after birth-instant. DOB is stored
            // at noon-local — raw subtraction rounded to 0 for most of
            // day-of-birth and shifted the dehydration red-flag thresholds by
            // ~12h.
            val startOfToday = de.rochefort.childmonitor.tracker.TrackerTime.startOfLocalDayMs(now)
            val startOfDob = de.rochefort.childmonitor.tracker.TrackerTime.startOfLocalDayMs(profile.dateOfBirthMs)
            val dayOfLife = ((startOfToday - startOfDob) / de.rochefort.childmonitor.tracker.TrackerTime.DAY_MS).toInt().coerceAtLeast(0) + 1
            // Use calendar-day to match the dashboard tile (CM-TRK-CR3-P1-4).
            val startOfDay = startOfToday
            val color = when (colorGroup.checkedRadioButtonId) {
                R.id.cYellow -> DiaperThresholds.StoolColor.YELLOW_MUSTARD
                R.id.cTan -> DiaperThresholds.StoolColor.TAN_BROWN
                R.id.cGreen -> DiaperThresholds.StoolColor.GREEN
                R.id.cMeconium -> DiaperThresholds.StoolColor.BLACK_MECONIUM
                R.id.cBlack -> DiaperThresholds.StoolColor.BLACK_MELENA
                R.id.cRed -> DiaperThresholds.StoolColor.RED_BLOOD
                R.id.cPale -> DiaperThresholds.StoolColor.WHITE_PALE_CHALKY
                else -> DiaperThresholds.StoolColor.YELLOW_MUSTARD
            }
            val colorFinding = DiaperThresholds.evaluateColor(color, dayOfLife)
            TrackerWriteRunner.run(
                activity = this,
                write = { trackerStore.countSince(childId, TrackerEvent.Kind.DIAPER_WET, startOfDay) },
                onSuccess = { wetCount ->
                    if (generation != hintGeneration.get()) return@run
                    val wetFinding = DiaperThresholds.evaluateWetCount(dayOfLife, wetCount)
                    hint.text = getString(
                        R.string.tracker_two_line_finding,
                        wetFinding.message,
                        colorFinding.message,
                    )
                },
                onFailure = {
                    if (generation == hintGeneration.get()) {
                        hint.text = getString(R.string.tracker_load_failed)
                    }
                },
            )
        }
        updateHint()
        colorGroup.setOnCheckedChangeListener { _, _ -> updateHint() }

        val saveButton = findViewById<Button>(R.id.saveButton)
        saveButton.setOnClickListener {
            val kind = when (kindGroup.checkedRadioButtonId) {
                R.id.kindDirty -> TrackerEvent.Kind.DIAPER_DIRTY
                R.id.kindMixed -> TrackerEvent.Kind.DIAPER_MIXED
                else -> TrackerEvent.Kind.DIAPER_WET
            }
            // #192-MED: stool colour is only meaningful for DIRTY and MIXED
            // diapers. Previously wet-only entries were stamped with subkind
            // YELLOW_MUSTARD by default, which made the wet-diaper rows show
            // up as 'yellow stool' in the timeline. For wet diapers leave the
            // subkind null and skip the colour extra.
            val color = if (kind != TrackerEvent.Kind.DIAPER_WET) when (colorGroup.checkedRadioButtonId) {
                R.id.cYellow -> DiaperThresholds.StoolColor.YELLOW_MUSTARD
                R.id.cTan -> DiaperThresholds.StoolColor.TAN_BROWN
                R.id.cGreen -> DiaperThresholds.StoolColor.GREEN
                R.id.cMeconium -> DiaperThresholds.StoolColor.BLACK_MECONIUM
                R.id.cBlack -> DiaperThresholds.StoolColor.BLACK_MELENA
                R.id.cRed -> DiaperThresholds.StoolColor.RED_BLOOD
                R.id.cPale -> DiaperThresholds.StoolColor.WHITE_PALE_CHALKY
                else -> DiaperThresholds.StoolColor.YELLOW_MUSTARD
            } else null
            val extra = if (color != null) JSONObject().put("color", color.name) else null
            val event = TrackerEvent(
                childId = childId,
                kind = kind,
                subkind = color?.name,
                extra = extra
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
                        message = getString(R.string.tracker_saved_undo),
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
