package de.rochefort.childmonitor.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

/** #306: the MJPEG frame stream — multi-call drain, EOF-on-close, and the idle keep-alive/EOF that
 *  stops a disconnected idle viewer from pinning a worker + limiter slot forever. */
class MjpegFrameStreamTest {

    private val jpegA = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

    private fun readN(s: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = s.read(out, read, n - read)
            if (r == -1) return out.copyOf(read)
            read += r
        }
        return out
    }

    @Test fun emitsOnePartPerNewFrame_drainsAcrossCalls() {
        val stream = MjpegFrameStream(frames = { jpegA }, onClose = {}, sleep = {})
        val expected = MjpegFraming.frame(jpegA)
        // read in small chunks to exercise the pos/buffer advancing
        val out = ByteArray(expected.size)
        var read = 0
        while (read < expected.size) {
            val r = stream.read(out, read, minOf(8, expected.size - read))
            assertTrue(r > 0)
            read += r
        }
        assertArrayEquals(expected, out)
    }

    @Test fun idle_reEmitsLastFrameAsKeepAlive() {
        // Provider always returns the SAME array → after the first part it's idle; must re-emit, not hang.
        val stream = MjpegFrameStream(frames = { jpegA }, onClose = {}, pollMs = 10, keepAliveMs = 20, sleep = {})
        val partSize = MjpegFraming.frame(jpegA).size
        val first = readN(stream, partSize)
        val second = readN(stream, partSize) // this would block forever without the keep-alive
        assertArrayEquals(MjpegFraming.frame(jpegA), first)
        assertArrayEquals(MjpegFraming.frame(jpegA), second)
    }

    @Test fun neverAnyFrame_endsStreamCleanly() {
        // Camera off before the first frame → no last frame to keep-alive → clean EOF (consumer reconnects).
        val stream = MjpegFrameStream(frames = { null }, onClose = {}, pollMs = 10, keepAliveMs = 20, sleep = {})
        assertEquals(-1, stream.read())
    }

    @Test fun close_yieldsEofAndReleasesOnce() {
        var releases = 0
        val stream = MjpegFrameStream(frames = { jpegA }, onClose = { releases++ }, sleep = {})
        stream.close()
        assertEquals(-1, stream.read())
        stream.close() // idempotent
        assertEquals("onClose runs exactly once", 1, releases)
    }

    @Test fun cameraOffMidStream_keepAlivesOnLastFrameThenStaysAlive() {
        // First frame arrives, then provider returns null (camera off) → keep-alive re-emits the last frame.
        var calls = 0
        val stream = MjpegFrameStream(
            frames = { if (calls++ == 0) jpegA else null },
            onClose = {}, pollMs = 10, keepAliveMs = 20, sleep = {},
        )
        val partSize = MjpegFraming.frame(jpegA).size
        assertArrayEquals(MjpegFraming.frame(jpegA), readN(stream, partSize))
        // camera now off → re-emit the last good frame as keep-alive rather than EOF or hang
        assertArrayEquals(MjpegFraming.frame(jpegA), readN(stream, partSize))
    }

    @Test fun revokedCapabilityTerminatesAnExistingStream() {
        var authorized = true
        val stream = MjpegFrameStream(
            frames = { jpegA },
            onClose = {},
            sleep = {},
            stillAuthorized = { authorized },
        )
        assertTrue(stream.read() >= 0)
        authorized = false
        assertEquals(-1, stream.read())
    }
}
