package de.rochefort.childmonitor.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProtocolVersionTest {

    @Test fun `parse accepts three-part versions`() {
        val v = ProtocolVersion.parse("2.5.7")!!
        assertEquals(2, v.major)
        assertEquals(5, v.minor)
        assertEquals(7, v.patch)
    }

    @Test fun `parse accepts two-part versions`() {
        val v = ProtocolVersion.parse("1.3")!!
        assertEquals(0, v.patch)
    }

    @Test fun `same major is compatible`() {
        assertTrue(ProtocolVersion(1, 2, 3).isCompatibleWith(ProtocolVersion(1, 0, 0)))
    }

    @Test fun `different major is not compatible`() {
        assertFalse(ProtocolVersion(2, 0, 0).isCompatibleWith(ProtocolVersion(1, 9, 9)))
    }

    @Test fun `handshake intersect returns common capabilities`() {
        val peer = JSONObject()
            .put("version", "1.2.0")
            .put("capabilities", JSONArray().put(Capabilities.AUDIO_MULAW).put("unknown.extension"))
        val common = CapabilityHandshake.intersect(peer)
        assertTrue(Capabilities.AUDIO_MULAW in common)
        assertFalse("unknown.extension" in common)
    }

    @Test fun `handshake offer exposes current capabilities`() {
        val offer = CapabilityHandshake.handshakeOffer()
        assertEquals(ProtocolVersion.CURRENT.toString(), offer.getString("version"))
        val arr = offer.getJSONArray("capabilities")
        var foundMulaw = false
        for (i in 0 until arr.length()) {
            if (arr.getString(i) == Capabilities.AUDIO_MULAW) { foundMulaw = true; break }
        }
        assertTrue(foundMulaw)
    }
}
