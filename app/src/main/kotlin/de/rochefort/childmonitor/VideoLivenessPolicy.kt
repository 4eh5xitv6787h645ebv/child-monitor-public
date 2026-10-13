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

/**
 * Pure decision for "is the parent's VIDEO channel currently live?" — i.e. is the child IP proven
 * good by recent snapshot frames, so an audio-only drop must NOT enter the reconnect/discovery loop
 * (see [ConnectionStatePolicy] and ListenActivity.onConnectionFailure / postErrorMessage).
 *
 * Two signals, both required:
 *  1. A decoded frame arrived RECENTLY. The window scales with the poll cadence (a slow/dark
 *     adaptive cadence frame is still legitimately "recent") but is CAPPED — at a slow ~10s cadence
 *     the old `interval * 2` floored-at-8s window grew to ~20s, long enough that a frame that
 *     predates an IP change could still read "fresh" and veto the activity-side discovery fallback
 *     while a saved-IP sweep (~5s) exhausted against a dead address.
 *  2. No ACTIVE run of fetch failures. A dead/changed IP makes snapshot fetches fail, so a
 *     recent-but-pre-change frame must not count as live; the failure streak ages the channel out
 *     within one poll instead of waiting for the whole freshness window.
 */
object VideoLivenessPolicy {

    /** Floor: even at a fast cadence we keep a frame "fresh" for a beat so a single slow fetch
     *  doesn't flap liveness. Mirrors the previous 8s floor. */
    const val MIN_FRESHNESS_MS = 8_000L

    /** Cap: at a slow/dark cadence the window must not stretch so far that a stale pre-IP-change
     *  frame can veto discovery during a saved-IP sweep. 12s still comfortably covers a 10s cadence. */
    const val MAX_FRESHNESS_MS = 12_000L

    fun isUp(
        lastFrameMs: Long,
        nowMs: Long,
        snapshotIntervalMs: Long,
        failureStreak: Int,
        minFreshnessMs: Long = MIN_FRESHNESS_MS,
        maxFreshnessMs: Long = MAX_FRESHNESS_MS,
    ): Boolean {
        if (lastFrameMs <= 0L) return false              // never received a frame
        if (failureStreak > 0) return false              // fetches are currently failing → not live
        val freshnessMs = (snapshotIntervalMs * 2).coerceIn(minFreshnessMs, maxFreshnessMs)
        val age = nowMs - lastFrameMs
        return age in 0 until freshnessMs                 // negative age (clock skew) is not trustworthy
    }
}
