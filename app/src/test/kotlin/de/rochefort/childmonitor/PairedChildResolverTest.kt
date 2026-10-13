package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #278: the non-QR reconnect paths (Quick Reconnect, boot auto-start, discovery relaunch)
 * launch ListenActivity without a deviceId. [PairedChildResolver] recovers it from the
 * paired-children store so the bearer token can still be attached to the CMHELLO handshake.
 */
class PairedChildResolverTest {

    private fun child(id: String, ip: String, port: Int = 10000) =
        PairedChild(deviceId = id, name = id, lastKnownIp = ip, lastKnownPort = port, lastSeenMs = 0L)

    @Test
    fun emptyStore_returnsNull() {
        assertNull(PairedChildResolver.resolve(emptyList(), "192.168.0.153"))
    }

    @Test
    fun exactIpMatch_winsEvenWithMultipleChildren() {
        val paired = listOf(
            child("aaaa1111", "192.168.0.10"),
            child("bbbb2222", "192.168.0.153"),
            child("cccc3333", "192.168.0.99")
        )
        assertEquals("bbbb2222", PairedChildResolver.resolve(paired, "192.168.0.153"))
    }

    @Test
    fun singleChild_isAdoptedWhenIpDoesNotMatch() {
        // The "child's IP changed" case for the common single-baby setup: the saved IP is
        // stale but there's only one paired child, so its deviceId is unambiguous.
        val paired = listOf(child("893b97fd", "192.168.0.153"))
        assertEquals("893b97fd", PairedChildResolver.resolve(paired, "192.168.0.200"))
    }

    @Test
    fun singleChild_isAdoptedWhenTargetAddressMissing() {
        val paired = listOf(child("893b97fd", "192.168.0.153"))
        assertEquals("893b97fd", PairedChildResolver.resolve(paired, null))
        assertEquals("893b97fd", PairedChildResolver.resolve(paired, ""))
    }

    @Test
    fun multipleChildren_noIpMatch_returnsNullToAvoidWrongToken() {
        val paired = listOf(
            child("aaaa1111", "192.168.0.10"),
            child("bbbb2222", "192.168.0.153")
        )
        // No IP match + ambiguous → null, so we never attach the wrong child's token.
        assertNull(PairedChildResolver.resolve(paired, "192.168.0.200"))
        assertNull(PairedChildResolver.resolve(paired, null))
    }
}
