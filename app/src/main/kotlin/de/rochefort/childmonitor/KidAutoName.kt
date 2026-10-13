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
 * Decides the [PairedChild.userName] to assign when a child is paired by QR (#402 Kids).
 *
 * Scanning a QR is a deliberate act of binding to a specific device, so we name the child
 * immediately — that flips [PairedChild.isPermanent] on, which exempts it from the recency
 * cap and makes reconnect-by-deviceId preserve the name. Without this, a freshly scanned kid
 * is "Not named yet", non-permanent, and can be evicted by churn — forcing a rescan after an
 * IP change, which is exactly the bug this fixes.
 *
 * Pure (no Android deps) so it is unit-testable directly. References [PairedChild] (same package).
 */
object KidAutoName {

    /** Upper bound on an auto-assigned name; a long advertised name is truncated to this. */
    const val MAX_LEN = 40

    /**
     * Decide the userName to assign when a child is paired by QR.
     *  - Re-scan of a [deviceId] that already has a non-blank userName → keep it (don't clobber a
     *    manual rename the user made earlier).
     *  - Else prefer the trimmed advertised [advertisedName] (capped to [MAX_LEN]), unless it is
     *    blank or it collides with a DIFFERENT device's userName (names should stay unambiguous).
     *  - Else generate the smallest unused "kidN" (N >= 1).
     */
    fun assign(advertisedName: String?, deviceId: String, existing: List<PairedChild>): String {
        // Don't clobber a name the user set on a previous scan/rename of this same device.
        existing.firstOrNull { it.deviceId == deviceId }
            ?.userName
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        // Names already in use by OTHER devices — a candidate must not collide with these.
        val takenByOthers = existing
            .filter { it.deviceId != deviceId }
            .mapNotNull { it.userName?.takeIf { name -> name.isNotBlank() } }
            .toSet()

        val candidate = advertisedName?.trim()?.takeIf { it.isNotBlank() }?.take(MAX_LEN)
        if (candidate != null && candidate !in takenByOthers) return candidate

        // Fall back to the smallest unused "kidN" (N >= 1).
        var n = 1
        while ("kid$n" in takenByOthers) n++
        return "kid$n"
    }
}
