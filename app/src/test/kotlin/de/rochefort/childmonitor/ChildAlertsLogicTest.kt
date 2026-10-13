package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChildAlertsLogicTest {

    // ---- parseHhmm ----

    @Test fun `parses valid times`() {
        assertEquals(0 to 0, ChildAlertsLogic.parseHhmm("00:00"))
        assertEquals(7 to 30, ChildAlertsLogic.parseHhmm("07:30"))
        assertEquals(23 to 59, ChildAlertsLogic.parseHhmm("23:59"))
        assertEquals(9 to 5, ChildAlertsLogic.parseHhmm(" 9:5 ")) // trimmed, unpadded
    }

    @Test fun `rejects malformed or out-of-range times`() {
        assertNull(ChildAlertsLogic.parseHhmm(null))
        assertNull(ChildAlertsLogic.parseHhmm(""))
        assertNull(ChildAlertsLogic.parseHhmm("24:00"))
        assertNull(ChildAlertsLogic.parseHhmm("12:60"))
        assertNull(ChildAlertsLogic.parseHhmm("-1:00"))
        assertNull(ChildAlertsLogic.parseHhmm("7"))
        assertNull(ChildAlertsLogic.parseHhmm("7:30:00"))
        assertNull(ChildAlertsLogic.parseHhmm("ab:cd"))
    }

    // ---- formatHhmm ----

    @Test fun `formats zero-padded 24h`() {
        assertEquals("00:00", ChildAlertsLogic.formatHhmm(0, 0))
        assertEquals("07:05", ChildAlertsLogic.formatHhmm(7, 5))
        assertEquals("23:59", ChildAlertsLogic.formatHhmm(23, 59))
    }

    @Test fun `format clamps out-of-range input`() {
        assertEquals("23:59", ChildAlertsLogic.formatHhmm(99, 99))
        assertEquals("00:00", ChildAlertsLogic.formatHhmm(-5, -5))
    }

    @Test fun `parse and format round-trip`() {
        val (h, m) = ChildAlertsLogic.parseHhmm("21:45")!!
        assertEquals("21:45", ChildAlertsLogic.formatHhmm(h, m))
    }

    // ---- isLikelyWebhookUrl ----

    @Test fun `accepts well-formed http and https urls`() {
        assertTrue(ChildAlertsLogic.isLikelyWebhookUrl("http://192.168.1.10:8123/api/webhook/x"))
        assertTrue(ChildAlertsLogic.isLikelyWebhookUrl("https://example.com/hook"))
        assertTrue(ChildAlertsLogic.isLikelyWebhookUrl("  https://host.local/path  ")) // trimmed
    }

    @Test fun `rejects junk, wrong-scheme, and hostless urls`() {
        assertFalse(ChildAlertsLogic.isLikelyWebhookUrl(null))
        assertFalse(ChildAlertsLogic.isLikelyWebhookUrl(""))
        assertFalse(ChildAlertsLogic.isLikelyWebhookUrl("   "))
        assertFalse(ChildAlertsLogic.isLikelyWebhookUrl("ftp://example.com"))
        assertFalse(ChildAlertsLogic.isLikelyWebhookUrl("example.com/hook"))
        assertFalse(ChildAlertsLogic.isLikelyWebhookUrl("http://"))
        assertFalse(ChildAlertsLogic.isLikelyWebhookUrl("just some text"))
    }
}
