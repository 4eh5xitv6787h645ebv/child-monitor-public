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
 */
package de.rochefort.childmonitor.alarm

/** Whether a special access matters on this device and, if so, whether it's been granted. */
enum class AccessState { GRANTED, NEEDED, NOT_APPLICABLE }

/**
 * #320: pure decision for the alert-delivery special-access prompts (no Android deps; SDK + grant
 * state injected). The connection-alert channel already requests setBypassDnd(true) + a full-screen
 * intent, but Android silently ignores bypassDnd unless the user has granted Notification Policy
 * (DnD) access (API 23+), and full-screen-intent is a separate user-revocable grant on API 34+.
 */
object AlertAccessPolicy {

    fun dnd(sdkInt: Int, granted: Boolean): AccessState = when {
        sdkInt < 23 -> AccessState.NOT_APPLICABLE
        granted -> AccessState.GRANTED
        else -> AccessState.NEEDED
    }

    fun fullScreenIntent(sdkInt: Int, granted: Boolean): AccessState = when {
        sdkInt < 34 -> AccessState.NOT_APPLICABLE // USE_FULL_SCREEN_INTENT auto-granted below 34
        granted -> AccessState.GRANTED
        else -> AccessState.NEEDED
    }

    /** True when at least one access is missing → surface the opt-in prompt. */
    fun anyNeeded(dnd: AccessState, fsi: AccessState): Boolean =
        dnd == AccessState.NEEDED || fsi == AccessState.NEEDED
}
