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
 * Pure (Android-free) roster operations over a list of [PairedChild], backing the
 * "Kids" feature: a parent names a child device (e.g. "kid1") so it becomes a
 * PERMANENT link — reconnectable by stable deviceId no matter how the IP changes,
 * with no re-pair (the durable refresh credential in [ParentSessionStore] survives
 * bearer expiry, #348).
 *
 * Two invariants make a named kid "permanent":
 *  1. **Merge-preserve.** Network code re-[upserted]s a child on every reconnect with
 *     the CHILD-advertised name and a fresh IP. That path must NEVER clobber the
 *     user-assigned [PairedChild.userName] or the original [PairedChild.createdAtMs].
 *     Only the explicit UI mutator ([withUserName]) changes the name.
 *  2. **Never-evict.** [capped] caps the roster to [MAX_RECENT] by recency, but a
 *     permanent entry ([PairedChild.isPermanent] — i.e. a named one) is exempt: naming a
 *     kid means it can't be pushed out by churn from other devices.
 *
 * Kept free of Android imports so it is unit-testable directly (no Robolectric).
 */
object PairedChildRoster {

    /** Recency cap for NON-permanent (un-named, un-pinned) entries. Permanent kids are extra. */
    const val MAX_RECENT = 10

    /**
     * Merge an incoming (typically network-sourced) record into the roster: preserve the
     * user-authored fields from any existing entry with the same deviceId, move the merged
     * entry to the front (most-recent), then [capped]. [nowMs] stamps [PairedChild.createdAtMs]
     * for a brand-new entry.
     */
    fun upserted(existing: List<PairedChild>, incoming: PairedChild, nowMs: Long): List<PairedChild> {
        val prior = existing.firstOrNull { it.deviceId == incoming.deviceId }
        val merged = incoming.copy(
            // Keep an existing user name unless the incoming record explicitly carries one.
            userName = incoming.userName?.takeIf { it.isNotBlank() } ?: prior?.userName,
            // First-seen time: keep the original, else the incoming value, else stamp now.
            createdAtMs = (prior?.createdAtMs?.takeIf { it > 0L }
                ?: incoming.createdAtMs.takeIf { it > 0L }
                ?: nowMs)
        )
        val rest = existing.filter { it.deviceId != incoming.deviceId }
        return capped(listOf(merged) + rest)
    }

    /**
     * Set or clear the user-assigned name for [deviceId]. Naming a child makes it permanent
     * ([PairedChild.isPermanent]) so it survives the recency cap and reconnects keep its name.
     * Clearing the name (null/blank) demotes it back to an ordinary, evictable recent entry.
     * No-op if [deviceId] is absent.
     */
    fun withUserName(list: List<PairedChild>, deviceId: String, userName: String?): List<PairedChild> {
        val clean = userName?.trim()?.takeIf { it.isNotBlank() }
        return list.map { if (it.deviceId == deviceId) it.copy(userName = clean) else it }
    }

    /** Drop [deviceId] from the roster entirely. */
    fun removed(list: List<PairedChild>, deviceId: String): List<PairedChild> =
        list.filter { it.deviceId != deviceId }

    /**
     * Cap to [max] entries by position (callers keep most-recent-first), but NEVER drop a
     * permanent entry. If permanent entries alone exceed [max] they are ALL kept (permanence
     * wins over the cap — that is the whole point of naming a kid). Original order preserved.
     */
    fun capped(list: List<PairedChild>, max: Int = MAX_RECENT): List<PairedChild> {
        if (list.size <= max) return list
        val permanentIds = list.filter { it.isPermanent }.map { it.deviceId }.toSet()
        val room = (max - permanentIds.size).coerceAtLeast(0)
        val keepTransientIds = list.asSequence()
            .filter { !it.isPermanent }
            .take(room)
            .map { it.deviceId }
            .toSet()
        return list.filter { it.deviceId in permanentIds || it.deviceId in keepTransientIds }
    }
}
