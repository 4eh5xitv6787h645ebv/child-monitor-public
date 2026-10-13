package de.rochefort.childmonitor.security

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DurablePairingPolicyTest {
    @Test fun `legacy finite and expired lifetimes migrate to permanent`() {
        assertEquals(Long.MAX_VALUE, DurablePairingPolicy.normalizedExpiry(1_000L))
        assertEquals(Long.MAX_VALUE, DurablePairingPolicy.normalizedExpiry(0L))
        assertEquals(Long.MAX_VALUE, DurablePairingPolicy.normalizedExpiry(Long.MAX_VALUE))
    }

    @Test fun `secure QR bootstraps refresh immediately`() {
        assertTrue(DurablePairingPolicy.shouldBootstrapRefresh("token", "AA:BB", 11443))
        assertFalse(DurablePairingPolicy.shouldBootstrapRefresh(null, "AA:BB", 11443))
        assertFalse(DurablePairingPolicy.shouldBootstrapRefresh("token", null, 11443))
        assertFalse(DurablePairingPolicy.shouldBootstrapRefresh("token", "AA:BB", null))
        assertFalse(DurablePairingPolicy.shouldBootstrapRefresh("token", "AA:BB", 70000))
    }
}
