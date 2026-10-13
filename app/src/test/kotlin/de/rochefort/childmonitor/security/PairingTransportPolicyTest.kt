package de.rochefort.childmonitor.security

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PairingTransportPolicyTest {
    @Test fun `pairing challenge is loopback only`() {
        assertTrue(PairingTransportPolicy.isAllowed(
            PairingTransportPolicy.Endpoint.CHALLENGE, isTlsListener = false, isLoopback = true))
        assertFalse(PairingTransportPolicy.isAllowed(
            PairingTransportPolicy.Endpoint.CHALLENGE, isTlsListener = true, isLoopback = false))
    }

    @Test fun `remote completion and issuance require TLS`() {
        for (endpoint in listOf(
            PairingTransportPolicy.Endpoint.COMPLETE,
            PairingTransportPolicy.Endpoint.ISSUE_TOKEN,
        )) {
            assertFalse(PairingTransportPolicy.isAllowed(endpoint, false, false))
            assertTrue(PairingTransportPolicy.isAllowed(endpoint, true, false))
            assertTrue(PairingTransportPolicy.isAllowed(endpoint, false, true))
        }
    }
}
