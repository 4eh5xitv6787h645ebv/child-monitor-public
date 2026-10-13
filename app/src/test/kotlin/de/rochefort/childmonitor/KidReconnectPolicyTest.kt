package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnect policy for permanent (named) kids: escalate to NSD-by-deviceId after one saved-IP pass,
 * and require an exact deviceId match in discovery (never adopt a different/unidentified child).
 */
class KidReconnectPolicyTest {

    @Test
    fun loops_permanentKidEscalatesAfterOnePass() {
        assertEquals(1, KidReconnectPolicy.loopsBeforeDiscovery(isPermanentKid = true, default = 3))
        // Permanent is always 1 regardless of the caller's default (pins the discard-default contract).
        assertEquals(1, KidReconnectPolicy.loopsBeforeDiscovery(isPermanentKid = true, default = 5))
    }

    @Test
    fun loops_nonPermanentUsesDefault() {
        assertEquals(3, KidReconnectPolicy.loopsBeforeDiscovery(isPermanentKid = false, default = 3))
        assertEquals(5, KidReconnectPolicy.loopsBeforeDiscovery(isPermanentKid = false, default = 5))
    }

    @Test
    fun accepts_noTarget_adoptsAnything() {
        assertTrue(KidReconnectPolicy.acceptsDiscovered(null, "abcd", isPermanentKid = false))
        assertTrue(KidReconnectPolicy.acceptsDiscovered("", null, isPermanentKid = true))
    }

    @Test
    fun accepts_permanentKid_requiresExactMatch() {
        assertTrue(KidReconnectPolicy.acceptsDiscovered("abcd", "abcd", isPermanentKid = true))
        // A null-id advertiser must NOT be adopted for a pinned link.
        assertFalse(KidReconnectPolicy.acceptsDiscovered("abcd", null, isPermanentKid = true))
        assertFalse(KidReconnectPolicy.acceptsDiscovered("abcd", "ffff", isPermanentKid = true))
    }

    @Test
    fun accepts_legacy_lenientOnNullId_strictOnMismatch() {
        assertTrue(KidReconnectPolicy.acceptsDiscovered("abcd", "abcd", isPermanentKid = false))
        // Legacy: an old child with no advertised id is still adopted.
        assertTrue(KidReconnectPolicy.acceptsDiscovered("abcd", null, isPermanentKid = false))
        // But a positively different id is skipped.
        assertFalse(KidReconnectPolicy.acceptsDiscovered("abcd", "ffff", isPermanentKid = false))
    }

    @Test
    fun backgroundDiscovery_waitsForPlainIpAttemptsThenRuns() {
        assertFalse(KidReconnectPolicy.shouldDiscoverInBackground(1, 0L, 10_000L))
        assertTrue(KidReconnectPolicy.shouldDiscoverInBackground(2, 0L, 10_000L))
    }

    @Test
    fun backgroundDiscovery_retriesAfterCooldownInsteadOfOncePerOutage() {
        val last = 10_000L
        val cooldown = KidReconnectPolicy.BACKGROUND_DISCOVERY_COOLDOWN_MS
        assertFalse(KidReconnectPolicy.shouldDiscoverInBackground(8, last, last + cooldown - 1))
        assertTrue(KidReconnectPolicy.shouldDiscoverInBackground(8, last, last + cooldown))
    }

    @Test
    fun backgroundDiscovery_monotonicClockResetDoesNotSuppressRecovery() {
        assertTrue(KidReconnectPolicy.shouldDiscoverInBackground(2, 50_000L, 1_000L))
    }
}
