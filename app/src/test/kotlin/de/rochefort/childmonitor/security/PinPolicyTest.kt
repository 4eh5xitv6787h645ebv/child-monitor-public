package de.rochefort.childmonitor.security

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PinPolicyTest {

    private val now = 1_000_000_000_000L

    // ---- isValidPin ----

    @Test fun `accepts 4 to 8 digit pins`() {
        assertTrue(PinPolicy.isValidPin("0000"))
        assertTrue(PinPolicy.isValidPin("1234"))
        assertTrue(PinPolicy.isValidPin("12345678"))
    }

    @Test fun `rejects wrong length`() {
        assertFalse(PinPolicy.isValidPin(""))
        assertFalse(PinPolicy.isValidPin("123"))
        assertFalse(PinPolicy.isValidPin("123456789"))
    }

    @Test fun `rejects non-digits`() {
        assertFalse(PinPolicy.isValidPin("12a4"))
        assertFalse(PinPolicy.isValidPin("12 4"))
        assertFalse(PinPolicy.isValidPin("１２３４")) // full-width digits are not 0-9
    }

    // ---- backoffMs (escalating, capped, never permanent) ----

    @Test fun `first few failures have no backoff`() {
        assertEquals(0L, PinPolicy.backoffMs(0))
        assertEquals(0L, PinPolicy.backoffMs(1))
        assertEquals(0L, PinPolicy.backoffMs(2))
        assertEquals(0L, PinPolicy.backoffMs(3))
    }

    @Test fun `backoff escalates then caps`() {
        assertEquals(5_000L, PinPolicy.backoffMs(4))
        assertEquals(15_000L, PinPolicy.backoffMs(5))
        assertEquals(30_000L, PinPolicy.backoffMs(6))
        assertEquals(PinPolicy.MAX_BACKOFF_MS, PinPolicy.backoffMs(7))
    }

    @Test fun `backoff never exceeds the cap, even for huge attempt counts`() {
        assertEquals(PinPolicy.MAX_BACKOFF_MS, PinPolicy.backoffMs(100))
        assertEquals(PinPolicy.MAX_BACKOFF_MS, PinPolicy.backoffMs(Int.MAX_VALUE))
    }

    // ---- remainingBackoffMs / canAttempt ----

    @Test fun `no backoff means attempt allowed immediately`() {
        assertEquals(0L, PinPolicy.remainingBackoffMs(2, now, now))
        assertTrue(PinPolicy.canAttempt(2, now, now))
    }

    @Test fun `within backoff window blocks, with correct remaining`() {
        // 4 failures -> 5s backoff; 2s elapsed -> 3s remaining.
        assertEquals(3_000L, PinPolicy.remainingBackoffMs(4, now, now + 2_000L))
        assertFalse(PinPolicy.canAttempt(4, now, now + 2_000L))
    }

    @Test fun `backoff elapsed allows attempt`() {
        assertEquals(0L, PinPolicy.remainingBackoffMs(4, now, now + 5_000L)) // exactly elapsed
        assertTrue(PinPolicy.canAttempt(4, now, now + 6_000L))
    }

    @Test fun `clock moving backwards is treated as full bounded delay, never permanent`() {
        val r = PinPolicy.remainingBackoffMs(7, now, now - 10_000L)
        assertEquals(PinPolicy.MAX_BACKOFF_MS, r)
        assertTrue(r <= PinPolicy.MAX_BACKOFF_MS) // bounded
    }
}
