package de.rochefort.childmonitor

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #310/#423: PairedChild.fromJson must neutralise an unsafe persisted/restored ip + port. */
class PairedChildFromJsonTest {

    private fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        for ((k, v) in pairs) put(k, v)
    }

    @Test fun keepsValidRecord() {
        val c = PairedChild.fromJson(json("deviceId" to "abc", "ip" to "192.168.0.9", "port" to 11443))
        assertEquals("192.168.0.9", c!!.lastKnownIp)
        assertEquals(11443, c.lastKnownPort)
    }

    @Test fun blanksCrlfIpButKeepsPairing() {
        // A crafted/corrupt ip must not survive (it would reach the raw WS Host: line) — but the
        // pairing is kept (ip blanked → reconnect falls back to NSD discovery by deviceId).
        val c = PairedChild.fromJson(json("deviceId" to "abc", "ip" to "1.2.3.4\r\nX-Evil: v"))
        assertEquals("abc", c!!.deviceId)
        assertEquals("", c.lastKnownIp)
    }

    @Test fun defaultsOutOfRangePort() {
        val c = PairedChild.fromJson(json("deviceId" to "abc", "ip" to "10.0.0.1", "port" to 999999))
        assertEquals(10000, c!!.lastKnownPort)
    }

    @Test fun blankIpStaysBlank() {
        val c = PairedChild.fromJson(json("deviceId" to "abc"))
        assertEquals("", c!!.lastKnownIp)
    }

    @Test fun blankDeviceIdRejected() {
        assertNull(PairedChild.fromJson(json("ip" to "1.2.3.4")))
    }
}
