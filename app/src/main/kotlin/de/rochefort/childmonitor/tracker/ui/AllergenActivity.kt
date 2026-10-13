package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.AllergenIntroOverrideStore
import de.rochefort.childmonitor.tracker.ChildProfile
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import de.rochefort.childmonitor.tracker.TrackerInputValidation
import de.rochefort.childmonitor.tracker.guidance.AllergenIntroGate
import de.rochefort.childmonitor.tracker.guidance.AllergenLadder
import org.json.JSONObject

class AllergenActivity : Activity() {

    private val allergens = listOf(
        TrackerEvent.AllergenSubkind.PEANUT,
        TrackerEvent.AllergenSubkind.EGG,
        TrackerEvent.AllergenSubkind.MILK,
        TrackerEvent.AllergenSubkind.WHEAT,
        TrackerEvent.AllergenSubkind.SOY,
        TrackerEvent.AllergenSubkind.SESAME,
        TrackerEvent.AllergenSubkind.TREE_NUT,
        TrackerEvent.AllergenSubkind.FISH,
        TrackerEvent.AllergenSubkind.SHELLFISH
    )

    private lateinit var childId: String
    private lateinit var store: TrackerStore
    private lateinit var listView: LinearLayout
    private var refreshGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_allergen)

        val id = intent.getStringExtra(BabyDashboardActivity.EXTRA_CHILD_ID)
            ?: ChildProfileStore.get(applicationContext).activeProfile()?.id
        if (id == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        childId = id
        store = TrackerStore.get(applicationContext)
        listView = findViewById(R.id.allergenList)
        findViewById<Button>(R.id.logButton).setOnClickListener { showLogDialog() }
    }

    override fun onResume() {
        super.onResume()
        if (TrackerActivityGuard.resolve(this, childId) == null) return
        refresh()
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        listView.removeAllViews()
        TrackerWriteRunner.run(
            activity = this,
            write = { store.recentByKind(childId, TrackerEvent.Kind.FEED_ALLERGEN, limit = 1000) },
            onSuccess = { exposures ->
                if (generation == refreshGeneration) render(exposures)
            },
            onFailure = {
                Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun render(allExposures: List<TrackerEvent>) {
        listView.removeAllViews()
        val now = System.currentTimeMillis()
        val tier = riskTier()
        allergens.forEach { allergen ->
            val exposures = allExposures
                .filter { it.subkind == allergen }
                .map { ev ->
                    val grams = ev.valueNum ?: 0.0
                    val reactionStr = ev.extra?.optString("reaction") ?: "NONE"
                    val reaction = runCatching { AllergenLadder.Reaction.valueOf(reactionStr) }
                        .getOrDefault(AllergenLadder.Reaction.NONE)
                    AllergenLadder.Exposure(ev.tsWallMs, grams, reaction)
                }
            val evaluation = AllergenLadder.evaluate(allergen, exposures, now)
            val gate = AllergenIntroGate.evaluate(
                tier, allergen, evaluation.status,
                AllergenIntroOverrideStore.isClearedByClinician(this, childId, allergen)
            )

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12, 12, 12, 12)
                // #163: red-highlight the card while a NIAID high-risk first
                // exposure is gated pending a clinician test.
                if (gate.blocked) {
                    setBackgroundColor(androidx.core.content.ContextCompat.getColor(
                        this@AllergenActivity, de.rochefort.childmonitor.R.color.tracker_danger_bg))
                }
            }
            val titleColor = if (gate.blocked) de.rochefort.childmonitor.R.color.tracker_danger
                else de.rochefort.childmonitor.R.color.tracker_text_primary
            val title = TextView(this).apply {
                val suffix = if (gate.blocked) getString(
                    R.string.tracker_allergen_blocked_suffix,
                    getString(R.string.tracker_allergen_gate_badge),
                ) else ""
                text = getString(
                    R.string.tracker_allergen_status_line,
                    labelFor(allergen), statusLabel(evaluation.status), suffix,
                )
                setTextColor(androidx.core.content.ContextCompat.getColor(this@AllergenActivity, titleColor))
                textSize = 16f
            }
            val rationale = TextView(this).apply {
                text = if (gate.blocked) gate.message else evaluation.rationale
                val color = if (gate.blocked) de.rochefort.childmonitor.R.color.tracker_danger
                    else de.rochefort.childmonitor.R.color.tracker_text_secondary
                setTextColor(androidx.core.content.ContextCompat.getColor(this@AllergenActivity, color))
                textSize = 12f
            }
            val count = TextView(this).apply {
                text = getString(R.string.tracker_allergen_count, exposures.size)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@AllergenActivity, de.rochefort.childmonitor.R.color.tracker_text_secondary))
                textSize = 12f
            }
            row.addView(title)
            row.addView(rationale)
            row.addView(count)
            listView.addView(row)

            val divider = android.view.View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@AllergenActivity, de.rochefort.childmonitor.R.color.tracker_bg_surface))
            }
            listView.addView(divider)
        }
    }

    private fun labelFor(allergen: String): String = when (allergen) {
        TrackerEvent.AllergenSubkind.PEANUT -> getString(R.string.tracker_allergen_label_peanut)
        TrackerEvent.AllergenSubkind.EGG -> getString(R.string.tracker_allergen_label_egg)
        TrackerEvent.AllergenSubkind.MILK -> getString(R.string.tracker_allergen_label_milk)
        TrackerEvent.AllergenSubkind.WHEAT -> getString(R.string.tracker_allergen_label_wheat)
        TrackerEvent.AllergenSubkind.SOY -> getString(R.string.tracker_allergen_label_soy)
        TrackerEvent.AllergenSubkind.SESAME -> getString(R.string.tracker_allergen_label_sesame)
        TrackerEvent.AllergenSubkind.TREE_NUT -> getString(R.string.tracker_allergen_label_tree_nut)
        TrackerEvent.AllergenSubkind.FISH -> getString(R.string.tracker_allergen_label_fish)
        TrackerEvent.AllergenSubkind.SHELLFISH -> getString(R.string.tracker_allergen_label_shellfish)
        else -> allergen
    }

    private fun showLogDialog() {
        val ctx = this
        val group = RadioGroup(ctx)
        allergens.forEach { a ->
            val rb = RadioButton(ctx).apply {
                id = android.view.View.generateViewId()
                text = labelFor(a)
                tag = a
            }
            group.addView(rb)
        }
        group.check(group.getChildAt(0).id)

        val gramsEdit = EditText(ctx).apply {
            hint = getString(R.string.tracker_allergen_protein_hint)
            inputType = android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or android.text.InputType.TYPE_CLASS_NUMBER
        }
        val reactionGroup = RadioGroup(ctx)
        listOf(
            AllergenLadder.Reaction.NONE,
            AllergenLadder.Reaction.MILD_MODERATE,
            AllergenLadder.Reaction.ANAPHYLAXIS
        ).forEach { r ->
            val rb = RadioButton(ctx).apply {
                id = android.view.View.generateViewId()
                text = r.name
                tag = r
            }
            reactionGroup.addView(rb)
        }
        reactionGroup.check(reactionGroup.getChildAt(0).id)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(ctx).apply { text = getString(R.string.tracker_allergen_field_allergen) })
            addView(group)
            addView(TextView(ctx).apply { text = getString(R.string.tracker_allergen_field_grams) })
            addView(gramsEdit)
            addView(TextView(ctx).apply { text = getString(R.string.tracker_allergen_field_reaction) })
            addView(reactionGroup)
        }

        // Keep the dialog open until SQLite confirms the insert. The normal
        // AlertDialog positive listener auto-dismisses before an asynchronous
        // failure can be reported, losing the caregiver's input (#459).
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.tracker_allergen_log_title)
            .setView(container)
            .setPositiveButton(R.string.tracker_save, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val selectedId = group.checkedRadioButtonId
                val selected = group.findViewById<RadioButton>(selectedId)?.tag as? String
                if (selected == null) {
                    Toast.makeText(this, R.string.tracker_allergen_pick_one, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val reactionSelectedId = reactionGroup.checkedRadioButtonId
                val reaction = reactionGroup.findViewById<RadioButton>(reactionSelectedId)?.tag as? AllergenLadder.Reaction
                    ?: AllergenLadder.Reaction.NONE
                val gramsRaw = gramsEdit.text.toString().trim()
                if (!TrackerInputValidation.requiredFiniteDouble(gramsRaw, 0.0..1000.0)) {
                    Toast.makeText(this, R.string.tracker_allergen_bad_grams, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val grams = gramsRaw.toDouble()
                saveButton.isEnabled = false
                TrackerWriteRunner.run(
                    activity = this,
                    write = { store.recentByKind(childId, TrackerEvent.Kind.FEED_ALLERGEN, limit = 1000) },
                    onSuccess = { allExposures ->
                        // #163: gate the first exposure for HIGH-risk infants on the NIAID
                        // pre-introduction allergens. The override is per child+allergen.
                        val gate = AllergenIntroGate.evaluate(
                            riskTier(), selected, statusOf(selected, allExposures),
                            AllergenIntroOverrideStore.isClearedByClinician(this, childId, selected)
                        )
                        val save = {
                            persistExposure(
                                selected = selected,
                                reaction = reaction,
                                grams = grams,
                                onSaved = { id ->
                                    dialog.dismiss()
                                    refresh()
                                    TrackerSaveUndo.show(
                                        activity = this,
                                        store = store,
                                        childId = childId,
                                        insertedIds = listOf(id),
                                        message = getString(R.string.tracker_saved_undo),
                                        onUndone = { refresh() },
                                    )
                                    if (reaction == AllergenLadder.Reaction.ANAPHYLAXIS) {
                                        launchAnaphylaxisFlow()
                                    }
                                },
                                onFailure = { saveButton.isEnabled = true },
                            )
                        }
                        if (gate.blocked) {
                            showPreIntroGateDialog(
                                allergen = selected,
                                onProceed = {
                                    AllergenIntroOverrideStore.markClearedByClinician(this, childId, selected)
                                    save()
                                },
                                onCancel = { saveButton.isEnabled = true },
                            )
                        } else {
                            save()
                        }
                    },
                    onFailure = {
                        saveButton.isEnabled = true
                        Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
                    },
                )
            }
        }
        dialog.show()
    }

    /** Insert the exposure event and fire the anaphylaxis flow when needed. */
    private fun persistExposure(
        selected: String,
        reaction: AllergenLadder.Reaction,
        grams: Double,
        onSaved: (Long) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val extra = JSONObject().put("reaction", reaction.name)
        val event = TrackerEvent(
            childId = childId,
            kind = TrackerEvent.Kind.FEED_ALLERGEN,
            subkind = selected,
            valueNum = grams,
            extra = extra
        )
        TrackerWriteRunner.run(this,
            write = { store.insert(event) },
            onSuccess = { id -> onSaved(id) },
            onFailure = { error ->
                onFailure(error)
                Toast.makeText(this, R.string.tracker_save_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun launchAnaphylaxisFlow() {
            // Launch after the dialog finishes dismissing to avoid a
            // brief flash (CM-TRK-CR3-P2-16). NEW_TASK + CLEAR_TOP so
            // it surfaces even when the app is backgrounded.
            window.decorView.post {
                val intent = android.content.Intent(this, AnaphylaxisActivity::class.java).apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                try {
                    startActivity(intent)
                } catch (_: Exception) {
                    // Background-launch blocked: fall back to a high-priority
                    // notification that fires the red screen via
                    // `fullScreenIntent`.
                    postAnaphylaxisNotification(intent)
                }
            }
    }

    /**
     * NIAID pre-introduction safety dialog (#163). Shows the clinician-test
     * recommendation and only proceeds via an explicit "cleared by my clinician"
     * override; the plain cancel leaves the first exposure unlogged.
     */
    private fun showPreIntroGateDialog(
        allergen: String,
        onProceed: () -> Unit,
        onCancel: () -> Unit,
    ) {
        // Guard the override the same way the log dialog guards save: a fast
        // double-tap on "Proceed anyway" must not insert two exposure rows
        // (which would falsely advance the maintenance ladder).
        val proceedGuard = java.util.concurrent.atomic.AtomicBoolean(false)
        AlertDialog.Builder(this)
            .setTitle(R.string.tracker_allergen_gate_title)
            .setMessage("${labelFor(allergen)}\n\n${AllergenIntroGate.MESSAGE}\n\n${AllergenIntroGate.SOURCE}")
            .setNegativeButton(R.string.tracker_allergen_gate_cancel) { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .setPositiveButton(R.string.tracker_allergen_gate_override) { _, _ ->
                if (proceedGuard.compareAndSet(false, true)) onProceed()
            }
            .show()
    }

    private fun riskTier(): ChildProfile.AllergyRiskTier =
        ChildProfileStore.get(applicationContext).list().firstOrNull { it.id == childId }?.allergyRiskTier
            ?: ChildProfile.AllergyRiskTier.LOW

    private fun statusLabel(status: AllergenLadder.Status): String = getString(when (status) {
        AllergenLadder.Status.NOT_STARTED -> R.string.tracker_allergen_status_not_started
        AllergenLadder.Status.INTRODUCED -> R.string.tracker_allergen_status_introduced
        AllergenLadder.Status.ESTABLISHED -> R.string.tracker_allergen_status_established
        AllergenLadder.Status.LAPSING -> R.string.tracker_allergen_status_lapsing
        AllergenLadder.Status.AVOID -> R.string.tracker_allergen_status_avoid
    })

    /** Current ladder status for [allergen], used by the pre-introduction gate. */
    private fun statusOf(
        allergen: String,
        allExposures: List<TrackerEvent>,
    ): AllergenLadder.Status {
        val now = System.currentTimeMillis()
        val exposures = allExposures
            .filter { it.subkind == allergen }
            .map { ev ->
                val reaction = runCatching {
                    AllergenLadder.Reaction.valueOf(ev.extra?.optString("reaction") ?: "NONE")
                }.getOrDefault(AllergenLadder.Reaction.NONE)
                AllergenLadder.Exposure(ev.tsWallMs, ev.valueNum ?: 0.0, reaction)
            }
        return AllergenLadder.evaluate(allergen, exposures, now).status
    }

    private fun postAnaphylaxisNotification(redScreen: android.content.Intent) {
        // CM-TRK-CR4-P0-2: check runtime permission before notify; fall back
        // to a visible Toast when the user hasn't granted POST_NOTIFICATIONS.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Toast.makeText(this, R.string.tracker_anaphylaxis_no_dismiss_notification,
                    Toast.LENGTH_LONG).show()
                // Also log so the event is captured in the diagnostic bundle
                // even when the app is backgrounded and the Toast is
                // suppressed (CM-TRK-CR5-P2-4).
                de.rochefort.childmonitor.LogManager.e(
                    de.rochefort.childmonitor.LogManager.LogCategory.SYSTEM,
                    "Anaphylaxis alert fallback notification BLOCKED: POST_NOTIFICATIONS denied"
                )
                return
            }
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "cm_tracker_anaphylaxis"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId, "Anaphylaxis alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }
        val pi = android.app.PendingIntent.getActivity(
            this, 0, redScreen,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        // On API 34+, setFullScreenIntent requires USE_FULL_SCREEN_INTENT AND
        // the user can revoke it per-app. Fall back to a HIGH-priority heads-up.
        val canFullScreen = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            nm.canUseFullScreenIntent()
        } else true
        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.listening_notification)
            .setContentTitle(getString(R.string.tracker_anaphylaxis_header))
            .setContentText(getString(R.string.tracker_anaphylaxis_body))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pi)
            .setAutoCancel(false)
            .setOngoing(true)
        if (canFullScreen) builder.setFullScreenIntent(pi, true)
        nm.notify(ANAPHYLAXIS_NOTIF_ID, builder.build())
    }

    companion object {
        private const val ANAPHYLAXIS_NOTIF_ID = 0xA11ED
    }
}
