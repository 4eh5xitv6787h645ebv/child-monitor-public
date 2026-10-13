package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.view.View
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerInputValidation
import de.rochefort.childmonitor.tracker.TrackerStore
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import org.json.JSONObject

/** Generic, child-scoped editor for an existing timeline row (#483). */
class TrackerEventEditActivity : Activity() {
    private lateinit var childId: String
    private lateinit var store: TrackerStore
    private lateinit var original: TrackerEvent
    private lateinit var timestampButton: Button
    private lateinit var saveButton: Button
    private lateinit var kindSpinner: Spinner
    private lateinit var subkind: EditText
    private lateinit var subkindSpinner: Spinner
    private lateinit var value: EditText
    private lateinit var value2: EditText
    private lateinit var note: EditText
    private var selectedTimestampMs = 0L
    private var subtypeChoices: List<TrackerEventLabels.SubtypeChoice> = emptyList()
    private val timestampFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tracker_event_edit)
        ThemeManager.applyTheme(this)

        childId = intent.getStringExtra(EXTRA_CHILD_ID).orEmpty()
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
        if (childId.isBlank() || eventId <= 0L || TrackerActivityGuard.resolve(this, childId) == null) {
            finish()
            return
        }

        store = TrackerStore.get(applicationContext)
        timestampButton = findViewById(R.id.editTimestampButton)
        saveButton = findViewById(R.id.editSaveButton)
        kindSpinner = findViewById(R.id.editKindSpinner)
        subkind = findViewById(R.id.editSubkind)
        subkindSpinner = findViewById(R.id.editSubkindSpinner)
        value = findViewById(R.id.editValue)
        value2 = findViewById(R.id.editValue2)
        note = findViewById(R.id.editNote)

        val kinds = TrackerEvent.Kind.values()
        kindSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            kinds.map { TrackerEventLabels.kind(this, it) },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        // Storage kinds have different metadata and safety workflows. Correct values/time/note in
        // place, but use Delete + the original entry screen to correct an event type (#483).
        kindSpinner.isEnabled = false

        timestampButton.setOnClickListener { pickTimestamp() }
        saveButton.setOnClickListener { saveChanges() }

        TrackerWriteRunner.run(
            activity = this,
            write = { store.find(childId, eventId) },
            onSuccess = { event ->
                if (event == null) {
                    Toast.makeText(this, R.string.tracker_event_missing, Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    original = event
                    populate(event, kinds)
                    configureSafetyRestrictions(event)
                    saveButton.isEnabled = true
                }
            },
            onFailure = {
                Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
                finish()
            },
        )
    }

    private fun configureSafetyRestrictions(event: TrackerEvent) {
        subtypeChoices = TrackerEventLabels.subtypeChoices(this, event.kind)
        if (subtypeChoices.isNotEmpty()) {
            subkind.visibility = View.GONE
            subkindSpinner.visibility = View.VISIBLE
            subkindSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                subtypeChoices.map { it.label },
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val selected = subtypeChoices.indexOfFirst { it.storedValue == event.subkind }
            if (selected >= 0) subkindSpinner.setSelection(selected)
        }
        if (event.kind == TrackerEvent.Kind.MEDICATION) {
            findViewById<TextView>(R.id.editSafetyHint).visibility = View.VISIBLE
            findViewById<TextView>(R.id.editTimestampLabel).visibility = View.GONE
            timestampButton.visibility = View.GONE
            subkind.visibility = View.GONE
            subkindSpinner.visibility = View.GONE
            value.visibility = View.GONE
            value2.visibility = View.GONE
        } else if (event.kind == TrackerEvent.Kind.MILESTONE) {
            subkind.visibility = View.GONE
            subkindSpinner.visibility = View.GONE
            value.visibility = View.GONE
            value2.visibility = View.GONE
            note.visibility = View.GONE
        } else if (event.kind == TrackerEvent.Kind.DIAPER_WET) {
            subkind.visibility = View.GONE
            subkindSpinner.visibility = View.GONE
        }
    }

    private fun populate(event: TrackerEvent, kinds: Array<TrackerEvent.Kind>) {
        selectedTimestampMs = event.tsWallMs
        timestampButton.text = timestampFormat.format(Date(selectedTimestampMs))
        kindSpinner.setSelection(kinds.indexOf(event.kind).coerceAtLeast(0))
        subkind.setText(event.subkind.orEmpty())
        value.setText(event.valueNum?.let(::plainNumber).orEmpty())
        value2.setText(event.valueNum2?.let(::plainNumber).orEmpty())
        note.setText(event.note.orEmpty())
    }

    private fun pickTimestamp() {
        val calendar = Calendar.getInstance().apply { timeInMillis = selectedTimestampMs }
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val pickedDate = Calendar.getInstance().apply {
                    timeInMillis = selectedTimestampMs
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                }
                TimePickerDialog(
                    this,
                    { _, hour, minute ->
                        pickedDate.set(Calendar.HOUR_OF_DAY, hour)
                        pickedDate.set(Calendar.MINUTE, minute)
                        pickedDate.set(Calendar.SECOND, 0)
                        pickedDate.set(Calendar.MILLISECOND, 0)
                        selectedTimestampMs = pickedDate.timeInMillis
                        timestampButton.text = timestampFormat.format(pickedDate.time)
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    android.text.format.DateFormat.is24HourFormat(this),
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH),
        ).show()
    }

    private fun saveChanges() {
        val kind = original.kind
        val valueRaw = value.text.toString().trim()
        val value2Raw = value2.text.toString().trim()
        if (!validPrimaryValue(kind, valueRaw) ||
            !TrackerInputValidation.optionalFiniteDouble(value2Raw, -1_000_000.0..1_000_000.0)
        ) {
            Toast.makeText(this, R.string.tracker_edit_bad_value, Toast.LENGTH_LONG).show()
            return
        }
        if (kind != TrackerEvent.Kind.MEDICATION &&
            (selectedTimestampMs <= 0L || selectedTimestampMs > System.currentTimeMillis() + 5 * 60_000L)
        ) {
            Toast.makeText(this, R.string.tracker_edit_bad_time, Toast.LENGTH_LONG).show()
            return
        }

        val deltaMs = selectedTimestampMs - original.tsWallMs
        val (updatedStartedMs, updatedEndedMs) = when (original.kind) {
            TrackerEvent.Kind.SLEEP_START, TrackerEvent.Kind.NAP_START ->
                selectedTimestampMs to original.endedMs
            TrackerEvent.Kind.SLEEP_END, TrackerEvent.Kind.NAP_END ->
                original.startedMs to selectedTimestampMs
            else -> original.startedMs?.plus(deltaMs) to original.endedMs?.plus(deltaMs)
        }
        if (original.kind in setOf(TrackerEvent.Kind.SLEEP_START, TrackerEvent.Kind.NAP_START) &&
            updatedEndedMs != null && selectedTimestampMs > updatedEndedMs
        ) {
            Toast.makeText(this, R.string.tracker_edit_sleep_order, Toast.LENGTH_LONG).show()
            return
        }
        val updatedValue = when (original.kind) {
            TrackerEvent.Kind.SLEEP_END, TrackerEvent.Kind.NAP_END ->
                updatedStartedMs?.let { (selectedTimestampMs - it) / 60_000.0 }
            else -> valueRaw.toDoubleOrNull()
        }
        if (updatedValue != null && updatedValue < 0.0 &&
            original.kind in setOf(TrackerEvent.Kind.SLEEP_END, TrackerEvent.Kind.NAP_END)
        ) {
            Toast.makeText(this, R.string.tracker_edit_sleep_order, Toast.LENGTH_LONG).show()
            return
        }
        val updatedSubkind = if (subtypeChoices.isNotEmpty() && subkindSpinner.visibility == View.VISIBLE) {
            subtypeChoices.getOrNull(subkindSpinner.selectedItemPosition)?.storedValue
        } else {
            subkind.text.toString().trim().takeIf { it.isNotEmpty() }
        }
        val updatedValue2 = value2Raw.toDoubleOrNull()
        val updated = original.copy(
            kind = kind,
            subkind = updatedSubkind,
            tsWallMs = selectedTimestampMs,
            startedMs = updatedStartedMs,
            endedMs = updatedEndedMs,
            valueNum = updatedValue,
            valueNum2 = updatedValue2,
            extra = normalizedExtra(kind, updatedSubkind, updatedValue),
            note = note.text.toString().trim().takeIf { it.isNotEmpty() },
        )
        saveButton.isEnabled = false
        TrackerWriteRunner.run(
            activity = this,
            write = { check(store.replace(childId, updated) == 1) { "tracker event was not found" } },
            onSuccess = {
                TrackerUndoBar.show(
                    activity = this,
                    message = getString(R.string.tracker_changes_saved_undo),
                    onUndo = { undoEdit(updated) },
                    onTimeout = { finish() },
                )
            },
            onFailure = {
                saveButton.isEnabled = true
                Toast.makeText(this, R.string.tracker_save_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun normalizedExtra(
        kind: TrackerEvent.Kind,
        updatedSubkind: String?,
        updatedValue: Double?,
    ): JSONObject? {
        val extra = JSONObject(original.extra?.toString() ?: "{}")
        fun putOrRemove(key: String, value: Any?) {
            if (value == null) extra.remove(key) else extra.put(key, value)
        }
        when (kind) {
            TrackerEvent.Kind.DIAPER_DIRTY, TrackerEvent.Kind.DIAPER_MIXED ->
                putOrRemove("color", updatedSubkind)
            TrackerEvent.Kind.DIAPER_WET -> extra.remove("color")
            TrackerEvent.Kind.GROWTH_WEIGHT -> putOrRemove("weightKg", updatedValue)
            TrackerEvent.Kind.GROWTH_LENGTH -> putOrRemove("lengthCm", updatedValue)
            TrackerEvent.Kind.GROWTH_HEAD -> putOrRemove("headCm", updatedValue)
            TrackerEvent.Kind.VACCINE -> {
                putOrRemove("antigen", updatedSubkind)
                putOrRemove("doseNumber", updatedValue?.toInt())
            }
            TrackerEvent.Kind.MILESTONE -> putOrRemove("itemId", original.subkind)
            TrackerEvent.Kind.MEDICATION -> {
                // Dose fields are hidden/immutable here; preserve and re-stamp their redundant
                // metadata so a note-only correction cannot desynchronise the dosing audit row.
                putOrRemove("doseMg", original.valueNum)
                putOrRemove("weightKg", original.valueNum2)
            }
            else -> Unit
        }
        return extra.takeIf { it.length() > 0 }
    }

    private fun undoEdit(updated: TrackerEvent) {
        TrackerWriteRunner.run(
            activity = this,
            write = { check(store.replace(childId, original) == 1) { "tracker event was not found" } },
            onSuccess = {
                populate(original, TrackerEvent.Kind.values())
                saveButton.isEnabled = true
                Toast.makeText(this, R.string.tracker_change_undone, Toast.LENGTH_SHORT).show()
            },
            onFailure = {
                original = updated
                saveButton.isEnabled = true
                Toast.makeText(this, R.string.tracker_undo_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun validPrimaryValue(kind: TrackerEvent.Kind, raw: String): Boolean {
        if (kind == TrackerEvent.Kind.VACCINE) {
            return TrackerInputValidation.requiredInt(raw, 1..20)
        }
        val range = when (kind) {
            TrackerEvent.Kind.GROWTH_WEIGHT -> 0.5..40.0
            TrackerEvent.Kind.GROWTH_LENGTH -> 20.0..140.0
            TrackerEvent.Kind.GROWTH_HEAD -> 20.0..70.0
            TrackerEvent.Kind.TEMPERATURE -> -10.0..50.0
            TrackerEvent.Kind.FEED_BOTTLE -> 0.0..2_000.0
            TrackerEvent.Kind.FEED_ALLERGEN -> 0.0..1_000.0
            TrackerEvent.Kind.MEDICATION -> 0.0..100_000.0
            else -> -1_000_000.0..1_000_000.0
        }
        val requiresValue = kind in setOf(
            TrackerEvent.Kind.GROWTH_WEIGHT,
            TrackerEvent.Kind.GROWTH_LENGTH,
            TrackerEvent.Kind.GROWTH_HEAD,
            TrackerEvent.Kind.GROWTH_BMI,
            TrackerEvent.Kind.GROWTH_MUAC,
            TrackerEvent.Kind.TEMPERATURE,
            TrackerEvent.Kind.MEDICATION,
            TrackerEvent.Kind.FEED_ALLERGEN,
        )
        return if (requiresValue) {
            TrackerInputValidation.requiredFiniteDouble(raw, range)
        } else {
            TrackerInputValidation.optionalFiniteDouble(raw, range)
        }
    }

    private fun plainNumber(number: Double): String =
        if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()

    override fun onDestroy() {
        TrackerUndoBar.dismiss(this)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHILD_ID = "trackerEditChildId"
        const val EXTRA_EVENT_ID = "trackerEditEventId"
    }
}
