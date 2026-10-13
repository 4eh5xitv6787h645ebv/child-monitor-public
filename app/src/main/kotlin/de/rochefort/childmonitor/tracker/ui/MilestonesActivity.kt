package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.ChildProfile
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.guidance.MilestoneChecklist
import org.json.JSONObject

/**
 * CDC "Learn the Signs" milestone checklist for the active child's age
 * bucket. Implements CM-TRK-REV-001.
 */
class MilestonesActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var bucketHeader: TextView
    private lateinit var store: TrackerStore
    private lateinit var profile: ChildProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_milestones)

        val childId = intent.getStringExtra(BabyDashboardActivity.EXTRA_CHILD_ID)
            ?: ChildProfileStore.get(applicationContext).activeProfile()?.id
        if (childId == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val candidate = ChildProfileStore.get(applicationContext).list().firstOrNull { it.id == childId }
        if (candidate == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        profile = candidate
        store = TrackerStore.get(applicationContext)
        container = findViewById(R.id.milestonesContainer)
        bucketHeader = findViewById(R.id.milestoneBucket)
    }

    override fun onResume() {
        super.onResume()
        // Guard against profile deletion from another activity while we were
        // backgrounded (CM-TRK-CR4-P1-4).
        if (TrackerActivityGuard.resolve(this, profile.id) == null) return
        refresh()
    }

    private fun refresh() {
        container.removeAllViews()
        val ageMonths = profile.correctedAgeMonths(System.currentTimeMillis())
        val bucket = MilestoneChecklist.AGE_BUCKETS.lastOrNull { it <= ageMonths }
        if (bucket == null) {
            bucketHeader.text = getString(R.string.tracker_milestones_too_young)
            return
        }
        bucketHeader.text = getString(R.string.tracker_milestones_bucket, bucket, ageMonths)

        TrackerWriteRunner.run(
            activity = this,
            write = { loadObservations(bucket) },
            onSuccess = { observations -> renderItems(bucket, observations) },
            onFailure = {
                Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun renderItems(bucket: Int, observations: Map<String, String>) {
        container.removeAllViews()
        val items = MilestoneChecklist.forAge(bucket)
        for (item in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }
            val title = TextView(this).apply {
                text = item.text
                setTextColor(androidx.core.content.ContextCompat.getColor(this@MilestonesActivity, de.rochefort.childmonitor.R.color.tracker_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            val domain = TextView(this).apply {
                text = item.domain.name.replace('_', ' ').lowercase()
                setTextColor(androidx.core.content.ContextCompat.getColor(this@MilestonesActivity, de.rochefort.childmonitor.R.color.tracker_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            }
            val group = RadioGroup(this).apply {
                orientation = RadioGroup.HORIZONTAL
            }
            fun addRadio(label: String, value: String, checked: Boolean) {
                val rb = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = label
                    tag = value
                    isChecked = checked
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@MilestonesActivity, de.rochefort.childmonitor.R.color.tracker_text_primary))
                    setPadding(0, 0, 24, 0)
                }
                group.addView(rb)
            }
            val currentStatus = observations[item.id] ?: ""
            addRadio(getString(R.string.tracker_milestone_yes), "YES", currentStatus == "YES")
            addRadio(getString(R.string.tracker_milestone_not_yet), "NOT_YET", currentStatus == "NOT_YET")
            addRadio(getString(R.string.tracker_milestone_not_sure), "NOT_SURE", currentStatus == "NOT_SURE")
            group.setOnCheckedChangeListener { g, _ ->
                val rb = g.findViewById<RadioButton>(g.checkedRadioButtonId)
                val status = (rb.tag as? String) ?: "NOT_SURE"
                storeObservation(bucket, item.id, status)
            }
            row.addView(title); row.addView(domain); row.addView(group)

            if (currentStatus == "NOT_YET" && item.redFlagWhenAbsent) {
                val warn = TextView(this).apply {
                    text = getString(R.string.tracker_milestone_redflag)
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@MilestonesActivity, de.rochefort.childmonitor.R.color.tracker_warning))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(0, 4, 0, 0)
                    isClickable = true
                    setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.source)))
                        } catch (_: Exception) {
                            Toast.makeText(this@MilestonesActivity, item.source, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                row.addView(warn)
            }

            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                )
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@MilestonesActivity, de.rochefort.childmonitor.R.color.tracker_bg_surface))
            }
            container.addView(row); container.addView(divider)
        }
    }

    /**
     * Milestone statuses live in SharedPreferences rather than one TrackerEvent
     * row per radio-tap, which accumulated forever (CM-TRK-CR4-P1-3). We still
     * write a single checkpoint MILESTONE event per status change so the
     * timeline has a record, but the radio reads the current value from prefs.
     */
    private fun prefs() = getSharedPreferences(MILESTONE_PREFS, MODE_PRIVATE)
    private fun prefKey(itemId: String) = "${profile.id}__${itemId}"

    private fun migrateLegacyObservationsOnce() {
        val prefs = prefs()
        val migrationKey = "__migrated_from_events_v1_${profile.id}"
        if (prefs.getBoolean(migrationKey, false)) return
        // CM-TRK-CR5-P1-1: earlier versions wrote one MILESTONE row per tap.
        // Seed prefs from the most-recent row per itemId so prior YES /
        // NOT_YET selections survive the CR4 migration to prefs-backed state.
        val events = store.recentByKind(profile.id, TrackerEvent.Kind.MILESTONE, limit = 5000)
        val editor = prefs.edit()
        val seen = mutableSetOf<String>()
        for (ev in events) {
            val id = ev.subkind ?: continue
            if (id in seen) continue
            seen.add(id)
            val status = ev.extra?.optString("status").orEmpty()
            if (status.isNotBlank() && prefs.getString(prefKey(id), null) == null) {
                editor.putString(prefKey(id), status)
            }
        }
        editor.putBoolean(migrationKey, true)
        editor.apply()
    }

    private fun loadObservations(bucket: Int): Map<String, String> {
        migrateLegacyObservationsOnce()
        val prefs = prefs()
        val result = mutableMapOf<String, String>()
        for (item in MilestoneChecklist.ITEMS) {
            if (item.ageMonths != bucket) continue
            prefs.getString(prefKey(item.id), null)?.let { result[item.id] = it }
        }
        return result
    }

    private fun storeObservation(bucket: Int, itemId: String, status: String) {
        val prefs = prefs()
        val previous = prefs.getString(prefKey(itemId), null)
        if (previous == status) return
        val extra = JSONObject()
            .put("itemId", itemId)
            .put("bucketMonths", bucket)
            .put("status", status)
        val event = TrackerEvent(
                childId = profile.id,
                kind = TrackerEvent.Kind.MILESTONE,
                subkind = itemId,
                extra = extra,
                note = "status=$status"
            )
        TrackerWriteRunner.run(this,
            write = { store.insert(event) },
            onSuccess = { id ->
                // Publish the UI preference only after the durable event commit. Otherwise a DB
                // failure suppresses retry and leaves the preference/event sources inconsistent.
                prefs.edit().putString(prefKey(itemId), status).apply()
                TrackerSaveUndo.show(
                    activity = this,
                    store = store,
                    childId = profile.id,
                    insertedIds = listOf(id),
                    message = getString(R.string.tracker_saved_undo),
                    onUndone = {
                        val editor = prefs.edit()
                        if (previous == null) editor.remove(prefKey(itemId))
                        else editor.putString(prefKey(itemId), previous)
                        editor.apply()
                        refresh()
                    },
                )
            },
        )
    }

    companion object {
        private const val MILESTONE_PREFS = "MilestoneObservations"
    }
}
