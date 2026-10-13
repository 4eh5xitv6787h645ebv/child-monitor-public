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
package de.rochefort.childmonitor.nighttimeline

/** Per-minute summary of the night (one row of the timeline). #283. */
data class MinuteBucket(
    /** Epoch millis floored to the minute (the bucket key). */
    val minuteEpochMs: Long,
    /** Quietest / mean / loudest relative-loudness sample this minute, in dBFS. */
    val rmsMinDbfs: Double,
    val rmsMeanDbfs: Double,
    val rmsPeakDbfs: Double,
    /** Max snapshot motion score this minute (0..100 % changed cells), 0 if none seen. */
    val motion: Int,
    /** Whether any audio level was sampled this minute (the parent's heartbeat). */
    val hasAudio: Boolean,
    /** Derived sleep/awake state (set by the recorder via [SleepState]). */
    val state: NightState = NightState.NO_SIGNAL
)

/** Coarse per-minute state shown as the timeline's color band. */
enum class NightState { NO_SIGNAL, ASLEEP, AWAKE, NOISY }
