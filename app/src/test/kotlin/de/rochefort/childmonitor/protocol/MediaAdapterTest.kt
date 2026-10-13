package de.rochefort.childmonitor.protocol

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaAdapterTest {

    @Test fun `mulaw adapter handles tcp urls`() {
        val a = MulawSocketAdapter()
        assertTrue(a.canHandle("tcp://10.0.0.1:10000"))
        assertTrue(a.canHandle("childmonitor://host"))
    }

    @Test fun `registry resolves adapter for tcp url`() {
        val a = MediaAdapterRegistry.adapterFor("tcp://1.2.3.4:10000")
        assertNotNull(a)
        assertEquals("mulaw-tcp", a.name)
    }

    @Test fun `interop matrix lists all pairs`() {
        val pairs = MediaAdapterRegistry.interopMatrix()
        assertTrue(pairs.any { it == "mulaw-tcp" to "audio.mulaw" })
    }
}
