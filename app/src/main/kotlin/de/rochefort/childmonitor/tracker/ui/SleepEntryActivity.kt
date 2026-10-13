package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.TrackerInputValidation
import de.rochefort.childmonitor.tracker.guidance.SafeSleepChecklist
import org.json.JSONArray
import org.json.JSONObject

class SleepEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sleep_entry)

        val childId = intent.getStringExtra(BabyDashboardActivity.EXTRA_CHILD_ID)
            ?: ChildProfileStore.get(applicationContext).activeProfile()?.id
        if (childId == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val trackerStore = TrackerStore.get(applicationContext)

        val typeGroup = findViewById<RadioGroup>(R.id.sleepTypeGroup)
        val locationSpinner = findViewById<Spinner>(R.id.locationSpinner)
        val safetyBox = findViewById<LinearLayout>(R.id.safetyChecklist)
        val tempEdit = findViewById<EditText>(R.id.tempEdit)
        val tempAdvice = findViewById<TextView>(R.id.tempAdvice)

        val locations = SafeSleepChecklist.Location.values().map { it.name }
        locationSpinner.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, locations)

        // CM-TRK-REV-008: persist checklist answers so the parent does not
        // have to re-verify identical items on every nap of the day.
        val checklistPrefs = getSharedPreferences(CHECKLIST_PREFS, MODE_PRIVATE)
        val checkboxes = mutableMapOf<String, CheckBox>()
        SafeSleepChecklist.ITEMS.forEach { item ->
            val persisted = checklistPrefs.getBoolean(
                checklistKey(childId, item.key),
                item.severity == SafeSleepChecklist.Item.Severity.RECOMMENDED
            )
            val cb = CheckBox(this).apply {
                text = item.prompt
                // CheckBox extends Button, so ThemeManager.applyRecursive matches the
                // `is Button` branch before `is CheckBox` and won't colour the text;
                // the tag path runs first and recolours it for the active/HC palette.
                tag = "theme_text_primary"
                setPadding(0, 8, 0, 8)
                isChecked = persisted
                setOnCheckedChangeListener { _, isChecked ->
                    checklistPrefs.edit()
                        .putBoolean(checklistKey(childId, item.key), isChecked)
                        .apply()
                }
            }
            safetyBox.addView(cb)
            checkboxes[item.key] = cb
        }
        // Apply theme after the safety checklist is built so the dynamically-added
        // checkboxes are recolored by the active palette / high-contrast overlay too.
        ThemeManager.applyTheme(this)

        tempEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val t = s?.toString()?.toDoubleOrNull() ?: return tempAdvice.setText("")
                tempAdvice.text = SafeSleepChecklist.roomTempAdvice(t)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val startButton = findViewById<Button>(R.id.startButton)
        startButton.setOnClickListener {
            val kind = if (typeGroup.checkedRadioButtonId == R.id.typeNight)
                TrackerEvent.Kind.SLEEP_START else TrackerEvent.Kind.NAP_START
            val location = SafeSleepChecklist.Location.valueOf(locations[locationSpinner.selectedItemPosition])
            val required = SafeSleepChecklist.requiredForLocation(location)
            val unchecked = required.filter { key ->
                val cb = checkboxes[key] ?: return@filter false
                !cb.isChecked
            }
            if (SafeSleepChecklist.isUnsafeLocation(location)) {
                Toast.makeText(this, R.string.tracker_sleep_location_unsafe, Toast.LENGTH_LONG).show()
            }
            if (unchecked.isNotEmpty()) {
                Toast.makeText(
                    this,
                    resources.getQuantityString(
                        R.plurals.tracker_sleep_safety_missing,
                        unchecked.size,
                        unchecked.size,
                    ),
                    Toast.LENGTH_LONG,
                ).show()
            }
            val checkedJson = JSONObject()
            checkboxes.forEach { (k, cb) -> checkedJson.put(k, cb.isChecked) }
            val extra = JSONObject().put("location", location.name).put("safety", checkedJson)
            val tempRaw = tempEdit.text.toString().trim()
            val tempC = tempRaw.toDoubleOrNull()
            if (!TrackerInputValidation.optionalFiniteDouble(tempRaw, -10.0..50.0)) {
                Toast.makeText(this, R.string.tracker_sleep_bad_temperature, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tempC?.let { extra.put("roomTempC", it) }
            val now = System.currentTimeMillis()
            val event = TrackerEvent(
                childId = childId,
                kind = kind,
                subkind = location.name,
                startedMs = now,
                extra = extra
            )
            startButton.isEnabled = false
            TrackerWriteRunner.run(this,
                write = { trackerStore.insert(event) },
                onSuccess = { id ->
                    TrackerSaveUndo.show(
                        activity = this,
                        store = trackerStore,
                        childId = childId,
                        insertedIds = listOf(id),
                        message = getString(R.string.tracker_sleep_started),
                        onUndone = { startButton.isEnabled = true },
                        onTimeout = { finish() },
                    )
                },
                onFailure = {
                    startButton.isEnabled = true
                    Toast.makeText(this, R.string.tracker_save_failed, Toast.LENGTH_LONG).show()
                },
            )
        }

        val endButton = findViewById<Button>(R.id.endButton)
        endButton.setOnClickListener {
            val now = System.currentTimeMillis()
            endButton.isEnabled = false
            TrackerWriteRunner.run(this,
                write = {
                    // Query and transaction both run on the serialized write executor so two
                    // activity instances cannot close the same sleep row.
                    val candidateNap = trackerStore.lastByKind(childId, TrackerEvent.Kind.NAP_START)
                    val candidateNight = trackerStore.lastByKind(childId, TrackerEvent.Kind.SLEEP_START)
                    val lastStart = listOfNotNull(candidateNap, candidateNight)
                        .filter { it.endedMs == null }
                        .maxByOrNull { it.tsWallMs }
                        ?: throw TrackerWriteRunner.Rejected(getString(R.string.tracker_sleep_no_open))
                    val endKind = if (lastStart.kind == TrackerEvent.Kind.NAP_START)
                        TrackerEvent.Kind.NAP_END else TrackerEvent.Kind.SLEEP_END
                    val end = TrackerEvent(
                        childId = childId,
                        kind = endKind,
                        startedMs = lastStart.startedMs,
                        endedMs = now,
                        valueNum = ((now - (lastStart.startedMs ?: lastStart.tsWallMs)) / 60_000.0),
                        subkind = lastStart.subkind,
                    )
                    lastStart.id to trackerStore.finishSleep(lastStart, end, now)
                },
                onSuccess = { (startId, endId) ->
                    TrackerUndoBar.show(
                        activity = this,
                        message = getString(R.string.tracker_sleep_ended),
                        onUndo = {
                            TrackerWriteRunner.run(
                                activity = this,
                                write = { trackerStore.undoFinishSleep(childId, startId, endId) },
                                onSuccess = {
                                    endButton.isEnabled = true
                                    Toast.makeText(
                                        this,
                                        R.string.tracker_change_undone,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                onFailure = {
                                    Toast.makeText(
                                        this,
                                        R.string.tracker_undo_failed,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                },
                            )
                        },
                        onTimeout = { finish() },
                    )
                },
                onFailure = { error ->
                    endButton.isEnabled = true
                    Toast.makeText(
                        this,
                        if (error is TrackerWriteRunner.Rejected) error.message else getString(R.string.tracker_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun checklistKey(childId: String, itemKey: String): String = "${childId}__${itemKey}"

    companion object {
        private const val CHECKLIST_PREFS = "SleepChecklistAnswers"
    }
}
