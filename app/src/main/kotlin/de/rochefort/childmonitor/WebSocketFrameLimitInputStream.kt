package de.rochefort.childmonitor

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Bounds the declared payload length of inbound WebSocket frames (#204).
 *
 * NanoWSD 2.3.1 reads a frame's declared length off the wire and immediately
 * does `new byte[length]` — before our `onMessage` handler ever runs — with the
 * only built-in ceiling being `Integer.MAX_VALUE` (~2 GB). A single crafted
 * frame can therefore force a multi-gigabyte allocation and an
 * `OutOfMemoryError`, killing the foreground monitoring service. The relevant
 * reader (`WebSocketFrame.readPayload`) and the frame loop are private/final in
 * NanoWSD, so the only seam we control is the [InputStream] that the frame
 * reader consumes. This filter mirrors RFC 6455 framing just far enough to learn
 * each frame's declared length and throws an [IOException] the instant a header
 * declares more than [maxFrameBytes] — before NanoWSD allocates. NanoWSD catches
 * the IOException and closes that one connection; the service stays up (a clean
 * per-connection close instead of a process-wide OOM).
 *
 * Pure `java.io`, so it is unit-testable on the JVM without Android or NanoWSD.
 * Bytes pass through untouched; only the framing state is tracked.
 */
class WebSocketFrameLimitInputStream(
    source: InputStream,
    private val maxFrameBytes: Long
) : FilterInputStream(source) {

    private enum class Phase { OPCODE, LEN7, EXT_LEN, MASK, PAYLOAD }

    private var phase = Phase.OPCODE
    private var masked = false
    private var extLenRemaining = 0
    private var extLenValue = 0L
    private var maskRemaining = 0
    private var payloadRemaining = 0L

    init {
        require(maxFrameBytes >= 0) { "maxFrameBytes must be non-negative" }
    }

    override fun read(): Int {
        val b = `in`.read()
        if (b >= 0) advance(b)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = `in`.read(b, off, len)
        for (i in 0 until n) advance(b[off + i].toInt() and 0xFF)
        return n
    }

    /**
     * Route skips through [read] so the framing state never desyncs. NanoWSD's
     * WebSocket path never skips on this stream, so this is purely defensive
     * against future drift; it is never on a hot path.
     */
    override fun skip(n: Long): Long {
        var skipped = 0L
        while (skipped < n && read() >= 0) skipped++
        return skipped
    }

    /**
     * Feed one consumed byte through the framing state machine. Throws the
     * moment a frame header finishes declaring a payload longer than the cap,
     * which (because NanoWSD reads header/length bytes one at a time) happens
     * strictly before NanoWSD allocates the payload buffer.
     */
    private fun advance(byte: Int) {
        when (phase) {
            Phase.OPCODE -> phase = Phase.LEN7
            Phase.LEN7 -> {
                masked = (byte and 0x80) != 0
                when (val len7 = byte and 0x7F) {
                    126 -> { extLenRemaining = 2; extLenValue = 0; phase = Phase.EXT_LEN }
                    127 -> { extLenRemaining = 8; extLenValue = 0; phase = Phase.EXT_LEN }
                    else -> beginAfterLength(len7.toLong())
                }
            }
            Phase.EXT_LEN -> {
                extLenValue = (extLenValue shl 8) or byte.toLong()
                if (--extLenRemaining == 0) beginAfterLength(extLenValue)
            }
            Phase.MASK -> if (--maskRemaining == 0) beginPayload()
            Phase.PAYLOAD -> if (--payloadRemaining <= 0L) phase = Phase.OPCODE
        }
    }

    private fun beginAfterLength(length: Long) {
        if (length < 0L || length > maxFrameBytes) {
            throw IOException("WebSocket frame length $length exceeds cap $maxFrameBytes bytes")
        }
        payloadRemaining = length
        if (masked) {
            maskRemaining = 4
            phase = Phase.MASK
        } else {
            beginPayload()
        }
    }

    private fun beginPayload() {
        phase = if (payloadRemaining > 0L) Phase.PAYLOAD else Phase.OPCODE
    }
}
