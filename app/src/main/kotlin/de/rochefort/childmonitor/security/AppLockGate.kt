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
package de.rochefort.childmonitor.security

/**
 * #296: pure foreground app-lock state machine (no Android deps), driven by activity start/stop
 * callbacks. Decides when to show the unlock screen so that:
 *  - cold start locks (when enabled);
 *  - returning from the background re-locks;
 *  - in-app navigation (one activity → another) does NOT lock;
 *  - the unlock-prompt round-trip (our LockActivity + the system credential UI) does NOT cause a
 *    re-lock loop — that's what [beginUnlock]/[onUnlockSucceeded] guard.
 *
 * LockActivity itself is NOT fed to this gate (the Application callbacks skip it), so [startedCount]
 * counts only normal content activities. Thread-confinement: all calls happen on the main thread.
 */
class AppLockGate {

    private var startedCount = 0
    private var authenticated = false  // unlocked for the current foreground session
    private var unlocking = false      // an unlock prompt is in flight — don't reset/relock

    /**
     * #296: pre-draw check used from onActivityCreated to cover the cold-start flash window — launch
     * the unlock screen BEFORE the content activity's first frame when we're not authenticated. Does
     * not mutate the started-count (that's onActivityStarted's job); safe to call redundantly.
     */
    fun shouldLockOnCreate(enabled: Boolean): Boolean = enabled && !unlocking && !authenticated

    /** A content activity started. Returns true if the unlock screen should be shown now. */
    fun onActivityStarted(enabled: Boolean): Boolean {
        val cameToForeground = startedCount == 0
        startedCount++
        if (!enabled || unlocking) return false
        return cameToForeground && !authenticated
    }

    /** A content activity stopped. Returns true if the app just dropped to the background. */
    fun onActivityStopped(): Boolean {
        if (startedCount > 0) startedCount--
        return startedCount == 0
    }

    /** Confirmed in the background (after the transition debounce). Require auth on next foreground. */
    fun onEnteredBackground() {
        if (!unlocking) authenticated = false
    }

    /** The unlock prompt (LockActivity + system credential) is being shown. */
    fun beginUnlock() {
        unlocking = true
    }

    fun onUnlockSucceeded() {
        authenticated = true
        unlocking = false
    }

    /** User cancelled / failed: stay unauthenticated (the caller backgrounds the app). */
    fun onUnlockFailedOrCancelled() {
        unlocking = false
    }

    fun isAuthenticated(): Boolean = authenticated

    /** #375 lock-on-enable: drop the current authentication so the next foreground re-locks (used
     *  when the user just turned the lock on, so it takes effect without needing a background trip). */
    fun requireReauth() {
        authenticated = false
    }
}
