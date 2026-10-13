package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.TrackerStore

/** Connects a committed insert (or insertAll) to the shared five-second undo surface. */
object TrackerSaveUndo {
    fun show(
        activity: Activity,
        store: TrackerStore,
        childId: String,
        insertedIds: Collection<Long>,
        message: CharSequence,
        onUndone: () -> Unit = {},
        onTimeout: () -> Unit = {},
    ) {
        val ids = insertedIds.distinct()
        require(ids.isNotEmpty()) { "at least one inserted tracker row is required" }
        TrackerUndoBar.show(
            activity = activity,
            message = message,
            onUndo = {
                TrackerWriteRunner.run(
                    activity = activity,
                    write = {
                        check(store.softDeleteAll(childId, ids) == ids.size) {
                            "one or more tracker events were not found"
                        }
                    },
                    onSuccess = {
                        Toast.makeText(activity, R.string.tracker_change_undone, Toast.LENGTH_SHORT).show()
                        onUndone()
                    },
                    onFailure = {
                        Toast.makeText(activity, R.string.tracker_undo_failed, Toast.LENGTH_LONG).show()
                    },
                )
            },
            onTimeout = onTimeout,
        )
    }
}
