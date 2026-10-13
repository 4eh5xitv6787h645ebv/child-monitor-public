package de.rochefort.childmonitor.network

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkChangePolicyTest {

    // --- shouldRefreshAdvertisement ---

    @Test fun `first observation (old empty) does not refresh`() {
        assertFalse(
            NetworkChangePolicy.shouldRefreshAdvertisement(
                old = emptySet(),
                new = setOf("192.168.1.50")
            )
        )
    }

    @Test fun `same address does not refresh`() {
        assertFalse(
            NetworkChangePolicy.shouldRefreshAdvertisement(
                old = setOf("192.168.1.50"),
                new = setOf("192.168.1.50")
            )
        )
    }

    @Test fun `changed address refreshes (A to B)`() {
        assertTrue(
            NetworkChangePolicy.shouldRefreshAdvertisement(
                old = setOf("192.168.1.50"),
                new = setOf("192.168.1.77")
            )
        )
    }

    @Test fun `dual-stack gaining an address refreshes (A to A,B)`() {
        assertTrue(
            NetworkChangePolicy.shouldRefreshAdvertisement(
                old = setOf("192.168.1.50"),
                new = setOf("192.168.1.50", "fd00::1234")
            )
        )
    }

    @Test fun `loss (non-empty to empty) is not treated as a change`() {
        assertFalse(
            NetworkChangePolicy.shouldRefreshAdvertisement(
                old = setOf("192.168.1.50"),
                new = emptySet()
            )
        )
    }

    @Test fun `empty to empty does not refresh`() {
        assertFalse(
            NetworkChangePolicy.shouldRefreshAdvertisement(
                old = emptySet(),
                new = emptySet()
            )
        )
    }

    // --- relevant() filtering ---

    @Test fun `relevant drops loopback IPv4`() {
        assertEquals(
            setOf("192.168.1.50"),
            NetworkChangePolicy.relevant(listOf("127.0.0.1", "192.168.1.50"))
        )
    }

    @Test fun `relevant drops loopback IPv6`() {
        assertEquals(
            setOf("fd00::1234"),
            NetworkChangePolicy.relevant(listOf("::1", "fd00::1234"))
        )
    }

    @Test fun `relevant drops link-local fe80 regardless of case`() {
        assertEquals(
            setOf("192.168.1.50"),
            NetworkChangePolicy.relevant(
                listOf("fe80::1", "FE80::abcd", "192.168.1.50")
            )
        )
    }

    @Test fun `relevant drops blank entries`() {
        assertEquals(
            setOf("10.0.0.5"),
            NetworkChangePolicy.relevant(listOf("", "   ", "10.0.0.5"))
        )
    }

    @Test fun `relevant keeps a normal dual-stack pair`() {
        assertEquals(
            setOf("192.168.1.50", "fd00::1234"),
            NetworkChangePolicy.relevant(listOf("192.168.1.50", "fd00::1234"))
        )
    }

    // --- decideLinkChange() ---

    private val A = setOf("192.168.1.50")
    private val B = setOf("192.168.1.77")

    @Test fun `empty new set leaves last addresses untouched and does not fire`() {
        val d = NetworkChangePolicy.decideLinkChange(lastAddresses = A, relevantNew = emptySet())
        assertFalse(d.fire)
        assertEquals(A, d.nextLastAddresses)
    }

    @Test fun `first observation primes without firing`() {
        val d = NetworkChangePolicy.decideLinkChange(lastAddresses = emptySet(), relevantNew = A)
        assertFalse(d.fire)
        assertEquals(A, d.nextLastAddresses)
    }

    @Test fun `unchanged set records and does not fire`() {
        val d = NetworkChangePolicy.decideLinkChange(lastAddresses = A, relevantNew = A)
        assertFalse(d.fire)
        assertEquals(A, d.nextLastAddresses)
    }

    @Test fun `real change fires and advances`() {
        val d = NetworkChangePolicy.decideLinkChange(lastAddresses = A, relevantNew = B)
        assertTrue(d.fire)
        assertEquals(B, d.nextLastAddresses)
    }

    @Test fun `identical callback after a fired change is deduplicated`() {
        val first = NetworkChangePolicy.decideLinkChange(lastAddresses = A, relevantNew = B)
        val second = NetworkChangePolicy.decideLinkChange(lastAddresses = first.nextLastAddresses, relevantNew = B)
        assertTrue(first.fire)
        assertFalse(second.fire)
        assertEquals(B, second.nextLastAddresses)
    }
}
