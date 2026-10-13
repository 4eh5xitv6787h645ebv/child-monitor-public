package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.widget.Toast
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.ChildProfile
import de.rochefort.childmonitor.tracker.ChildProfileStore

/**
 * Shared helper so tracker activities always fail fast when the active child
 * has been deleted out from under them (CM-TRK-CR4-P1-4). Call from `onResume`
 * in addition to `onCreate` — profile deletion from ChildProfileActivity can
 * happen while an entry activity is in the back stack.
 */
internal object TrackerActivityGuard {

    /**
     * Returns the resolved [ChildProfile] for [childId], or finishes [activity]
     * with a toast when the profile no longer exists. Callers should treat a
     * null return as "stop doing work".
     */
    fun resolve(activity: Activity, childId: String?): ChildProfile? {
        // Single instance of the store + one pass over the profile list
        // (CM-TRK-CR5-P2-3).
        val store = ChildProfileStore.get(activity.applicationContext)
        val profiles = store.list()
        val id = childId ?: store.getActiveId()
        val profile = id?.let { target -> profiles.firstOrNull { it.id == target } }
        if (profile == null) {
            Toast.makeText(activity, R.string.tracker_create_profile_first, Toast.LENGTH_SHORT).show()
            activity.finish()
        }
        return profile
    }
}
