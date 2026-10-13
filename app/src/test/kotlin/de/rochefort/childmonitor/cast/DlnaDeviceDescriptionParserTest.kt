package de.rochefort.childmonitor.cast

import java.net.InetAddress
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DlnaDeviceDescriptionParserTest {
    private val responder = InetAddress.getByName("192.168.1.40")

    @Test fun `extracts renderer AVTransport endpoint and resolves relative control URL`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <friendlyName>Nursery TV</friendlyName>
                <serviceList><service>
                  <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                  <controlURL>/upnp/control/avtransport</controlURL>
                </service></serviceList>
              </device>
            </root>
        """.trimIndent().toByteArray()

        val renderer = DlnaDeviceDescriptionParser.parse(
            xml,
            "http://192.168.1.40:1400/device.xml",
            responder,
        )

        assertEquals("Nursery TV", renderer?.friendlyName)
        assertEquals("http://192.168.1.40:1400/upnp/control/avtransport", renderer?.controlUrl)
        assertEquals("192.168.1.40", renderer?.address)
    }

    @Test fun `rejects description redirects and control URLs to another LAN host`() {
        val xml = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Hostile renderer</friendlyName><serviceList><service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>http://192.168.1.99/control</controlURL>
              </service></serviceList>
            </device></root>
        """.trimIndent().toByteArray()

        assertNull(DlnaDeviceDescriptionParser.parse(xml, "http://192.168.1.40/device.xml", responder))
        assertNull(DlnaDeviceDescriptionParser.parse(xml, "http://8.8.8.8/device.xml", responder))
    }

    @Test fun `rejects doctype and external entity input`() {
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>&xxe;</friendlyName></device></root>
        """.trimIndent().toByteArray()
        assertNull(DlnaDeviceDescriptionParser.parse(xml, "http://192.168.1.40/device.xml", responder))
    }
}
