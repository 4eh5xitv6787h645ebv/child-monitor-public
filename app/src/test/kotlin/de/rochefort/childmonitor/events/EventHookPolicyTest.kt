package de.rochefort.childmonitor.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** #308: webhook URL validation + JSON payload building. #332: LAN-only address classification. */
class EventHookPolicyTest {

    private fun ip(s: String) = InetAddress.getByName(s) // literal IPs → no DNS

    @Test fun privateAddresses_areLan() {
        listOf("127.0.0.1", "10.0.0.1", "172.16.5.4", "192.168.1.20", "169.254.3.3",
            "::1", "fe80::1", "fc00::1", "fd12:3456::1", "0.0.0.0").forEach {
            assertTrue("$it should be private", EventHookPolicy.isPrivateAddress(ip(it)))
        }
    }

    @Test fun publicAddresses_areNotLan() {
        listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "203.0.113.7",
            "2001:4860:4860::8888").forEach {
            assertFalse("$it should be public", EventHookPolicy.isPrivateAddress(ip(it)))
        }
    }

    @Test fun allPrivate_requiresNonEmptyAndAllPrivate() {
        assertTrue(EventHookPolicy.allPrivate(listOf(ip("192.168.1.1"), ip("10.0.0.2"))))
        assertFalse("any public → reject", EventHookPolicy.allPrivate(listOf(ip("192.168.1.1"), ip("8.8.8.8"))))
        assertFalse("empty → reject", EventHookPolicy.allPrivate(emptyList()))
    }

    @Test fun acceptsHttpAndHttpsUrls() {
        assertEquals("http://10.0.2.2:9099/hook", EventHookPolicy.validateWebhookUrl("http://10.0.2.2:9099/hook"))
        assertEquals("https://ha.local/api/webhook/x", EventHookPolicy.validateWebhookUrl(" https://ha.local/api/webhook/x "))
    }

    @Test fun acceptsIpv6AndUppercaseScheme() {
        assertEquals("http://[::1]:8080/x", EventHookPolicy.validateWebhookUrl("http://[::1]:8080/x"))
        assertEquals("HTTP://10.0.0.5/hook", EventHookPolicy.validateWebhookUrl("HTTP://10.0.0.5/hook"))
    }

    @Test fun rejectsNonHttpSchemesAndGarbage() {
        assertNull(EventHookPolicy.validateWebhookUrl("file:///etc/passwd"))
        assertNull(EventHookPolicy.validateWebhookUrl("intent://evil"))
        assertNull(EventHookPolicy.validateWebhookUrl("ftp://host/x"))
        assertNull(EventHookPolicy.validateWebhookUrl("http://"))      // no host
        assertNull(EventHookPolicy.validateWebhookUrl("notaurl"))
        assertNull(EventHookPolicy.validateWebhookUrl(""))
        assertNull(EventHookPolicy.validateWebhookUrl(null))
    }

    @Test fun buildsPayloadWithEventAndTs() {
        val json = EventHookPolicy.buildJsonPayload("test", 1700000000000L, emptyMap())
        assertEquals("""{"event":"test","ts":1700000000000}""", json)
    }

    @Test fun buildsPayloadWithDetails() {
        val json = EventHookPolicy.buildJsonPayload(
            "parent_connected", 42L, linkedMapOf("address" to "192.168.1.5", "parents" to "1")
        )
        assertEquals("""{"event":"parent_connected","ts":42,"address":"192.168.1.5","parents":"1"}""", json)
    }

    @Test fun escapesJsonSpecialChars() {
        // input value is: a " b \ c <newline> d  → quote/backslash/newline must be escaped.
        val json = EventHookPolicy.buildJsonPayload("evt", 1L, mapOf("note" to "a\"b\\c\nd"))
        assertEquals("""{"event":"evt","ts":1,"note":"a\"b\\c\nd"}""", json)
    }
}
