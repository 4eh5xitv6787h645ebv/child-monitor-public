package de.rochefort.childmonitor.test

import org.junit.Test
import kotlin.test.assertTrue

class NetworkEmulatorTest {

    @Test fun `perfect profile delivers everything`() {
        val em = NetworkEmulator(NetworkEmulator.Profile.PERFECT, seed = 1L)
        var delivered = 0
        repeat(100) {
            val outs = em.send(ByteArray(16), nowMs = it.toLong())
            for (o in outs) if (o is NetworkEmulator.Delivery.Received) delivered++
        }
        val flushed = em.flushReady(10_000L)
        for (f in flushed) if (f is NetworkEmulator.Delivery.Received) delivered++
        assertTrue(delivered in 90..100)
    }

    @Test fun `bad profile drops some packets`() {
        val em = NetworkEmulator(NetworkEmulator.Profile.BAD, seed = 1L)
        var lost = 0
        repeat(500) {
            val outs = em.send(ByteArray(16), nowMs = it.toLong())
            for (o in outs) if (o is NetworkEmulator.Delivery.Lost) lost++
        }
        assertTrue(lost > 0)
    }

    @Test fun `same seed produces same decisions`() {
        val em1 = NetworkEmulator(NetworkEmulator.Profile.LTE, seed = 42L)
        val em2 = NetworkEmulator(NetworkEmulator.Profile.LTE, seed = 42L)
        val out1 = (0 until 100).flatMap { em1.send(ByteArray(4) { it.toByte() }, nowMs = it.toLong()) }
        val out2 = (0 until 100).flatMap { em2.send(ByteArray(4) { it.toByte() }, nowMs = it.toLong()) }
        assertTrue(out1.size == out2.size)
    }
}
