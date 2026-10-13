package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.ChildProfile
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.TrackerInputValidation
import de.rochefort.childmonitor.tracker.guidance.GrowthPercentiles
import org.json.JSONObject

class GrowthEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_growth_entry)
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
        val trackerStore = TrackerStore.get(applicationContext)

        val weightEdit = findViewById<EditText>(R.id.weightEdit)
        val lengthEdit = findViewById<EditText>(R.id.lengthEdit)
        val headEdit = findViewById<EditText>(R.id.headEdit)
        val summary = findViewById<TextView>(R.id.percentileSummary)

        val compute = {
            val now = System.currentTimeMillis()
            val ageMonths = profile.correctedAgeMonths(now)
            val weight = weightEdit.text.toString().toDoubleOrNull()
            val length = lengthEdit.text.toString().toDoubleOrNull()
            val head = headEdit.text.toString().toDoubleOrNull()

            val sb = StringBuilder()
            val wfaRef = if (profile.sex == ChildProfile.Sex.FEMALE) GrowthPercentiles.Reference.WFA_FEMALE else GrowthPercentiles.Reference.WFA_MALE
            val lfaRef = if (profile.sex == ChildProfile.Sex.FEMALE) GrowthPercentiles.Reference.LFA_FEMALE else GrowthPercentiles.Reference.LFA_MALE
            val hcfaRef = if (profile.sex == ChildProfile.Sex.FEMALE) GrowthPercentiles.Reference.HCFA_FEMALE else GrowthPercentiles.Reference.HCFA_MALE
            val outOfRange = !GrowthPercentiles.isInRange(wfaRef, ageMonths)
            if (outOfRange) {
                sb.append(
                    resources.getQuantityString(
                        R.plurals.tracker_growth_out_of_range,
                        ageMonths,
                        ageMonths,
                    )
                ).append('\n')
            }
            if (weight != null) {
                val z = GrowthPercentiles.zScoreOrNull(wfaRef, weight, ageMonths)
                val pct = GrowthPercentiles.percentileOrNull(wfaRef, weight, ageMonths)
                if (z != null && pct != null) {
                    sb.append(getString(R.string.tracker_growth_wfa, z, pct)).append('\n')
                }
            }
            if (length != null) {
                val z = GrowthPercentiles.zScoreOrNull(lfaRef, length, ageMonths)
                val pct = GrowthPercentiles.percentileOrNull(lfaRef, length, ageMonths)
                if (z != null && pct != null) {
                    sb.append(getString(R.string.tracker_growth_lfa, z, pct)).append('\n')
                }
            }
            if (head != null) {
                val z = GrowthPercentiles.zScoreOrNull(hcfaRef, head, ageMonths)
                val pct = GrowthPercentiles.percentileOrNull(hcfaRef, head, ageMonths)
                if (z != null && pct != null) {
                    sb.append(getString(R.string.tracker_growth_hcfa, z, pct)).append('\n')
                    if (z < -1.88 || z > 1.88) sb.append(getString(R.string.tracker_growth_hc_flag)).append('\n')
                }
            }
            if (sb.isEmpty()) sb.append(getString(R.string.tracker_growth_enter_at_least_one))
            else sb.append(getString(R.string.tracker_growth_source_who))
            summary.text = sb.toString()
        }
        findViewById<Button>(R.id.computeButton).setOnClickListener { compute() }

        val saveButton = findViewById<Button>(R.id.saveButton)
        saveButton.setOnClickListener {
            val weightRaw = weightEdit.text.toString().trim()
            val lengthRaw = lengthEdit.text.toString().trim()
            val headRaw = headEdit.text.toString().trim()
            val weight = weightRaw.toDoubleOrNull()
            val length = lengthRaw.toDoubleOrNull()
            val head = headRaw.toDoubleOrNull()

            if (!TrackerInputValidation.anyEntered(weightRaw, lengthRaw, headRaw)) {
                Toast.makeText(this, R.string.tracker_growth_need_value, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!TrackerInputValidation.optionalFiniteDouble(weightRaw, 0.5..40.0)) {
                Toast.makeText(this, R.string.tracker_growth_bad_weight, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!TrackerInputValidation.optionalFiniteDouble(lengthRaw, 20.0..140.0)) {
                Toast.makeText(this, R.string.tracker_growth_bad_length, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!TrackerInputValidation.optionalFiniteDouble(headRaw, 20.0..70.0)) {
                Toast.makeText(this, R.string.tracker_growth_bad_head, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val now = System.currentTimeMillis()
            val extra = JSONObject()
            weight?.let { extra.put("weightKg", it) }
            length?.let { extra.put("lengthCm", it) }
            head?.let { extra.put("headCm", it) }
            // #426: persist the (up to 3) growth measurements in ONE transaction — three separate
            // auto-committing inserts could leave a partial growth record if the process is killed
            // between them. Mirrors MedicationEntryActivity's insertAll() usage.
            val events = mutableListOf<TrackerEvent>()
            weight?.let { events.add(TrackerEvent(childId = childId, kind = TrackerEvent.Kind.GROWTH_WEIGHT,
                tsWallMs = now, valueNum = it, extra = extra)) }
            length?.let { events.add(TrackerEvent(childId = childId, kind = TrackerEvent.Kind.GROWTH_LENGTH,
                tsWallMs = now, valueNum = it, extra = extra)) }
            head?.let { events.add(TrackerEvent(childId = childId, kind = TrackerEvent.Kind.GROWTH_HEAD,
                tsWallMs = now, valueNum = it, extra = extra)) }
            saveButton.isEnabled = false
            TrackerWriteRunner.run(this,
                write = { trackerStore.insertAll(events) },
                onSuccess = { ids ->
                    TrackerSaveUndo.show(
                        activity = this,
                        store = trackerStore,
                        childId = childId,
                        insertedIds = ids,
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
