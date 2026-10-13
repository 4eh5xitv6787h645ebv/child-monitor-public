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

/** What the parent should do with a `/api/replay/audio` response. */
enum class ReplayOutcome {
    /** A non-empty µ-law clip came back — decode and play it. */
    PLAY,
    /** Ring buffer empty or stale (204, or 200 with an empty body) — nothing recent to replay. */
    NOTHING_RECENT,
    /** 401 — bearer token missing/expired; forget it and prompt a re-pair. */
    UNAUTHORIZED,
    /** Any other status (or, at the call site, a transport failure). */
    ERROR
}

/**
 * #338 (part of #284): pure decision logic for the parent-side "Replay last 30s" audio feature,
 * deliberately free of Android types so it is unit-testable.
 *
 * The child's `GET /api/replay/audio?seconds=N` (shipped in #284 / PR #336) returns raw G.711
 * µ-law, 8 kHz mono — exactly the encoding the parent's live path already decodes. It answers
 * 200 + bytes when recent audio is buffered, 204 when the ring buffer is empty or has gone stale
 * (a privacy gate on the child), and 401 when the token is missing/expired.
 */
object ReplayAudioPolicy {
    /** µ-law at 8 kHz mono is exactly 8000 bytes per second (1 byte per sample). */
    const val BYTES_PER_SECOND = 8000

    /** The window the parent requests; mirrors the child's clamp of 1..60s. */
    const val DEFAULT_SECONDS = 30
    const val MIN_SECONDS = 1
    const val MAX_SECONDS = 60

    /** Defensive cap on the response body the parent will buffer (~64s of µ-law). */
    const val MAX_REPLAY_BYTES = (MAX_SECONDS + 4) * BYTES_PER_SECOND

    /** Clamp a requested window to the range the child enforces (1..60s). */
    fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /** Classify the child's replay response from its HTTP status and body length. */
    fun outcome(httpStatus: Int, byteCount: Int): ReplayOutcome = when {
        httpStatus == 401 -> ReplayOutcome.UNAUTHORIZED
        httpStatus == 204 -> ReplayOutcome.NOTHING_RECENT
        httpStatus == 200 -> if (byteCount > 0) ReplayOutcome.PLAY else ReplayOutcome.NOTHING_RECENT
        else -> ReplayOutcome.ERROR
    }

    /** Playback duration of a µ-law clip of [byteCount] bytes, in milliseconds. */
    fun durationMs(byteCount: Int): Long =
        if (byteCount <= 0) 0L else byteCount.toLong() * 1000L / BYTES_PER_SECOND
}
