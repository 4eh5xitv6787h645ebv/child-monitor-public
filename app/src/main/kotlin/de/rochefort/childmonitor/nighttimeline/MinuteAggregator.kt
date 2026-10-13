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

/**
 * Rolls a stream of audio-level and motion samples up into one [MinuteBucket] per minute
 * (#283). Pure and time-injected — the caller passes `nowMs`, so it's fully unit-testable
 * and has no Android / clock dependencies.
 *
 * Usage: feed [addLevel]/[addMotion]; each returns a finalized bucket the moment the wall
 * clock crosses into a new minute (else null). Call [flush] on stop to emit the partial
 * current minute. Not internally synchronized — the caller serializes access.
 */
class MinuteAggregator(private val minuteMs: Long = 60_000L) {

    private var curMinute = -1L
    private var min = Double.NaN
    private var peak = Double.NaN
    private var sum = 0.0
    private var count = 0
    private var motionMax = 0
    private var sawAudio = false

    fun addLevel(dbfs: Double, nowMs: Long): MinuteBucket? {
        val emitted = rollIfNeeded(floorMinute(nowMs))
        sawAudio = true
        if (min.isNaN() || dbfs < min) min = dbfs
        if (peak.isNaN() || dbfs > peak) peak = dbfs
        sum += dbfs
        count++
        return emitted
    }

    fun addMotion(score: Int, nowMs: Long): MinuteBucket? {
        val emitted = rollIfNeeded(floorMinute(nowMs))
        if (score > motionMax) motionMax = score
        return emitted
    }

    /** Emit the in-progress minute (e.g. on session stop). Null if nothing accumulated. */
    fun flush(): MinuteBucket? = finalizeCurrent()

    private fun floorMinute(nowMs: Long) = (nowMs / minuteMs) * minuteMs

    private fun rollIfNeeded(m: Long): MinuteBucket? {
        if (curMinute == -1L) {
            curMinute = m
            return null
        }
        // Same minute, OR a backward wall-clock step (NTP correction / manual time change):
        // keep accumulating into the current minute instead of rewinding, so a stale past
        // minute key can never overwrite an already-persisted good row (CONFLICT_REPLACE).
        if (m <= curMinute) return null
        val done = finalizeCurrent()
        curMinute = m
        return done
    }

    private fun finalizeCurrent(): MinuteBucket? {
        if (curMinute == -1L || (count == 0 && motionMax == 0 && !sawAudio)) return null
        val bucket = MinuteBucket(
            minuteEpochMs = curMinute,
            rmsMinDbfs = if (min.isNaN()) AudioLevel.FLOOR_DBFS else min,
            rmsMeanDbfs = if (count > 0) sum / count else AudioLevel.FLOOR_DBFS,
            rmsPeakDbfs = if (peak.isNaN()) AudioLevel.FLOOR_DBFS else peak,
            motion = motionMax,
            hasAudio = sawAudio
        )
        min = Double.NaN
        peak = Double.NaN
        sum = 0.0
        count = 0
        motionMax = 0
        sawAudio = false
        return bucket
    }
}
