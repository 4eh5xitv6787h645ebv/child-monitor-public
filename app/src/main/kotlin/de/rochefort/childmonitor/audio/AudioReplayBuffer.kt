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
package de.rochefort.childmonitor.audio

/**
 * #284: fixed-capacity in-memory ring of the most recently captured audio (G.711 mu-law, 8 kHz
 * mono) so a parent woken by an alert can replay the last ~minute of what just happened. Oldest
 * bytes drop as new ones arrive. Thread-safe: the capture thread [append]s while an HTTP thread
 * reads via [lastSeconds]. Pure/JVM-testable (no Android deps); the bytes are whatever codec the
 * caller feeds — mu-law in this app at 8000 B/s.
 */
class AudioReplayBuffer(
    val maxSeconds: Int = 60,
    val bytesPerSecond: Int = 8000
) {
    private val capacity = maxSeconds * bytesPerSecond
    private val ring = ByteArray(capacity)
    private var writePos = 0
    private var filled = 0
    private var lastAppendMs = 0L
    private val lock = Any()

    /**
     * Append [length] bytes from [data] starting at [offset], stamped with monotonic [nowMs].
     * A write larger than the ring keeps only its tail.
     */
    fun append(data: ByteArray, offset: Int, length: Int, nowMs: Long) {
        if (length <= 0) return
        synchronized(lock) {
            lastAppendMs = nowMs
            var off = offset
            var len = length
            if (len >= capacity) {
                off += (len - capacity)
                len = capacity
            }
            var src = off
            var remaining = len
            while (remaining > 0) {
                val chunk = minOf(remaining, capacity - writePos)
                System.arraycopy(data, src, ring, writePos, chunk)
                writePos = (writePos + chunk) % capacity
                src += chunk
                remaining -= chunk
            }
            filled = minOf(capacity, filled + len)
        }
    }

    /**
     * The last [seconds] of audio (clamped to what's buffered), ordered oldest→newest. Returns
     * empty if the buffer is *stale* — no audio appended within [maxGapMs] of [nowMs] — so a parent
     * never replays minutes/hours-old room audio as if it just happened (privacy). A short
     * disconnect→reconnect (post-alert) stays within the window, so that audio is preserved.
     */
    fun lastSeconds(seconds: Int, nowMs: Long, maxGapMs: Long): ByteArray = synchronized(lock) {
        if (isStale(nowMs, maxGapMs)) return ByteArray(0)
        val want = minOf(filled, maxOf(0, seconds) * bytesPerSecond)
        val out = ByteArray(want)
        if (want == 0) return out
        val start = ((writePos - want) % capacity + capacity) % capacity
        val first = minOf(want, capacity - start)
        System.arraycopy(ring, start, out, 0, first)
        if (want > first) System.arraycopy(ring, 0, out, first, want - first)
        return out
    }

    /** Milliseconds of audio currently buffered, or 0 if stale (see [lastSeconds]). */
    fun availableMs(nowMs: Long, maxGapMs: Long): Long = synchronized(lock) {
        if (isStale(nowMs, maxGapMs)) 0L else filled.toLong() * 1000L / bytesPerSecond
    }

    private fun isStale(nowMs: Long, maxGapMs: Long): Boolean =
        filled == 0 || nowMs - lastAppendMs > maxGapMs

    fun clear() = synchronized(lock) {
        writePos = 0
        filled = 0
        lastAppendMs = 0L
    }
}
