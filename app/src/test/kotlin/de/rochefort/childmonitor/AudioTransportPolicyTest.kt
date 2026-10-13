package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioTransportPolicyTest {
    @Test fun `advertised audio TLS with a pin uses pinned TLS`() {
        assertEquals(
            AudioTransportPolicy.Decision.PINNED_TLS,
            AudioTransportPolicy.decide(hasPin = true, audioTlsPort = 10443, hasEverAudioTls = true)
        )
    }

    @Test fun `legacy pairing remains cleartext compatible during rollout`() {
        assertEquals(
            AudioTransportPolicy.Decision.LEGACY_CLEARTEXT,
            AudioTransportPolicy.decide(hasPin = true, audioTlsPort = null, hasEverAudioTls = false)
        )
    }

    @Test fun `previously secure audio pairing refuses missing pin or port`() {
        assertEquals(
            AudioTransportPolicy.Decision.REFUSE_DOWNGRADE,
            AudioTransportPolicy.decide(hasPin = false, audioTlsPort = 10443, hasEverAudioTls = true)
        )
        assertEquals(
            AudioTransportPolicy.Decision.REFUSE_DOWNGRADE,
            AudioTransportPolicy.decide(hasPin = true, audioTlsPort = null, hasEverAudioTls = true)
        )
    }

    @Test fun `child rejects strict tokens on raw port and accepts them on TLS`() {
        assertFalse(AudioTransportPolicy.acceptsSession(tlsAudioRequired = true, secureTransport = false))
        assertTrue(AudioTransportPolicy.acceptsSession(tlsAudioRequired = true, secureTransport = true))
        assertTrue(AudioTransportPolicy.acceptsSession(tlsAudioRequired = false, secureTransport = false))
    }
}
