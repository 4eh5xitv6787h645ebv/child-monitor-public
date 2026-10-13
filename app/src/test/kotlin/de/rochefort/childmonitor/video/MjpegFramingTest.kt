package de.rochefort.childmonitor.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #306: MJPEG multipart framing. */
class MjpegFramingTest {

    @Test fun contentType_carriesBoundary() {
        assertEquals("multipart/x-mixed-replace; boundary=childmonitorframe", MjpegFraming.CONTENT_TYPE)
    }

    @Test fun partHeader_isExactMultipartPreamble() {
        val expected = "--childmonitorframe\r\nContent-Type: image/jpeg\r\nContent-Length: 1234\r\n\r\n"
        assertEquals(expected, String(MjpegFraming.partHeader(1234), Charsets.US_ASCII))
    }

    @Test fun partTrailer_isCrlf() {
        assertArrayEquals("\r\n".toByteArray(Charsets.US_ASCII), MjpegFraming.partTrailer)
    }

    @Test fun frame_concatenatesHeaderJpegTrailer() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0xFF.toByte(), 0xD9.toByte())
        val frame = MjpegFraming.frame(jpeg)
        val header = MjpegFraming.partHeader(jpeg.size)

        // header prefix
        assertArrayEquals(header, frame.copyOfRange(0, header.size))
        // jpeg payload in the middle
        assertArrayEquals(jpeg, frame.copyOfRange(header.size, header.size + jpeg.size))
        // trailing CRLF
        assertArrayEquals(MjpegFraming.partTrailer, frame.copyOfRange(frame.size - 2, frame.size))
        assertEquals(header.size + jpeg.size + 2, frame.size)
    }

    @Test fun partHeader_lengthMatchesValue() {
        assertTrue(String(MjpegFraming.partHeader(0), Charsets.US_ASCII).contains("Content-Length: 0"))
        assertTrue(String(MjpegFraming.partHeader(99999), Charsets.US_ASCII).contains("Content-Length: 99999"))
    }
}
