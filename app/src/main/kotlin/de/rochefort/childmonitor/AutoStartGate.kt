/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor

import android.Manifest

/**
 * Shared gating for "auto-start on boot" (#359/#379). Enabling auto-start for a role requires, in
 * order: the role's runtime permissions, (parent only) somewhere to reconnect to, and the overlay
 * permission — the monitor is launched from the background at boot, which needs "display over other
 * apps" on Android 10+. This logic was previously duplicated inline across StartActivity (5 blocks)
 * and SetupWizard (which wrongly skipped the overlay check); centralizing the DECISION here keeps the
 * Settings toggle, the home-screen checkboxes, and the wizard consistent. The Android request
 * mechanics (requestPermissions / overlay intent) stay in each Activity by necessity.
 *
 * The decision functions are pure (boolean inputs) and unit-tested.
 */
object AutoStartGate {

    enum class Role { CHILD, PARENT }

    enum class Decision {
        /** All gates satisfied — persist the pref. */
        PERSIST,
        /** Missing the role's runtime permissions — request them. */
        REQUEST_PERMISSIONS,
        /** Missing the overlay permission — send the user to grant it. */
        REQUEST_OVERLAY,
        /** Parent has no saved child to reconnect to — block with a hint (never for child). */
        BLOCK_NO_SAVED_CHILD
    }

    const val KEY_CHILD = "autoStartChild"
    const val KEY_PARENT = "autoStartParent"

    fun prefKey(role: Role): String = if (role == Role.CHILD) KEY_CHILD else KEY_PARENT

    /** Child captures audio + camera; parent only plays received audio. */
    fun requiredPermissions(role: Role): Array<String> = when (role) {
        Role.CHILD -> arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        Role.PARENT -> arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    /** Parent auto-start needs somewhere to reconnect to at boot: a saved IP OR a paired kid (#406). */
    fun hasSavedChild(ipHistory: String?, pairedChildCount: Int): Boolean =
        !ipHistory.isNullOrBlank() || pairedChildCount > 0

    /**
     * What must happen to ENABLE auto-start for [role], given the current grants. Gate order:
     * permissions → (parent) a saved child → overlay → else persist. Disabling never gates — the
     * caller persists `false` directly.
     */
    fun decideEnable(
        role: Role,
        hasPermissions: Boolean,
        hasSavedChild: Boolean,
        canDrawOverlays: Boolean
    ): Decision = when {
        !hasPermissions -> Decision.REQUEST_PERMISSIONS
        role == Role.PARENT && !hasSavedChild -> Decision.BLOCK_NO_SAVED_CHILD
        !canDrawOverlays -> Decision.REQUEST_OVERLAY
        else -> Decision.PERSIST
    }
}
