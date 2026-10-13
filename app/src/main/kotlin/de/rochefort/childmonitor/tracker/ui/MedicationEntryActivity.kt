package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.tracker.TrackerTime
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.TrackerInputValidation
import de.rochefort.childmonitor.tracker.guidance.FeverThresholds
import de.rochefort.childmonitor.tracker.guidance.MedicationDose
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MedicationEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_medication_entry)
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
        val store = TrackerStore.get(applicationContext)

        val tempEdit = findViewById<EditText>(R.id.tempEdit)
        val feverReadout = findViewById<TextView>(R.id.feverReadout)
        val drugGroup = findViewById<RadioGroup>(R.id.drugGroup)
        val weightEdit = findViewById<EditText>(R.id.weightEdit)
        val strengthEdit = findViewById<EditText>(R.id.strengthEdit)
        val recommendText = findViewById<TextView>(R.id.recommendText)

        fun currentAgeMonths(nowMs: Long) = profile.chronologicalAgeMonths(nowMs)

        /**
         * Build a fresh Context from the database. Used by both the Recommend button
         * and the Save button so neither can bypass the 24h cap or interval guard.
         *
         * #178: query the most recent GROWTH_WEIGHT event for the active child and
         * pass its age into MedicationDose.Context so the spec's 30-day stale-weight
         * warning fires. Previously hardcoded null.
         */
        fun buildContextFromStore(drug: MedicationDose.Drug, weightKg: Double, nowMs: Long): MedicationDose.Context {
            val since24h = nowMs - TimeUnit.HOURS.toMillis(24)
            // Use SQL-level SUM/COUNT/MAX so the 24h cap does not bypass when
            // the child has many other events in the last 24h (CM-TRK-CR2-P0-1).
            val summary = store.sumValueSince(
                childId = childId,
                kind = TrackerEvent.Kind.MEDICATION,
                subkind = drug.name,
                sinceWallMs = since24h
            )
            val lastWeightEvent = store.lastByKind(childId, TrackerEvent.Kind.GROWTH_WEIGHT)
            val weightMeasuredDaysAgo = lastWeightEvent?.let { ev ->
                ((nowMs - ev.tsWallMs) / TrackerTime.DAY_MS).coerceAtLeast(0).toInt()
            }
            // #147-2: look up the most recent dose of the OTHER analgesic so
            // the interleave warning ("don't stack paracetamol + ibuprofen
            // <60min apart") fires.
            val otherDrugName = when (drug) {
                MedicationDose.Drug.PARACETAMOL -> MedicationDose.Drug.IBUPROFEN.name
                MedicationDose.Drug.IBUPROFEN -> MedicationDose.Drug.PARACETAMOL.name
                else -> null
            }
            val lastOtherDrugAtMs = otherDrugName?.let { otherName ->
                store.lastByKindSubkind(childId, TrackerEvent.Kind.MEDICATION, otherName)?.tsWallMs
            }
            return MedicationDose.Context(
                ageMonths = currentAgeMonths(nowMs),
                weightKg = weightKg,
                weightMeasuredDaysAgo = weightMeasuredDaysAgo,
                last24hDosesMg = summary.totalValue,
                last24hDoseCount = summary.count,
                lastDoseAtMs = summary.lastAtMs,
                nowMs = nowMs,
                lastOtherDrugDoseAtMs = lastOtherDrugAtMs
            )
        }

        fun restorePreviousMedicationReminder(
            drug: MedicationDose.Drug,
            weightKg: Double,
            childName: String,
        ) {
            TrackerWriteRunner.run(
                activity = this,
                write = {
                    MedicationDose.recommend(
                        drug,
                        context = buildContextFromStore(drug, weightKg, System.currentTimeMillis()),
                    ).nextSafeDoseAtMs
                },
                onSuccess = { whenMs ->
                    whenMs?.let {
                        de.rochefort.childmonitor.tracker.MedicationReminderReceiver.schedule(
                            applicationContext,
                            drug.name,
                            childId,
                            childName,
                            it,
                        )
                    }
                },
            )
        }

        // #178: prefill the weight field from the most recent GROWTH_WEIGHT entry if
        // the parent hasn't typed anything yet. Don't overwrite a user-edited value.
        // Surface a "weight is stale" banner under the field when the last
        // measurement is older than 30 days.
        TrackerWriteRunner.run(
            activity = this,
            write = { store.lastByKind(childId, TrackerEvent.Kind.GROWTH_WEIGHT) },
            onSuccess = { lastWeight ->
            if (lastWeight != null) {
                val kg = lastWeight.valueNum
                if (kg != null && weightEdit.text.isNullOrBlank()) {
                    weightEdit.setText(String.format(java.util.Locale.US, "%.2f", kg))
                }
                val daysAgo = ((System.currentTimeMillis() - lastWeight.tsWallMs) / TrackerTime.DAY_MS).coerceAtLeast(0)
                if (daysAgo > 30) {
                    val staleBanner = findViewById<TextView>(R.id.weightStaleBanner)
                    staleBanner?.let {
                        it.visibility = android.view.View.VISIBLE
                        val quantity = daysAgo.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        it.text = resources.getQuantityString(
                            R.plurals.tracker_med_stale_weight,
                            quantity,
                            daysAgo,
                        )
                    }
                }
            }
            },
            onFailure = {
                Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
            },
        )

        // #150: the fever band is never derived from temperature alone. A valid
        // temperature reveals the NICE traffic-light checklist (placed above the
        // readout), and the band shown is the highest of the temperature band
        // and the checklist band, re-evaluated live as signs are ticked.
        val checklistGroup = findViewById<android.view.View>(R.id.checklistGroup)
        val chkPaleMottled = findViewById<CheckBox>(R.id.chkPaleMottled)
        val chkUnresponsive = findViewById<CheckBox>(R.id.chkUnresponsive)
        val chkWeakCry = findViewById<CheckBox>(R.id.chkWeakCry)
        val chkGrunting = findViewById<CheckBox>(R.id.chkGrunting)
        val chkApnoea = findViewById<CheckBox>(R.id.chkApnoea)
        val chkRespRate60 = findViewById<CheckBox>(R.id.chkRespRate60)
        val chkTachycardia = findViewById<CheckBox>(R.id.chkTachycardia)
        val chkSkinTurgor = findViewById<CheckBox>(R.id.chkSkinTurgor)
        val chkNonBlanchingRash = findViewById<CheckBox>(R.id.chkNonBlanchingRash)
        val chkNeuroSigns = findViewById<CheckBox>(R.id.chkNeuroSigns)
        val allChecks = listOf(
            chkPaleMottled, chkUnresponsive, chkWeakCry, chkGrunting, chkApnoea,
            chkRespRate60, chkTachycardia, chkSkinTurgor, chkNonBlanchingRash, chkNeuroSigns
        )
        chkTachycardia.text = getString(
            R.string.tracker_chk_tachy,
            FeverThresholds.tachycardiaThresholdBpm(currentAgeMonths(System.currentTimeMillis()))
        )

        fun currentChecklist() = FeverThresholds.Checklist(
            paleMottledAshenBlue = chkPaleMottled.isChecked,
            unresponsiveOrHardToRouse = chkUnresponsive.isChecked,
            weakHighPitchedCry = chkWeakCry.isChecked,
            grunting = chkGrunting.isChecked,
            apnoea = chkApnoea.isChecked,
            respRateOver60 = chkRespRate60.isChecked,
            tachycardiaForAge = chkTachycardia.isChecked,
            reducedSkinTurgor = chkSkinTurgor.isChecked,
            nonBlanchingRash = chkNonBlanchingRash.isChecked,
            neuroSigns = chkNeuroSigns.isChecked
        )

        fun recomputeFeverReadout() {
            val t = tempEdit.text?.toString()?.toDoubleOrNull()
            if (t == null) {
                checklistGroup.visibility = android.view.View.GONE
                feverReadout.text = ""
                return
            }
            checklistGroup.visibility = android.view.View.VISIBLE
            val assessment = FeverThresholds.evaluate(
                t, currentAgeMonths(System.currentTimeMillis()), currentChecklist())
            feverReadout.text = getString(R.string.tracker_fever_line,
                assessment.risk.name, assessment.headline, assessment.rationale)
        }

        tempEdit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = recomputeFeverReadout()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        allChecks.forEach { box ->
            box.setOnCheckedChangeListener { _, _ -> recomputeFeverReadout() }
        }

        val recommendButton = findViewById<Button>(R.id.recommendButton)
        recommendButton.setOnClickListener {
            val drug = if (drugGroup.checkedRadioButtonId == R.id.drugIbuprofen)
                MedicationDose.Drug.IBUPROFEN else MedicationDose.Drug.PARACETAMOL
            val weightRaw = weightEdit.text.toString().trim()
            val strengthRaw = strengthEdit.text.toString().trim()
            if (!TrackerInputValidation.requiredFiniteDouble(weightRaw, 0.5..40.0)) {
                Toast.makeText(this, R.string.tracker_med_need_weight, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!TrackerInputValidation.requiredFiniteDouble(strengthRaw, 10.0..1000.0)) {
                Toast.makeText(this, R.string.tracker_med_need_strength, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val weightKg = weightRaw.toDouble()
            val strength = strengthRaw.toDouble()
            val nowMs = System.currentTimeMillis()
            recommendButton.isEnabled = false
            TrackerWriteRunner.run(
                activity = this,
                write = {
                    MedicationDose.recommend(
                        drug,
                        context = buildContextFromStore(drug, weightKg, nowMs),
                    )
                },
                onSuccess = { rec ->
                    recommendButton.isEnabled = true
                    val sb = StringBuilder()
                    if (rec.blocked) {
                        sb.append(getString(R.string.tracker_med_blocked))
                        rec.blockedReasons.forEach { sb.append('\n').append("• ").append(it) }
                    } else if (rec.doseMg != null) {
                        val ml = MedicationDose.doseMlFromMg(rec.doseMg, strength)
                        sb.append(getString(R.string.tracker_med_dose_mg, rec.doseMg))
                        sb.append('\n').append(getString(R.string.tracker_med_dose_ml, ml))
                        rec.nextSafeDoseAtMs?.let {
                            val minsUntilNext = (it - nowMs) / 60_000L
                            if (minsUntilNext > 0) {
                                val quantity = minsUntilNext.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                sb.append('\n').append(
                                    resources.getQuantityString(
                                        R.plurals.tracker_med_next_ok_in,
                                        quantity,
                                        minsUntilNext,
                                    )
                                )
                            }
                        }
                    }
                    rec.warnings.forEach { sb.append('\n').append("! ").append(it) }
                    sb.append('\n').append(getString(R.string.tracker_med_source, rec.source))
                    recommendText.text = sb.toString()
                },
                onFailure = {
                    recommendButton.isEnabled = true
                    Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
                },
            )
        }

        val saveButton = findViewById<Button>(R.id.saveButton)
        saveButton.setOnClickListener {
            // Double-tap guard: disable the button immediately so a second
            // click between buildContext and insert cannot bypass the cap
            // (CM-TRK-CR3-P0-2).
            saveButton.isEnabled = false
            val drug = if (drugGroup.checkedRadioButtonId == R.id.drugIbuprofen)
                MedicationDose.Drug.IBUPROFEN else MedicationDose.Drug.PARACETAMOL
            val weightRaw = weightEdit.text.toString().trim()
            if (!TrackerInputValidation.requiredFiniteDouble(weightRaw, 0.5..40.0)) {
                saveButton.isEnabled = true
                Toast.makeText(this, R.string.tracker_med_need_weight, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val weightKg = weightRaw.toDouble()
            val strengthRaw = strengthEdit.text.toString().trim()
            if (!TrackerInputValidation.requiredFiniteDouble(strengthRaw, 10.0..1000.0)) {
                saveButton.isEnabled = true
                Toast.makeText(this, R.string.tracker_med_need_strength, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val strength = strengthRaw.toDouble()
            // Serialize the recommend+insert pair so a concurrent save on a
            // second activity instance cannot slip past the cap.
            val nowMs = System.currentTimeMillis()
            val tempRaw = tempEdit.text.toString().trim()
            val tempC = tempRaw.toDoubleOrNull()
            if (!TrackerInputValidation.optionalFiniteDouble(tempRaw, 30.0..45.0)) {
                saveButton.isEnabled = true
                Toast.makeText(this, R.string.tracker_sleep_bad_temperature, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            TrackerWriteRunner.run(this,
                write = {
                    synchronized(STORE_WRITE_LOCK) {
                        val ctx = buildContextFromStore(drug, weightKg, nowMs)
                        val rec = MedicationDose.recommend(drug, context = ctx)
                        val mg = rec.doseMg
                        if (rec.blocked || mg == null) {
                            throw TrackerWriteRunner.Rejected(
                                rec.blockedReasons.firstOrNull()
                                    ?: getString(R.string.tracker_med_blocked)
                            )
                        }
                        val extra = JSONObject()
                            .put("strengthMgPer5ml", strength)
                            .put("doseMg", mg)
                            .put("doseMl", MedicationDose.doseMlFromMg(mg, strength))
                            .put("weightKg", weightKg)
                            .put("mgPerKg", mg / weightKg)
                        val rows = mutableListOf<TrackerEvent>()
                        if (tempC != null) {
                            rows.add(TrackerEvent(childId = childId, kind = TrackerEvent.Kind.TEMPERATURE,
                                tsWallMs = nowMs, valueNum = tempC))
                        }
                        rows.add(TrackerEvent(
                            childId = childId,
                            kind = TrackerEvent.Kind.MEDICATION,
                            subkind = drug.name,
                            valueNum = mg,
                            valueNum2 = weightKg,
                            extra = extra,
                            tsWallMs = nowMs
                        ))
                        val insertedIds = store.insertAll(rows)
                        val recommendation = MedicationDose.recommend(
                            drug,
                            context = buildContextFromStore(drug, weightKg, System.currentTimeMillis()),
                        )
                        insertedIds to recommendation
                    }
                },
                onSuccess = { (insertedIds, recommendation) ->
                    recommendation.nextSafeDoseAtMs?.let { whenMs ->
                        de.rochefort.childmonitor.tracker.MedicationReminderReceiver.schedule(
                            context = applicationContext,
                            drug = drug.name,
                            childId = childId,
                            childName = profile.name,
                            whenWallMs = whenMs
                        )
                    }
                    TrackerSaveUndo.show(
                        activity = this,
                        store = store,
                        childId = childId,
                        insertedIds = insertedIds,
                        message = getString(R.string.tracker_saved_undo),
                        onUndone = {
                            saveButton.isEnabled = true
                            de.rochefort.childmonitor.tracker.MedicationReminderReceiver.cancel(
                                applicationContext,
                                drug.name,
                                childId,
                            )
                            restorePreviousMedicationReminder(drug, weightKg, profile.name)
                        },
                        onTimeout = { finish() },
                    )
                },
                onFailure = { error ->
                    saveButton.isEnabled = true
                    Toast.makeText(
                        this,
                        if (error is TrackerWriteRunner.Rejected) error.message else getString(R.string.tracker_save_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    companion object {
        /** Shared lock for recommend+insert so two instances cannot race past
         *  the 24h cap (CM-TRK-CR3-P0-2). */
        private val STORE_WRITE_LOCK = Any()
    }
}
