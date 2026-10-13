/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor.video

import java.io.InputStream

/**
 * #306: a blocking [InputStream] that emits one `multipart/x-mixed-replace` JPEG part per new camera
 * frame from [frames] (the same provider `/snapshot` uses), polling every [pollMs] and deduping by
 * reference so each frame is sent once. NanoHTTPD serves it on a worker thread.
 *
 * Disconnect handling (the important part): NanoHTTPD only notices a dropped client when it next tries
 * to write, which only happens when [read] returns bytes. If the frame source goes idle (camera turned
 * off mid-stream, or publishing paused on a stable frame), we must NOT block forever — that would pin
 * the worker thread and the [MjpegStreamLimiter] slot for a viewer that may already be gone. So after
 * [keepAliveMs] with no new frame we re-emit the last frame as a keep-alive (forcing a write that
 * surfaces a broken pipe), and if no frame ever arrived we return EOF so the consumer reconnects.
 *
 * The [sleep] seam is injected so the poll/keep-alive timing is unit-testable without real waiting.
 */
class MjpegFrameStream(
    private val frames: () -> ByteArray?,
    private val onClose: () -> Unit,
    private val pollMs: Long = 150L,
    private val keepAliveMs: Long = 2_000L,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val stillAuthorized: () -> Boolean = { true },
) : InputStream() {

    private var buffer: ByteArray = ByteArray(0)
    private var pos = 0
    private var lastFrame: ByteArray? = null
    @Volatile private var closed = false

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n == -1) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed || !authorized()) return -1
        if (pos >= buffer.size) {
            val next = nextPartBlocking() ?: return -1
            buffer = next
            pos = 0
        }
        val n = minOf(len, buffer.size - pos)
        System.arraycopy(buffer, pos, b, off, n)
        pos += n
        return n
    }

    /** Block until a new frame is ready; on idle, re-emit the last frame (keep-alive) or EOF (null). */
    private fun nextPartBlocking(): ByteArray? {
        var waited = 0L
        while (!closed) {
            if (!authorized()) return null
            val jpeg = try { frames() } catch (_: Exception) { null }
            if (jpeg != null && jpeg !== lastFrame) {
                lastFrame = jpeg
                return MjpegFraming.frame(jpeg)
            }
            if (waited >= keepAliveMs) {
                val last = lastFrame ?: return null // never had a frame → clean end-of-stream
                return MjpegFraming.frame(last)     // keep-alive re-emit → write surfaces a disconnect
            }
            try {
                sleep(pollMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            waited += pollMs
        }
        return null
    }

    private fun authorized(): Boolean = try { stillAuthorized() } catch (_: Exception) { false }

    /**
     * NanoHTTPD guarantees this runs on request teardown: `HTTPSession.execute()` closes the Response
     * in its finally on every exit path (normal + error + server stop) and `Response.close()` closes
     * its data stream — so [onClose] (the limiter release) is reliable even though `Response.send()`'s
     * own disconnect path skips the stream close. [closed] is set first so the keep-alive loop exits.
     */
    override fun close() {
        if (!closed) {
            closed = true
            try { onClose() } catch (_: Exception) {}
        }
        super.close()
    }
}
