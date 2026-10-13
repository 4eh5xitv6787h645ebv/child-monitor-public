package de.rochefort.childmonitor

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MdnsTxtRecordTest {

    @Test fun `id and protocol version are always advertised`() {
        val attrs = MdnsTxtRecord.attributes("a1b2c3d4", customName = null)
        assertEquals("a1b2c3d4", attrs["id"])
        assertEquals("1", attrs["v"])
    }

    @Test fun `the default (no custom name) does not broadcast a name attribute`() {
        val attrs = MdnsTxtRecord.attributes("a1b2c3d4", customName = null)
        assertFalse(attrs.containsKey("name"), "the device model must never be broadcast")
    }

    @Test fun `a blank custom name is treated as no name`() {
        assertFalse(MdnsTxtRecord.attributes("id", customName = "").containsKey("name"))
        assertFalse(MdnsTxtRecord.attributes("id", customName = "   ").containsKey("name"))
    }

    @Test fun `an explicit custom name is advertised`() {
        val attrs = MdnsTxtRecord.attributes("id", customName = "Nursery")
        assertEquals("Nursery", attrs["name"])
    }

    @Test fun `a custom name is trimmed`() {
        assertEquals("Nursery", MdnsTxtRecord.attributes("id", customName = "  Nursery  ")["name"])
    }

    @Test fun `only id, v and the optional name are ever present`() {
        val withName = MdnsTxtRecord.attributes("id", customName = "Crib")
        assertEquals(setOf("id", "v", "name"), withName.keys)
        val withoutName = MdnsTxtRecord.attributes("id", customName = null)
        assertEquals(setOf("id", "v"), withoutName.keys)
    }

    @Test fun `TLS audio capability upgrades protocol and advertises its port`() {
        val attrs = MdnsTxtRecord.attributes("id", customName = null, audioTlsPort = 10443)
        assertEquals("2", attrs["v"])
        assertEquals("10443", attrs["ap"])
        assertEquals(setOf("id", "v", "ap"), attrs.keys)
    }

    @Test fun `invalid TLS audio port is not advertised`() {
        val attrs = MdnsTxtRecord.attributes("id", customName = null, audioTlsPort = 70000)
        assertEquals("1", attrs["v"])
        assertFalse(attrs.containsKey("ap"))
    }
}
