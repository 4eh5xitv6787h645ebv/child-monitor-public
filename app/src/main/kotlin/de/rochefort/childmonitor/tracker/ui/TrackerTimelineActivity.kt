package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.ChildProfileStore
import de.rochefort.childmonitor.tracker.TrackerEvent
import de.rochefort.childmonitor.tracker.TrackerStore
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Chronological list of every tracker event for the active child.
 *
 * Implements CM-TRK-REV-003. Reads via [TrackerStore.recent]; groups by day
 * (device timezone) and renders accessible edit/delete actions. Deletes are retained for the
 * 30-day recovery window and can be undone immediately (#483).
 */
class TrackerTimelineActivity : Activity() {

    private lateinit var container: LinearLayout
    private lateinit var store: TrackerStore
    private lateinit var childId: String
    private lateinit var recentlyDeletedButton: Button
    private var recentlyDeleted: List<TrackerEvent> = emptyList()
    private var refreshGeneration = 0
    private val dayFmt = SimpleDateFormat("EEE d MMM yyyy", java.util.Locale.getDefault())
    private val timeFmt = SimpleDateFormat("HH:mm", java.util.Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tracker_timeline)

        val id = intent.getStringExtra(BabyDashboardActivity.EXTRA_CHILD_ID)
            ?: ChildProfileStore.get(applicationContext).activeProfile()?.id
        if (id == null) {
            Toast.makeText(this, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            finish(); return
        }
        childId = id
        store = TrackerStore.get(applicationContext)
        container = findViewById(R.id.timelineContainer)
        recentlyDeletedButton = findViewById(R.id.recentlyDeletedButton)
        recentlyDeletedButton.setOnClickListener { showRecentlyDeleted() }
    }

    override fun onResume() {
        super.onResume()
        if (TrackerActivityGuard.resolve(this, childId) == null) return
        refresh()
    }

    private fun refresh() {
        val generation = ++refreshGeneration
        container.removeAllViews()
        TrackerWriteRunner.run(
            activity = this,
            write = {
                store.purgeDeletedBefore(System.currentTimeMillis() - DELETED_RETENTION_MS)
                store.recent(childId, limit = 500) to
                    store.recentlyDeleted(
                        childId,
                        deletedAfterMs = System.currentTimeMillis() - DELETED_RETENTION_MS,
                        limit = 500,
                    )
            },
            onSuccess = { (events, deleted) ->
                if (generation == refreshGeneration) {
                    recentlyDeleted = deleted
                    recentlyDeletedButton.isEnabled = deleted.isNotEmpty()
                    recentlyDeletedButton.text = resources.getQuantityString(
                        R.plurals.tracker_recently_deleted_count,
                        deleted.size,
                        deleted.size,
                    )
                    render(events)
                }
            },
            onFailure = {
                Toast.makeText(this, R.string.tracker_load_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun render(events: List<TrackerEvent>) {
        container.removeAllViews()
        if (events.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.tracker_timeline_empty)
                setTextColor(androidx.core.content.ContextCompat.getColor(this@TrackerTimelineActivity, de.rochefort.childmonitor.R.color.tracker_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, 32, 0, 0)
            }
            container.addView(empty)
            return
        }
        val byDay = events.groupBy { dayFmt.format(Date(it.tsWallMs)) }
        for ((day, rows) in byDay) {
            val dayHeader = TextView(this).apply {
                text = day
                setTextColor(androidx.core.content.ContextCompat.getColor(this@TrackerTimelineActivity, de.rochefort.childmonitor.R.color.tracker_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(0, 24, 0, 8)
            }
            container.addView(dayHeader)
            for (ev in rows) container.addView(rowFor(ev))
        }
    }

    private fun rowFor(event: TrackerEvent): LinearLayout {
        val rowLabel = getString(
            R.string.tracker_timeline_row,
            timeFmt.format(Date(event.tsWallMs)),
            TrackerEventLabels.event(this, event),
        )
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 6, 8, 6)
            minimumHeight = (48 * resources.displayMetrics.density).toInt()
            isClickable = true
            isLongClickable = true
            contentDescription = getString(R.string.tracker_timeline_action_hint, rowLabel)
            setOnClickListener { showActions(event) }
            setOnLongClickListener { showActions(event); true }
        }
        val title = TextView(this).apply {
            text = rowLabel
            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setTextColor(androidx.core.content.ContextCompat.getColor(this@TrackerTimelineActivity, de.rochefort.childmonitor.R.color.tracker_timeline_row_title))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        row.addView(title)
        val details = TrackerEventLabels.details(this, event)
        if (details.isNotBlank()) {
            val body = TextView(this).apply {
                // Plain text only — event.note is parent-typed and must not be
                // fed to Html.fromHtml (CM-TRK-CR-P2-3).
                text = details
                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setTextColor(androidx.core.content.ContextCompat.getColor(this@TrackerTimelineActivity, de.rochefort.childmonitor.R.color.tracker_text_muted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            row.addView(body)
        }
        val divider = android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@TrackerTimelineActivity, de.rochefort.childmonitor.R.color.tracker_bg_surface))
        }
        row.addView(divider)
        return row
    }

    private fun confirmDelete(event: TrackerEvent) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.tracker_timeline_delete_confirm, TrackerEventLabels.event(this, event)))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                TrackerWriteRunner.run(this,
                    write = {
                        check(store.softDelete(childId, event.id) == 1) {
                            "tracker event was not found"
                        }
                    },
                    onSuccess = {
                        refresh()
                        TrackerUndoBar.show(
                            activity = this,
                            message = getString(R.string.tracker_deleted_undo),
                            onUndo = { restoreDeleted(event) },
                        )
                    },
                    onFailure = {
                        Toast.makeText(this, R.string.tracker_save_failed, Toast.LENGTH_LONG).show()
                    },
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showActions(event: TrackerEvent) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tracker_timeline_actions, TrackerEventLabels.event(this, event)))
            .setItems(arrayOf(getString(R.string.tracker_edit), getString(R.string.tracker_delete))) { _, which ->
                if (which == 0) {
                    startActivity(
                        Intent(this, TrackerEventEditActivity::class.java)
                            .putExtra(TrackerEventEditActivity.EXTRA_CHILD_ID, childId)
                            .putExtra(TrackerEventEditActivity.EXTRA_EVENT_ID, event.id),
                    )
                } else {
                    confirmDelete(event)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun restoreDeleted(event: TrackerEvent) {
        TrackerWriteRunner.run(
            activity = this,
            write = { check(store.restore(childId, event.id) == 1) { "tracker event was not found" } },
            onSuccess = {
                refresh()
                Toast.makeText(this, R.string.tracker_change_undone, Toast.LENGTH_SHORT).show()
            },
            onFailure = {
                Toast.makeText(this, R.string.tracker_undo_failed, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun showRecentlyDeleted() {
        if (recentlyDeleted.isEmpty()) return
        val choices = recentlyDeleted.map { event ->
            getString(
                R.string.tracker_timeline_row,
                timeFmt.format(Date(event.tsWallMs)),
                TrackerEventLabels.event(this, event),
            )
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.tracker_recently_deleted_title)
            .setItems(choices) { _, which ->
                val event = recentlyDeleted.getOrNull(which) ?: return@setItems
                AlertDialog.Builder(this)
                    .setMessage(getString(R.string.tracker_restore_confirm, choices[which]))
                    .setPositiveButton(R.string.tracker_restore) { _, _ -> restoreDeleted(event) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        TrackerUndoBar.dismiss(this)
        super.onDestroy()
    }

    companion object {
        private const val DELETED_RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
