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

/**
 * #306: pure `multipart/x-mixed-replace` (MJPEG) framing — the boundary, the per-frame part header,
 * and the response content-type. Each part is length-delimited (Content-Length), so the boundary
 * appearing inside JPEG bytes is harmless to a conformant reader. No Android deps → unit-testable.
 */
object MjpegFraming {

    const val BOUNDARY = "childmonitorframe"

    /** The HTTP response Content-Type a consumer (browser / Home Assistant / Frigate) reads. */
    const val CONTENT_TYPE = "multipart/x-mixed-replace; boundary=$BOUNDARY"

    private val CRLF = "\r\n"

    /** Bytes written before each JPEG: boundary delimiter + per-part headers, ending with a blank line. */
    fun partHeader(contentLength: Int): ByteArray =
        ("--$BOUNDARY$CRLF" +
            "Content-Type: image/jpeg$CRLF" +
            "Content-Length: $contentLength$CRLF" +
            CRLF).toByteArray(Charsets.US_ASCII)

    /** Bytes written after each JPEG so the next boundary starts on its own line. */
    val partTrailer: ByteArray = CRLF.toByteArray(Charsets.US_ASCII)

    /** A complete part: header + jpeg + trailer. */
    fun frame(jpeg: ByteArray): ByteArray {
        val header = partHeader(jpeg.size)
        val out = ByteArray(header.size + jpeg.size + partTrailer.size)
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(jpeg, 0, out, header.size, jpeg.size)
        System.arraycopy(partTrailer, 0, out, header.size + jpeg.size, partTrailer.size)
        return out
    }
}
