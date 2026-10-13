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
package de.rochefort.childmonitor.parent

/**
 * #348 (part 2): pure decisions for the parent's durable-credential renewal — when to bootstrap a
 * refresh credential and when to proactively re-mint it. Time-injected so it's unit-testable; the
 * Android-coupled HTTP/prefs work lives in SessionRenewer.
 *
 * The goal: a parent paired once can reconnect without re-pairing the wall-mounted
 * child. A short-lived bearer is silently renewed from the long-lived refresh credential; the
 * credential itself is permanent on current children (until explicit revoke). The half-TTL path
 * remains for compatibility with older children that still advertise a finite lifetime.
 */
object RefreshRenewalPolicy {

    /**
     * Whether to (re)mint a refresh credential, given the current stored state. True when there is
     * no credential yet ([issuedAtMs] <= 0) or it has passed half its [ttlMs] lifetime. Bootstrapping
     * and re-minting both require a currently-valid bearer (the caller only invokes this on a
     * successful authed request).
     */
    fun shouldMint(nowMs: Long, issuedAtMs: Long, ttlMs: Long): Boolean {
        if (issuedAtMs <= 0L || ttlMs <= 0L) return true
        if (ttlMs == Long.MAX_VALUE) return false // current pair-once credential; explicit revoke only
        val age = nowMs - issuedAtMs
        // Re-mint at the halfway point so a credential never expires mid-use; also covers a
        // backwards clock jump (age < 0 → not yet due, harmless) and a future-dated issue.
        return age >= ttlMs / 2
    }
}
