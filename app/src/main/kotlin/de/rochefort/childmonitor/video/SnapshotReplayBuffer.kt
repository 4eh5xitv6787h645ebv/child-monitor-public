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

/** #337: one captured JPEG frame plus the monotonic time it was taken. */
class SnapshotFrame(val jpeg: ByteArray, val timestampMs: Long) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SnapshotFrame) return false
        return timestampMs == other.timestampMs && jpeg.contentEquals(other.jpeg)
    }

    override fun hashCode(): Int = 31 * jpeg.contentHashCode() + timestampMs.hashCode()
}

/**
 * #337 (part of #284): fixed-capacity in-memory ring of the most recently captured JPEG snapshot
 * frames, so a parent woken by an alert can replay the last ~minute of what just happened on
 * camera. The visual counterpart to [de.rochefort.childmonitor.audio.AudioReplayBuffer], but it
 * stores discrete timestamped frames — a JPEG can't be sliced mid-byte, and the parent needs to
 * know *when* each frame was taken.
 *
 * Oldest frames drop as new ones arrive, bounded by THREE caps so a high cadence or large frames
 * can't grow memory without limit and the window stays roughly time-bounded: a frame count, a
 * total-byte budget, and [maxAgeMs] (the *content* window — how far back frames are retained,
 * ~90s here). The just-added newest frame is always kept. Thread-safe: the camera thread [append]s
 * while an HTTP thread reads. Pure/JVM-testable (no Android deps).
 *
 * Note [maxAgeMs] (content retained) is distinct from the *staleness* window the caller passes to
 * the read methods (whether the buffer is served at all — e.g. the service's 5-minute
 * REPLAY_STALE_MS, the same privacy gate as audio). Retain ~90s of recent frames; serve them only
 * if a frame arrived within the (larger) staleness window so a brief disconnect→reconnect keeps them.
 */
class SnapshotReplayBuffer(
    val maxFrames: Int = 150,
    val maxTotalBytes: Long = 6L * 1024 * 1024,
    val maxAgeMs: Long = 90_000L
) {
    private val frames = ArrayDeque<SnapshotFrame>()
    private var totalBytes = 0L
    private var lastAppendMs = 0L
    private val lock = Any()

    /**
     * Append a captured JPEG stamped with monotonic [nowMs]. Evicts the oldest frames until the
     * count, byte, and age caps all hold — but never drops the frame just added.
     */
    fun append(jpeg: ByteArray, nowMs: Long) {
        if (jpeg.isEmpty()) return
        synchronized(lock) {
            lastAppendMs = nowMs
            frames.addLast(SnapshotFrame(jpeg, nowMs))
            totalBytes += jpeg.size
            while (frames.size > 1 && (
                    frames.size > maxFrames ||
                    totalBytes > maxTotalBytes ||
                    nowMs - frames.first().timestampMs > maxAgeMs)
            ) {
                totalBytes -= frames.removeFirst().jpeg.size
            }
        }
    }

    /**
     * Monotonic timestamps of the buffered frames, oldest→newest. Empty if the ring is *stale* — no
     * frame appended within [maxGapMs] of [nowMs] — so a parent never replays minutes-old room
     * footage as if it just happened (privacy; mirrors AudioReplayBuffer).
     */
    fun timestamps(nowMs: Long, maxGapMs: Long): List<Long> = synchronized(lock) {
        if (isStale(nowMs, maxGapMs)) emptyList() else frames.map { it.timestampMs }
    }

    /** The buffered frame captured at exactly [timestampMs], or null if absent or the ring is stale. */
    fun frameAt(timestampMs: Long, nowMs: Long, maxGapMs: Long): SnapshotFrame? = synchronized(lock) {
        // lastOrNull: timestamps are unique per publish (distinct elapsedRealtime); this is just
        // defensive against a same-ms double-publish, returning the most recent match.
        if (isStale(nowMs, maxGapMs)) null else frames.lastOrNull { it.timestampMs == timestampMs }
    }

    /** Number of buffered frames, or 0 if stale. */
    fun availableCount(nowMs: Long, maxGapMs: Long): Int = synchronized(lock) {
        if (isStale(nowMs, maxGapMs)) 0 else frames.size
    }

    /** Span (newest − oldest) of the buffered frames in ms, or 0 if stale or fewer than two frames. */
    fun availableMs(nowMs: Long, maxGapMs: Long): Long = synchronized(lock) {
        if (isStale(nowMs, maxGapMs) || frames.size < 2) 0L
        else frames.last().timestampMs - frames.first().timestampMs
    }

    private fun isStale(nowMs: Long, maxGapMs: Long): Boolean =
        frames.isEmpty() || nowMs - lastAppendMs > maxGapMs

    fun clear() = synchronized(lock) {
        frames.clear()
        totalBytes = 0L
        lastAppendMs = 0L
    }
}
