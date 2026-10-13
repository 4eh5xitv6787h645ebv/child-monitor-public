package de.rochefort.childmonitor.cast

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DlnaProtocolTest {
    @Test fun `SSDP parser accepts only bounded successful response headers`() {
        val response = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.4/device.xml\r\nST: x\r\n\r\n"
        assertTrue(DlnaDiscoveryClient.parseHeader(response, "location") == "http://192.168.1.4/device.xml")
        assertTrue(DlnaDiscoveryClient.parseHeader("NOTIFY * HTTP/1.1\r\nLOCATION: x\r\n", "location") == null)
        assertTrue(DlnaDiscoveryClient.parseHeader("HTTP/1.1 200 OK\r\nLOCATION: x\r\ninjected\r\n", "location") == "x")
    }

    @Test fun `DLNA metadata and SOAP escape capability URL`() {
        val url = "http://192.168.1.2:11000/cast/snapshot?cast=abc&v=1"
        val metadata = DlnaController.didlMetadata(url, DlnaController.MIME_JPEG, "Nursery & cot")
        val envelope = DlnaController.soapEnvelope(
            "SetAVTransportURI",
            "<CurrentURI>${DlnaController.xmlEscape(url)}</CurrentURI>",
        )
        assertTrue(metadata.contains("cast=abc&amp;v=1"))
        assertTrue(envelope.contains("cast=abc&amp;v=1"))
        assertFalse(metadata.contains("cast=abc&v=1"))
        assertTrue(metadata.contains("Nursery &amp; cot"))
    }

    @Test fun `media URL accepts private HTTP only`() {
        assertTrue(DlnaController.isSafeMediaUrl("http://192.168.1.2:11000/cast/snapshot?cast=abc"))
        assertFalse(DlnaController.isSafeMediaUrl("https://192.168.1.2/cast/snapshot?cast=abc"))
        assertFalse(DlnaController.isSafeMediaUrl("http://8.8.8.8/cast/snapshot?cast=abc"))
        assertFalse(DlnaController.isSafeMediaUrl("file:///etc/passwd"))
    }
}
