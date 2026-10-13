package de.rochefort.childmonitor.observability

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PacketRingBufferTest {

    @Test fun `records entries and bounds by window`() {
        var t = 1_000L
        val ring = PacketRingBuffer(windowMs = 50L, nowMs = { t })
        ring.record("out", "1.2.3.4", 320)
        ring.record("out", "1.2.3.4", 320)
        assertEquals(2, ring.size())
        t += 80
        assertEquals(0, ring.dump().length())
    }

    @Test fun `dump returns JSON with fields`() {
        val ring = PacketRingBuffer(windowMs = 60_000L)
        ring.record("in", "2.3.4.5", 64, note = "handshake")
        val json = ring.dump()
        val entry = json.getJSONObject(0)
        assertEquals("in", entry.getString("dir"))
        assertEquals("2.3.4.5", entry.getString("endpoint"))
        assertEquals(64, entry.getInt("bytes"))
        assertTrue(entry.getString("note") == "handshake")
    }
}
