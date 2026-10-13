package de.rochefort.childmonitor.audio

import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JitterBufferTest {

    @Test fun `waits until target depth before delivering`() {
        val jb = JitterBuffer(initialTargetMs = 40, minTargetMs = 40, maxTargetMs = 320, frameDurationMs = 20)
        jb.enqueue(JitterBuffer.Packet(1, 0, ShortArray(160)))
        assertNull(jb.pop())
        jb.enqueue(JitterBuffer.Packet(2, 0, ShortArray(160)))
        assertNotNull(jb.pop())
    }

    @Test fun `delivers in sequence order`() {
        val jb = JitterBuffer(initialTargetMs = 20, minTargetMs = 20, maxTargetMs = 320, frameDurationMs = 20)
        jb.enqueue(JitterBuffer.Packet(2, 0, ShortArray(160) { 2 }))
        jb.enqueue(JitterBuffer.Packet(1, 0, ShortArray(160) { 1 }))
        jb.enqueue(JitterBuffer.Packet(3, 0, ShortArray(160) { 3 }))
        val first = jb.pop()!!
        assertTrue(first[0].toInt() == 1)
    }

    @Test fun `backlog is trimmed`() {
        val jb = JitterBuffer(initialTargetMs = 20, minTargetMs = 20, maxTargetMs = 80, frameDurationMs = 20)
        repeat(500) { jb.enqueue(JitterBuffer.Packet(it.toLong(), 0, ShortArray(160))) }
        val metrics = jb.metrics()
        assertTrue(metrics.dropped > 0)
    }
}
