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

import org.json.JSONObject

/**
 * #338 (part of #284): pure logic for the parent-side snapshot replay scrubber, kept free of
 * Android types so it is unit-testable. Parses the child's `GET /api/replay/snapshots` list body
 * (`{"timestamps":[…],"count":N}`) and maps a SeekBar position to a frame timestamp.
 */
object ReplayScrubPolicy {

    /**
     * Parse the snapshot-list body into frame timestamps, oldest→newest. Returns empty on any
     * malformed/empty input so the caller can simply skip offering a scrubber. Defensively sorted
     * ascending (the child already emits them in order).
     */
    fun parseTimestamps(json: String): List<Long> = try {
        val arr = JSONObject(json).optJSONArray("timestamps")
        if (arr == null) emptyList()
        else (0 until arr.length()).map { arr.getLong(it) }.sorted()
    } catch (_: Exception) {
        emptyList()
    }

    /** Clamp a SeekBar position to a valid index into a list of [size] frames, or -1 if empty. */
    fun clampIndex(index: Int, size: Int): Int =
        if (size <= 0) -1 else index.coerceIn(0, size - 1)

    /** The timestamp at SeekBar position [index] for [timestamps], or null if out of range/empty. */
    fun timestampAt(index: Int, timestamps: List<Long>): Long? {
        val i = clampIndex(index, timestamps.size)
        return if (i < 0) null else timestamps[i]
    }
}
