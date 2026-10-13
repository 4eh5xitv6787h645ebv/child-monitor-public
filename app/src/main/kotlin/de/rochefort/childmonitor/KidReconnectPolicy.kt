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
 * Reconnect policy for the "Kids" feature (#402). A PERMANENT (parent-named) kid is a deliberate,
 * stable binding to one physical child device, so its reconnect behaviour differs from an ad-hoc
 * IP reconnect:
 *
 *  - **Escalate to discovery sooner.** A named kid's IP churns between sessions (new DHCP lease,
 *    Wi-Fi handoff) and NSD-by-deviceId is the reliable way to find it again. After a single pass
 *    over the saved-IP history (the fast path for the common "IP unchanged" case) we go straight to
 *    mDNS discovery instead of sweeping the global IP history ~3 times (~45s wasted).
 *  - **Match strictly.** When adopting a discovered child for a pinned link, require an EXACT
 *    deviceId match — never adopt a different child, nor an unidentified (null-id) advertiser, for a
 *    kid the user explicitly bound to a specific device. The legacy ad-hoc path stays lenient
 *    (skip only on a positive mismatch) for back-compat with old children that don't advertise an id.
 *
 * Pure (no Android deps) so it is unit-testable directly.
 */
object KidReconnectPolicy {

    const val BACKGROUND_DISCOVERY_MIN_ATTEMPTS = 2
    const val BACKGROUND_DISCOVERY_COOLDOWN_MS = 30_000L

    /** Saved-IP sweep passes before escalating to NSD discovery. Permanent kid → 1 (one fast pass,
     *  then discovery); otherwise the caller's [default] (the legacy ad-hoc reconnect bound). */
    fun loopsBeforeDiscovery(isPermanentKid: Boolean, default: Int): Int =
        if (isPermanentKid) 1 else default

    /**
     * Whether a child found via discovery may be adopted as the reconnect target.
     *  - No target deviceId → adopt anything (legacy first-connect / IP-only path).
     *  - Permanent kid → require an EXACT id match (reject a different OR a null-id advertiser).
     *  - Otherwise (legacy) → skip only on a positive mismatch; a null advertised id is accepted.
     *
     * Note: rejecting a null-id advertiser for a permanent kid is deliberate. A legitimate but
     * id-less child (very old build) is still reconnectable via the id-agnostic saved-IP fast path —
     * this gate only governs ADOPTING an unverified discovery result, not the IP sweep.
     */
    fun acceptsDiscovered(targetDeviceId: String?, discoveredDeviceId: String?, isPermanentKid: Boolean): Boolean {
        if (targetDeviceId.isNullOrBlank()) return true
        if (isPermanentKid) return discoveredDeviceId == targetDeviceId
        return discoveredDeviceId == null || discoveredDeviceId == targetDeviceId
    }

    /**
     * Whether the background service should run another deviceId-filtered discovery pass.
     * A one-shot boolean is insufficient: if mDNS misses the child while it is offline, the parent
     * otherwise retries the stale saved IP forever even after the child returns on a new address.
     */
    fun shouldDiscoverInBackground(
        reconnectAttempts: Int,
        lastDiscoveryElapsedMs: Long,
        nowElapsedMs: Long,
        minAttempts: Int = BACKGROUND_DISCOVERY_MIN_ATTEMPTS,
        cooldownMs: Long = BACKGROUND_DISCOVERY_COOLDOWN_MS,
    ): Boolean {
        if (reconnectAttempts < minAttempts) return false
        if (lastDiscoveryElapsedMs <= 0L || nowElapsedMs < lastDiscoveryElapsedMs) return true
        return nowElapsedMs - lastDiscoveryElapsedMs >= cooldownMs
    }
}
