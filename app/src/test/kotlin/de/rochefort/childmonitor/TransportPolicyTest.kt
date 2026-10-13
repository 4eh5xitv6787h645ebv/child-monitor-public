package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportPolicyTest {

    @Test fun `a current pin uses pinned TLS`() {
        assertEquals(
            TransportPolicy.Decision.PINNED_TLS,
            TransportPolicy.decide(hasPinNow = true, hasEverPinned = true)
        )
        // hasEverPinned is implied by hasPinNow, but defend the degenerate input too.
        assertEquals(
            TransportPolicy.Decision.PINNED_TLS,
            TransportPolicy.decide(hasPinNow = true, hasEverPinned = false)
        )
    }

    @Test fun `a device that was ever pinned refuses to downgrade when the pin is gone`() {
        assertEquals(
            TransportPolicy.Decision.REFUSE_DOWNGRADE,
            TransportPolicy.decide(hasPinNow = false, hasEverPinned = true)
        )
    }

    @Test fun `a never-pinned device falls back to cleartext`() {
        assertEquals(
            TransportPolicy.Decision.INSECURE_CLEARTEXT,
            TransportPolicy.decide(hasPinNow = false, hasEverPinned = false)
        )
    }

    @Test fun `requireTls is true unless the device was never pinned`() {
        assertTrue(TransportPolicy.requireTls(hasPinNow = true, hasEverPinned = false))
        assertTrue(TransportPolicy.requireTls(hasPinNow = false, hasEverPinned = true))
        assertFalse(TransportPolicy.requireTls(hasPinNow = false, hasEverPinned = false))
    }

    @Test fun `cleartext is only insecure-warned when never pinned`() {
        assertTrue(TransportPolicy.isInsecureCleartext(hasPinNow = false, hasEverPinned = false))
        assertFalse(TransportPolicy.isInsecureCleartext(hasPinNow = false, hasEverPinned = true))
        assertFalse(TransportPolicy.isInsecureCleartext(hasPinNow = true, hasEverPinned = true))
    }
}
