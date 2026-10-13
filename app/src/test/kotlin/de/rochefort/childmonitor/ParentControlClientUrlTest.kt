package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParentControlClientUrlTest {

    private val addr = "192.168.1.50"
    private val path = "/api/status"

    @Test fun `a current pin uses HTTPS on the pinned port`() {
        assertEquals(
            "https://$addr:11443$path",
            ParentControlClient.buildUrl(hasPinNow = true, hasEverPinned = true, httpsPort = 11443, address = addr, path = path),
        )
    }

    @Test fun `a non-default pinned port is honoured`() {
        assertEquals(
            "https://$addr:9999$path",
            ParentControlClient.buildUrl(hasPinNow = true, hasEverPinned = true, httpsPort = 9999, address = addr, path = path),
        )
    }

    @Test fun `a device that was ever pinned stays on HTTPS (no silent downgrade)`() {
        // Mirrors TransportPolicy.REFUSE_DOWNGRADE — the URL stays HTTPS so PinnedHttp fails closed.
        val url = ParentControlClient.buildUrl(hasPinNow = false, hasEverPinned = true, httpsPort = 11443, address = addr, path = path)
        assertTrue(url.startsWith("https://"), "ever-pinned must not downgrade to cleartext: $url")
    }

    @Test fun `a never-pinned device uses cleartext on the default port`() {
        assertEquals(
            "http://$addr:11000$path",
            ParentControlClient.buildUrl(hasPinNow = false, hasEverPinned = false, httpsPort = 11443, address = addr, path = path),
        )
    }

    @Test fun `ipv6 address is bracketed in both URL modes`() {
        val ipv6 = "fd00::1234"
        assertEquals(
            "https://[fd00::1234]:11443$path",
            ParentControlClient.buildUrl(true, true, 11443, ipv6, path),
        )
        assertEquals(
            "http://[fd00::1234]:11000$path",
            ParentControlClient.buildUrl(false, false, 11443, ipv6, path),
        )
    }
}
