package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.ChildProfile
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChildProfileActivity : Activity() {

    private var dobMs: Long = 0L
    private var dueMs: Long = 0L
    private lateinit var dobText: TextView
    private lateinit var dueText: TextView
    private lateinit var scheduleSpinner: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_profile)
        ThemeManager.applyTheme(this)
        val store = ChildProfileStore.get(applicationContext)
        val addNew = intent.getBooleanExtra(EXTRA_ADD_NEW, false)
        val existing = if (addNew) null else store.activeProfile()

        val nameEdit = findViewById<EditText>(R.id.nameEdit)
        val sexGroup = findViewById<RadioGroup>(R.id.sexGroup)
        val tierGroup = findViewById<RadioGroup>(R.id.tierGroup)
        dobText = findViewById(R.id.dobText)
        dueText = findViewById(R.id.dueText)
        scheduleSpinner = findViewById(R.id.scheduleSpinner)

        val jurisdictions = ChildProfile.Jurisdiction.values().map { it.name }
        scheduleSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, jurisdictions)

        existing?.let {
            nameEdit.setText(it.name)
            dobMs = it.dateOfBirthMs
            dueMs = it.dueDateMs ?: 0L
            dobText.text = dobMs.formatDate()
            dueText.text = dueMs.takeIf { v -> v > 0 }?.formatDate() ?: ""
            when (it.sex) {
                ChildProfile.Sex.MALE -> sexGroup.check(R.id.sexMale)
                ChildProfile.Sex.FEMALE -> sexGroup.check(R.id.sexFemale)
                ChildProfile.Sex.UNKNOWN -> sexGroup.check(R.id.sexUnknown)
            }
            when (it.allergyRiskTier) {
                ChildProfile.AllergyRiskTier.LOW -> tierGroup.check(R.id.tierLow)
                ChildProfile.AllergyRiskTier.MODERATE -> tierGroup.check(R.id.tierModerate)
                ChildProfile.AllergyRiskTier.HIGH -> tierGroup.check(R.id.tierHigh)
            }
            scheduleSpinner.setSelection(jurisdictions.indexOf(it.schedule.name))
        }

        findViewById<Button>(R.id.pickDobButton).setOnClickListener { pickDate { dobMs = it; dobText.text = it.formatDate() } }
        findViewById<Button>(R.id.pickDueButton).setOnClickListener { pickDate { dueMs = it; dueText.text = it.formatDate() } }

        val deleteButton = findViewById<Button>(R.id.deleteButton)
        if (existing != null) {
            deleteButton.visibility = android.view.View.VISIBLE
            deleteButton.setOnClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.tracker_profile_delete)
                    .setMessage(getString(R.string.tracker_profile_delete_confirm, existing.name))
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        TrackerWriteRunner.run(
                            activity = this,
                            write = {
                                TrackerStore.get(applicationContext).deleteAllForChild(existing.id)
                            },
                            onSuccess = {
                                // Only remove the profile after its SQLite rows were durably
                                // deleted; a database failure otherwise leaves orphan health data.
                                store.remove(existing.id)
                                // Wipe per-child SharedPreferences (CM-TRK-CR5-P2-2).
                                for (prefsName in listOf("SleepChecklistAnswers", "MilestoneObservations")) {
                                    val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
                                    val editor = prefs.edit()
                                    for (key in prefs.all.keys) {
                                        if (key.startsWith("${existing.id}__") ||
                                            key.endsWith("_${existing.id}")) {
                                            editor.remove(key)
                                        }
                                    }
                                    editor.apply()
                                }
                                Toast.makeText(this, R.string.tracker_profile_deleted, Toast.LENGTH_SHORT).show()
                                finish()
                            },
                        )
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }

        findViewById<Button>(R.id.saveButton).setOnClickListener {
            val name = nameEdit.text.toString().ifBlank { getString(R.string.tracker_default_name) }
            if (dobMs <= 0L) {
                Toast.makeText(this, R.string.tracker_profile_dob_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val sex = when (sexGroup.checkedRadioButtonId) {
                R.id.sexMale -> ChildProfile.Sex.MALE
                R.id.sexFemale -> ChildProfile.Sex.FEMALE
                else -> ChildProfile.Sex.UNKNOWN
            }
            val tier = when (tierGroup.checkedRadioButtonId) {
                R.id.tierModerate -> ChildProfile.AllergyRiskTier.MODERATE
                R.id.tierHigh -> ChildProfile.AllergyRiskTier.HIGH
                else -> ChildProfile.AllergyRiskTier.LOW
            }
            val schedule = runCatching {
                ChildProfile.Jurisdiction.valueOf(scheduleSpinner.selectedItem as String)
            }.getOrDefault(ChildProfile.Jurisdiction.US)
            val profile = existing?.copy(
                name = name,
                dateOfBirthMs = dobMs,
                dueDateMs = dueMs.takeIf { it > 0 },
                sex = sex,
                allergyRiskTier = tier,
                schedule = schedule
            ) ?: ChildProfile(
                id = ChildProfileStore.newId(),
                name = name,
                dateOfBirthMs = dobMs,
                dueDateMs = dueMs.takeIf { it > 0 },
                sex = sex,
                allergyRiskTier = tier,
                schedule = schedule
            )
            store.upsert(profile)
            store.setActiveId(profile.id)
            Toast.makeText(this, R.string.tracker_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun pickDate(onSet: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val picked = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, day)
                    set(Calendar.HOUR_OF_DAY, 12)
                    set(Calendar.MINUTE, 0)
                }.timeInMillis
                onSet(picked)
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun Long.formatDate(): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(this))

    companion object {
        const val EXTRA_ADD_NEW = "add_new"
    }
}
