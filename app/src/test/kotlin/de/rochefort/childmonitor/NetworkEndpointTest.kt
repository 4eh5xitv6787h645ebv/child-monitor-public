package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkEndpointTest {

    @Test fun ipv4AndHostnameRoundTrip() {
        for (raw in listOf("192.168.1.42:10000", "child.local:11443")) {
            assertEquals(raw, NetworkEndpoint.parse(raw)?.encoded())
        }
    }

    @Test fun canonicalIpv6RoundTripsWithBrackets() {
        val endpoint = NetworkEndpoint.parse("[fd00::1234]:10000")
        assertEquals("fd00::1234", endpoint?.host)
        assertEquals(10000, endpoint?.port)
        assertEquals("[fd00::1234]:10000", endpoint?.encoded())
    }

    @Test fun legacyUnbracketedIpv6MigratesToCanonicalForm() {
        val endpoint = NetworkEndpoint.parse("fd00::1234:10000")
        assertEquals("fd00::1234", endpoint?.host)
        assertEquals(10000, endpoint?.port)
        assertEquals("[fd00::1234]:10000", endpoint?.encoded())
    }

    @Test fun scopedIpv6IsPreservedAndBracketed() {
        val endpoint = NetworkEndpoint.parse("fe80::1%wlan0:10000")
        assertEquals("fe80::1%wlan0", endpoint?.host)
        assertEquals("[fe80::1%wlan0]:10000", endpoint?.encoded())
    }

    @Test fun defaultPortSupportsOldHostOnlyEntries() {
        assertEquals("192.168.1.42:10000", NetworkEndpoint.parse("192.168.1.42", 10000)?.encoded())
    }

    @Test fun rejectsMalformedUnsafeAndOutOfRangeEndpoints() {
        for (raw in listOf("", "host:", "host:not-a-port", "host:0", "host:65536", "[fd00::1", "host evil:10000")) {
            assertNull(raw, NetworkEndpoint.parse(raw))
        }
    }
}
