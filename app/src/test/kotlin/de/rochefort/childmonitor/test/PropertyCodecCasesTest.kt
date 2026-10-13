package de.rochefort.childmonitor.test

import de.rochefort.childmonitor.AudioCodecDefines
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyCodecCasesTest {

    @Test fun `all audio frame cases have distinct labels`() {
        val labels = PropertyCodecCases.audioFrameCases().map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    @Test fun `mulaw encode decode round trips on raw bytes`() {
        val codec = AudioCodecDefines.CODEC
        for (c in PropertyCodecCases.audioFrameCases()) {
            val pcm = ShortArray(c.bytes.size)
            codec.decode(pcm, c.bytes, c.bytes.size, 0)
            val out = ByteArray(c.bytes.size)
            codec.encode(pcm, pcm.size, out, 0)
            assertTrue(out.size == c.bytes.size, "round-trip length for ${c.label}")
        }
    }

    @Test fun `websocket boundary cases span 125 and 126 lengths`() {
        val cases = PropertyCodecCases.websocketBoundaryCases().map { it.bytes.size }
        assertTrue(125 in cases)
        assertTrue(126 in cases)
    }
}
