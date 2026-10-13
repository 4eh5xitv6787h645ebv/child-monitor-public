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
 * Resolves the stable child [PairedChild.deviceId] for an authenticated reconnect when
 * the launching intent didn't carry one.
 *
 * #278: every post-pairing reconnect entry point except the QR scan launches
 * [ListenActivity] with only `address`/`port`/`name` — `StartActivity` Quick Reconnect,
 * `BootService.launchParent`, and `DiscoverActivity` all omit the deviceId. Without it
 * `ListenService` can't look up the bearer token, so it sends an unauthenticated
 * `CMHELLO 1 \n`; the child rejects it (`reason=format`/401) and the reconnect loop
 * livelocks with no audio. Recovering the deviceId from the paired-children store lets
 * the token attach on the very first handshake.
 *
 * Resolution order:
 *  1. exact last-known-IP match — the common "reconnect to the same IP" case;
 *  2. the sole paired child, when exactly one is paired — covers "the child's IP changed"
 *     for the overwhelmingly common single-baby setup;
 *  3. null when ambiguous (several paired children, none matching the target IP) so we
 *     never attach the wrong child's token — NSD discovery resolves it by deviceId.
 */
object PairedChildResolver {

    fun resolve(paired: List<PairedChild>, targetAddress: String?): String? {
        if (paired.isEmpty()) return null
        if (!targetAddress.isNullOrBlank()) {
            paired.firstOrNull { it.lastKnownIp == targetAddress }?.let { return it.deviceId }
        }
        return paired.singleOrNull()?.deviceId
    }
}
