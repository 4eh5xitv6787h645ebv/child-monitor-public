package de.rochefort.childmonitor.audio

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Test

class MaskedWebSocketWriterTest {
    @Test fun maskedFramesRoundTripAcrossBothExtendedLengthBoundaries() {
        for (length in listOf(0, 1, 125, 126, 800, 65535, 65536)) {
            val data = ByteArray(length + 3) { (it % 251).toByte() }
            val output = ByteArrayOutputStream()
            MaskedWebSocketWriter.write(output, data, length)
            val frame = output.toByteArray()
            assertEquals(0x82, frame[0].toInt() and 255)
            assertTrue(frame[1].toInt() and 128 != 0)
            val marker = frame[1].toInt() and 127
            val prefix = when (marker) { 126 -> 4; 127 -> 10; else -> 2 }
            var decodedLength = if (prefix == 2) marker.toLong() else 0L
            if (prefix > 2) for (i in 2 until prefix) decodedLength =
                (decodedLength shl 8) or (frame[i].toLong() and 255)
            assertEquals(length.toLong(), decodedLength)
            val decoded = ByteArray(length) { i ->
                (frame[prefix + 4 + i].toInt() xor frame[prefix + i % 4].toInt()).toByte()
            }
            assertArrayEquals(data.copyOf(length), decoded)
        }
    }

    @Test fun brokenConnectionStopsWriterInsteadOfHidingFailure() {
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("disconnected") }
        }
        assertThrows(IOException::class.java) { MaskedWebSocketWriter.write(output, byteArrayOf(1), 1) }
    }

    @Test fun flushFailureAlsoPropagates() {
        val output = object : ByteArrayOutputStream() {
            override fun flush() { throw IOException("disconnected") }
        }
        assertThrows(IOException::class.java) { MaskedWebSocketWriter.write(output, byteArrayOf(1), 1) }
    }
}
