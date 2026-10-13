package de.rochefort.childmonitor

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WebSocketFrameLimitInputStreamTest {

    /**
     * Build the on-the-wire bytes of a single RFC 6455 frame header (everything
     * up to but not including the payload) declaring [declaredLen] bytes of
     * payload. Mirrors how a real client encodes the length (7-bit, 16-bit, or
     * 64-bit) so the guard sees exactly what NanoWSD would.
     */
    private fun header(declaredLen: Long, masked: Boolean = true): ByteArray {
        val out = ArrayList<Byte>()
        out.add(0x82.toByte()) // FIN + binary opcode
        val maskBit = if (masked) 0x80 else 0x00
        when {
            declaredLen < 126 -> out.add((maskBit or declaredLen.toInt()).toByte())
            declaredLen <= 0xFFFF -> {
                out.add((maskBit or 126).toByte())
                out.add(((declaredLen shr 8) and 0xFF).toByte())
                out.add((declaredLen and 0xFF).toByte())
            }
            else -> {
                out.add((maskBit or 127).toByte())
                for (shift in 7 downTo 0) out.add(((declaredLen shr (shift * 8)) and 0xFF).toByte())
            }
        }
        if (masked) repeat(4) { out.add(0x00) }
        return out.toByteArray()
    }

    private fun frame(payload: ByteArray, masked: Boolean = true): ByteArray =
        header(payload.size.toLong(), masked) + payload

    private fun drain(bytes: ByteArray, cap: Long) {
        val s = WebSocketFrameLimitInputStream(ByteArrayInputStream(bytes), cap)
        while (s.read() != -1) { /* consume */ }
    }

    @Test fun `small masked frame passes through byte-for-byte`() {
        val wire = frame(ByteArray(10) { it.toByte() })
        val s = WebSocketFrameLimitInputStream(ByteArrayInputStream(wire), 64L * 1024)
        assertContentEquals(wire, s.readBytes())
    }

    @Test fun `oversized 64-bit-length frame is rejected before the payload`() {
        // 1 MiB declared via the 127 (8-byte length) encoding, cap 64 KiB.
        val wire = header(1L shl 20)
        assertFailsWith<IOException> { drain(wire, 64L * 1024) }
    }

    @Test fun `gigabyte frame (the OOM vector) is rejected`() {
        val wire = header(2L * 1024 * 1024 * 1024) // ~2 GiB, the NanoWSD ceiling
        assertFailsWith<IOException> { drain(wire, 64L * 1024) }
    }

    @Test fun `length exactly at the cap passes, one over is rejected`() {
        // Values >125 force the 16-bit extended-length encoding.
        drain(header(1000L), cap = 1000L)                 // == cap: allowed
        assertFailsWith<IOException> { drain(header(1001L), cap = 1000L) }
    }

    @Test fun `unmasked oversized frame is also rejected`() {
        val wire = header(1L shl 20, masked = false)
        assertFailsWith<IOException> { drain(wire, 64L * 1024) }
    }

    @Test fun `multiple small frames keep independent state`() {
        val wire = frame(ByteArray(5) { 1 }) + frame(ByteArray(7) { 2 }) + frame(ByteArray(0))
        val s = WebSocketFrameLimitInputStream(ByteArrayInputStream(wire), 64L * 1024)
        assertContentEquals(wire, s.readBytes())
    }

    @Test fun `bulk read path still enforces the cap`() {
        val s = WebSocketFrameLimitInputStream(ByteArrayInputStream(header(1L shl 20)), 64L * 1024)
        assertFailsWith<IOException> {
            val buf = ByteArray(64)
            while (s.read(buf, 0, buf.size) != -1) { /* consume */ }
        }
    }

    @Test fun `empty source reports EOF without error`() {
        val s = WebSocketFrameLimitInputStream(ByteArrayInputStream(ByteArray(0)), 64L * 1024)
        assertEquals(-1, s.read())
    }

    @Test fun `skip advances framing state so a following frame still parses`() {
        // Skip through frame 1, then read frame 2 — state must have advanced
        // exactly through frame 1 (skip routed via read), leaving frame 2 intact.
        val f1 = frame(ByteArray(5) { 1 })
        val f2 = frame(ByteArray(7) { 2 })
        val s = WebSocketFrameLimitInputStream(ByteArrayInputStream(f1 + f2), 64L * 1024)
        assertEquals(f1.size.toLong(), s.skip(f1.size.toLong()))
        assertContentEquals(f2, s.readBytes())
    }

    @Test fun `skip still enforces the cap on an oversized following frame`() {
        val small = frame(ByteArray(3) { 9 })
        val s = WebSocketFrameLimitInputStream(ByteArrayInputStream(small + header(1L shl 20)), 64L * 1024)
        s.skip(small.size.toLong())
        assertFailsWith<IOException> { while (s.read() != -1) { /* consume */ } }
    }
}
