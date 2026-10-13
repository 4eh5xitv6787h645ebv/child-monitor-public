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
 * The parent holds TWO independent channels to the child — AUDIO (raw TCP, owned by ListenService)
 * and VIDEO (HTTPS snapshot polling, owned by ListenActivity). Either one being live PROVES the
 * child's IP is currently reachable, so a single channel dropping must NOT drag the parent into the
 * NSD-discovery / saved-IP-sweep reconnect loop (which would also retarget the still-working channel
 * to a churning address).
 *
 * This policy maps the liveness of the two channels to the action to take when one reports a failure:
 *  - BOTH up   → CONNECTED: nothing is actually down; ignore the spurious error.
 *  - ONE up    → RETRY_MISSING_CHANNEL: the IP is known-good; let the down channel self-heal in
 *                place (audio's doListen retries the same address; the snapshot loop keeps polling).
 *  - BOTH down → FULL_RECONNECT: the legitimate disconnect — enter the reconnect/discovery loop.
 *
 * Pure (no Android deps) so it is unit-testable directly.
 */
object ConnectionStatePolicy {
    enum class Action { CONNECTED, RETRY_MISSING_CHANNEL, FULL_RECONNECT }

    fun onChannelDown(audioUp: Boolean, videoUp: Boolean): Action = when {
        audioUp && videoUp -> Action.CONNECTED
        audioUp || videoUp -> Action.RETRY_MISSING_CHANNEL
        else               -> Action.FULL_RECONNECT
    }
}
