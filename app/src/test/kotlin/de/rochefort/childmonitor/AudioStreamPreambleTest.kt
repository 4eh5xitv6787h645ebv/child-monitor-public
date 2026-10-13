package de.rochefort.childmonitor

import java.io.ByteArrayInputStream
import java.io.PushbackInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioStreamPreambleTest {
    private fun input(bytes: ByteArray) = PushbackInputStream(ByteArrayInputStream(bytes), 128)

    @Test fun `ordinary audio is returned byte-for-byte`() {
        val audio = byteArrayOf(0x43, 0x01, 0x7f, 0x00)
        val stream = input(audio)
        assertEquals(AudioStreamPreamble.Result.AUDIO, AudioStreamPreamble.probe(stream))
        assertArrayEquals(audio, stream.readBytes())
    }

    @Test fun `unauthorized child response is recognized and consumed`() {
        val stream = input("CMERROR 401 unauthorized\n".toByteArray())
        assertEquals(AudioStreamPreamble.Result.UNAUTHORIZED, AudioStreamPreamble.probe(stream))
        assertEquals(-1, stream.read())
    }

    @Test fun `other child errors are distinguished from audio`() {
        val stream = input("CMERROR 503 unavailable\n".toByteArray())
        assertEquals(AudioStreamPreamble.Result.SERVER_ERROR, AudioStreamPreamble.probe(stream))
    }

    @Test fun `empty and truncated error prefixes are closed streams`() {
        assertEquals(AudioStreamPreamble.Result.CLOSED, AudioStreamPreamble.probe(input(byteArrayOf())))
        assertEquals(AudioStreamPreamble.Result.CLOSED, AudioStreamPreamble.probe(input("CMER".toByteArray())))
    }
}
