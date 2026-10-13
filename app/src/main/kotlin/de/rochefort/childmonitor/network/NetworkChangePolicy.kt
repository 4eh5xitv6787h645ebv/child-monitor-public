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
package de.rochefort.childmonitor.network

/**
 * Pure decision logic for "did the child device's local IP MEANINGFULLY change,
 * such that we must re-advertise the NSD service?"
 *
 * The audio ServerSocket is wildcard-bound (0.0.0.0) so it needs no rebind on an
 * IP change; only the mDNS/NSD advertisement carries the (now-stale) address and
 * must be refreshed. A same-network DHCP renew / AP roam changes the local IP
 * without firing NetworkCallback.onAvailable, so the IP set delivered via
 * onLinkPropertiesChanged is the signal we key off here.
 *
 * Kept free of Android imports so it can be unit-tested on the plain JVM.
 */
object NetworkChangePolicy {

    /** Non-loopback, non-link-local IPv4/IPv6 addresses from a raw list. */
    fun relevant(addresses: List<String>): Set<String> = addresses
        .filter { it.isNotBlank() && !it.startsWith("127.") && it != "::1" && !it.startsWith("fe80", ignoreCase = true) }
        .toSet()

    /**
     * True iff the relevant address set MEANINGFULLY changed (so we should re-advertise):
     *  false when new is empty, false on the first observation (old empty), false when equal.
     */
    fun shouldRefreshAdvertisement(old: Set<String>, new: Set<String>): Boolean =
        new.isNotEmpty() && old.isNotEmpty() && old != new

    /** Outcome of one Network's onLinkPropertiesChanged event. */
    data class LinkDecision(val fire: Boolean, val nextLastAddresses: Set<String>)

    /**
     * Never debounce a meaningful address change. Android does not promise a follow-up link-properties
     * callback, so suppressing the only A->B event can leave the old address advertised indefinitely.
     * Repeated identical callbacks are naturally deduplicated by recording [relevantNew].
     */
    fun decideLinkChange(
        lastAddresses: Set<String>,
        relevantNew: Set<String>,
    ): LinkDecision = when {
        // A loss, not a change — handled by onLost; leave lastAddresses untouched.
        relevantNew.isEmpty() -> LinkDecision(false, lastAddresses)
        // Prime on the first non-empty observation, or no real change — record the set, don't fire.
        !shouldRefreshAdvertisement(lastAddresses, relevantNew) -> LinkDecision(false, relevantNew)
        // Real change — fire and advance. Identical subsequent callbacks are deduplicated.
        else -> LinkDecision(true, relevantNew)
    }
}
