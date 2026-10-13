package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LogRedactionTest {

    @Test fun `ssid is replaced by a stable non-identifying token`() {
        val a = LogRedaction.redactSsid("Smith Family 5G")
        assertTrue(a.startsWith("wifi#"), "got $a")
        assertNotEquals("Smith Family 5G", a)
        assertTrue(!a.contains("Smith"), "must not leak the name: $a")
        // deterministic so the diagnostic log can still tell networks apart
        assertEquals(a, LogRedaction.redactSsid("Smith Family 5G"))
    }

    @Test fun `different ssids hash differently`() {
        assertNotEquals(LogRedaction.redactSsid("HomeNet"), LogRedaction.redactSsid("CoffeeShop"))
    }

    @Test fun `blank ssid passes through`() {
        assertEquals("", LogRedaction.redactSsid(""))
    }

    @Test fun `ipv4 masks the host octet but keeps the subnet`() {
        assertEquals("192.168.1.x", LogRedaction.redactIp("192.168.1.42"))
        assertEquals("10.0.0.x", LogRedaction.redactIp("10.0.0.255"))
        assertTrue(LogRedaction.redactIp("192.168.1.42").startsWith("192.168.1."))
    }

    @Test fun `ipv4 placeholder zero address is still masked`() {
        assertEquals("0.0.0.x", LogRedaction.redactIp("0.0.0.0"))
    }

    @Test fun `ipv6 is masked and does not reveal the full address`() {
        val full = "fe80::1ff:fe23:4567:890a"
        val red = LogRedaction.redactIp(full)
        assertNotEquals(full, red)
        assertTrue(!red.contains("4567"), "must not leak the interface id: $red")
    }

    @Test fun `non-ip strings pass through unchanged`() {
        assertEquals("Not connected", LogRedaction.redactIp("Not connected"))
        assertEquals("childmonitor.local", LogRedaction.redactIp("childmonitor.local"))
    }

    @Test fun `out-of-range dotted quads are not treated as ipv4`() {
        // 999 is not a valid octet — leave it untouched rather than mangle it.
        assertEquals("999.1.1.1", LogRedaction.redactIp("999.1.1.1"))
    }

    @Test fun `ipv4 with a port masks the host and keeps the port`() {
        // This is the shape the parent code passes (address:port); it must not
        // fail open and leak the full host.
        assertEquals("192.168.1.x:10000", LogRedaction.redactIp("192.168.1.5:10000"))
    }

    @Test fun `bracketed ipv6 with a port is masked`() {
        val red = LogRedaction.redactIp("[2001:db8::1]:10000")
        assertEquals("[2001:x]:10000", red)
    }

    @Test fun `hostname with a port passes through`() {
        assertEquals("childmonitor.local:8080", LogRedaction.redactIp("childmonitor.local:8080"))
    }
}
