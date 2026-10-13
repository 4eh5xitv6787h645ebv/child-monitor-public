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
package de.rochefort.childmonitor.alarm

/**
 * Pure state machine for the #289 disconnect notification (no Android deps; time-injected).
 *
 * The disconnect path fires on every reconnect retry, so a naive "post each call" would
 * re-wake the screen / re-sound on every retry and, worse, re-post on every flap cycle
 * (code-review HIGH). This bounds it to **once per sustained outage**:
 *  - a brief blip shorter than [debounceMs] never posts (it reconnects before the threshold);
 *  - a real outage posts exactly once (the `posted` latch), no matter how many retries;
 *  - [acknowledge] (user opened the app) suppresses posting until the next reconnect;
 *  - [onReconnect] re-arms so a genuinely new outage alerts again.
 */
class ConnectionAlertState(private val debounceMs: Long = DEFAULT_DEBOUNCE_MS) {

    private var acknowledged = false
    private var posted = false
    private var inOutage = false
    private var firstDownMs = 0L

    /** Feed a disconnect tick (per retry). Returns true when the caller should POST now. */
    fun onDisconnect(nowMs: Long): Boolean {
        if (acknowledged) return false
        // Explicit flag rather than a 0L sentinel — nowMs can legitimately be 0.
        if (!inOutage) {
            inOutage = true
            firstDownMs = nowMs
        }
        if (!posted && nowMs - firstDownMs >= debounceMs) {
            posted = true
            return true
        }
        return false
    }

    /** Connection (re)established: clear the latches so a future outage can alert again. */
    fun onReconnect() {
        acknowledged = false
        posted = false
        inOutage = false
        firstDownMs = 0L
    }

    /** User acknowledged (opened the app): suppress posting until [onReconnect]. */
    fun acknowledge() {
        acknowledged = true
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 6_000L
    }
}
