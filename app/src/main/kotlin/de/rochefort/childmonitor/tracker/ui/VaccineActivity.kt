package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.ChildProfile
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.guidance.VaccineSchedule
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vaccine list + log-as-given UI. Implements CM-TRK-REV-002.
 *
 * The "given" rows live in `tracker_events` as kind=VACCINE, subkind=antigen,
 * valueNum=doseNumber (Int stored as Double), valueNum2=site (encoded as
 * enum ordinal). Next-due entries come from [VaccineSchedule.nextDue].
 */
class VaccineActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var store: TrackerStore
    private lateinit var profile: ChildProfile
    private var refreshGeneration = 0
    private val dateFmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vaccine)

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
        container = findViewById(R.id.vaccineContainer)
    }

    override fun onResume() {
        super.onResume()
        if (TrackerActivityGuard.resolve(this, profile.id) == null) return
        refresh()
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        container.removeAllViews()
        TrackerWriteRunner.run(
            activity = this,
            write = { store.recentByKind(profile.id, TrackerEvent.Kind.VACCINE, limit = 500) },
            onSuccess = { storedGiven ->
                if (generation == refreshGeneration) render(storedGiven)
            },
            onFailure = {
                Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun render(storedGiven: List<TrackerEvent>) {
        container.removeAllViews()
        val now = System.currentTimeMillis()
        val given = storedGiven
            .mapNotNull {
                val name = it.subkind ?: return@mapNotNull null
                val dose = it.valueNum?.toInt() ?: 1
                VaccineSchedule.Given(name, dose, it.tsWallMs)
            }
        val due = VaccineSchedule.nextDue(profile, given, now)

        addHeader(getString(R.string.tracker_vaccine_next_header))
        if (due.isEmpty()) {
            addMuted(getString(R.string.tracker_vaccine_upToDate))
        } else {
            for (d in due.take(10)) {
                val status = when {
                    // [#244] An age-capped dose (e.g. rotavirus) past its window is no longer administrable.
                    d.windowClosed -> getString(R.string.tracker_vaccine_window_passed)
                    d.overdueByDays > 0 -> resources.getQuantityString(
                        R.plurals.tracker_vaccine_overdue,
                        d.overdueByDays,
                        d.overdueByDays,
                    )
                    d.overdueByDays >= -14 -> getString(R.string.tracker_vaccine_due_soon)
                    else -> getString(R.string.tracker_vaccine_scheduled)
                }
                // [#244] Don't offer "record given" for a window-closed dose — surfacing it as
                // administrable is exactly the misleading behaviour this fixes.
                val logAction: (() -> Unit)? = if (d.windowClosed) {
                    null
                } else {
                    { showGivenDialog(d.dose.antigen, d.dose.doseNumber) }
                }
                addVaccineRow(
                    title = "${d.dose.antigen} #${d.dose.doseNumber}",
                    detail = "${dateFmt.format(Date(d.dueAtMs))} · $status",
                    onLogAction = logAction
                )
            }
        }

        addHeader(getString(R.string.tracker_vaccine_given_header))
        if (storedGiven.isEmpty()) {
            addMuted(getString(R.string.tracker_vaccine_none_given))
        } else {
            for (ev in storedGiven) {
                val name = ev.subkind ?: continue
                val dose = ev.valueNum?.toInt() ?: 1
                val title = "$name #$dose"
                val detail = dateFmt.format(Date(ev.tsWallMs))
                addVaccineRow(title = title, detail = detail, onLogAction = null,
                    onDelete = {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.tracker_timeline_delete_confirm, title))
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                TrackerWriteRunner.run(this,
                                    write = {
                                        check(store.softDelete(profile.id, ev.id) == 1) {
                                            "vaccine row was not found"
                                        }
                                    },
                                    onSuccess = {
                                        refresh()
                                        TrackerUndoBar.show(
                                            activity = this,
                                            message = getString(R.string.tracker_deleted_undo),
                                            onUndo = {
                                                TrackerWriteRunner.run(
                                                    activity = this,
                                                    write = {
                                                        check(store.restore(profile.id, ev.id) == 1) {
                                                            "vaccine row was not found"
                                                        }
                                                    },
                                                    onSuccess = { refresh() },
                                                    onFailure = {
                                                        Toast.makeText(
                                                            this,
                                                            R.string.tracker_undo_failed,
                                                            Toast.LENGTH_LONG,
                                                        ).show()
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    })
            }
        }
    }

    private fun addHeader(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(androidx.core.content.ContextCompat.getColor(this@VaccineActivity, de.rochefort.childmonitor.R.color.tracker_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 24, 0, 8)
        }
        container.addView(tv)
    }

    private fun addMuted(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(androidx.core.content.ContextCompat.getColor(this@VaccineActivity, de.rochefort.childmonitor.R.color.tracker_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        container.addView(tv)
    }

    private fun addVaccineRow(title: String, detail: String,
                              onLogAction: (() -> Unit)? = null,
                              onDelete: (() -> Unit)? = null) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        val tv1 = TextView(this).apply {
            text = title
            setTextColor(androidx.core.content.ContextCompat.getColor(this@VaccineActivity, de.rochefort.childmonitor.R.color.tracker_text_primary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        val tv2 = TextView(this).apply {
            text = detail
            setTextColor(androidx.core.content.ContextCompat.getColor(this@VaccineActivity, de.rochefort.childmonitor.R.color.tracker_text_secondary))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(tv1); row.addView(tv2)
        if (onLogAction != null) {
            val btn = Button(this).apply {
                text = getString(R.string.tracker_vaccine_record_given)
                setOnClickListener { onLogAction() }
            }
            row.addView(btn)
        }
        if (onDelete != null) {
            row.isLongClickable = true
            row.setOnLongClickListener { onDelete(); true }
        }
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@VaccineActivity, de.rochefort.childmonitor.R.color.tracker_bg_surface))
        }
        container.addView(row)
        container.addView(divider)
    }

    private fun showGivenDialog(antigen: String, doseNumber: Int) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tracker_vaccine_log_given_title, antigen, doseNumber))
            .setMessage(R.string.tracker_vaccine_log_given_body)
            .setPositiveButton(R.string.tracker_save) { _, _ ->
                val extra = JSONObject()
                    .put("antigen", antigen)
                    .put("doseNumber", doseNumber)
                val event = TrackerEvent(
                    childId = profile.id,
                    kind = TrackerEvent.Kind.VACCINE,
                    subkind = antigen,
                    valueNum = doseNumber.toDouble(),
                    extra = extra
                )
                TrackerWriteRunner.run(this,
                    write = { store.insert(event) },
                    onSuccess = { id ->
                        refresh()
                        TrackerSaveUndo.show(
                            activity = this,
                            store = store,
                            childId = profile.id,
                            insertedIds = listOf(id),
                            message = getString(R.string.tracker_saved_undo),
                            onUndone = { refresh() },
                        )
                    },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
