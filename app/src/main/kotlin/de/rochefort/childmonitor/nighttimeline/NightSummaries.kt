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

/** #314: per-night rollup of the per-minute night-timeline data, for the multi-night trends view. */
data class NightSummary(
    /** Epoch ms of this night's boundary start (e.g. 6pm local); also the bar's identity + label source. */
    val nightStartMs: Long,
    val minutesAsleep: Int,
    val minutesAwake: Int,
    val minutesNoisy: Int,
    val minutesNoSignal: Int,
    /** Longest unbroken ASLEEP run in minutes (consecutive recorded minutes). */
    val longestAsleepRun: Int,
    /** Number of times the baby woke — an ASLEEP run followed by an AWAKE/NOISY minute. */
    val wakings: Int
) {
    /** Minutes with any recorded state (excludes NO_SIGNAL gaps) — the stacked bar height. */
    val minutesRecorded: Int get() = minutesAsleep + minutesAwake + minutesNoisy
}

/**
 * #314 (epic #283): pure aggregation of the per-minute [MinuteBucket] stream into per-night
 * summaries ("one bar per night: time-asleep + wakings"). No Android deps → unit-testable.
 *
 * A "night" runs from [nightBoundaryHour] (local) to the same hour the next day and is keyed by the
 * epoch of its start. Local time is derived from the UTC [zoneOffsetMs] the caller passes (kept out
 * of this pure code). Minutes are reclassified with the current sensitivity rather than trusting the
 * stored state — exactly what NightTimelineActivity does at render time, so changing sensitivity
 * re-derives trends without recapture.
 */
object NightSummaries {

    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L

    fun summarizeNights(
        buckets: List<MinuteBucket>,
        awakeDbfs: Double,
        motionThresholdPercent: Int,
        zoneOffsetMs: Long,
        nightBoundaryHour: Int = 18
    ): List<NightSummary> {
        if (buckets.isEmpty()) return emptyList()
        val boundaryMs = nightBoundaryHour.toLong() * HOUR_MS
        // Group by night index, keeping each night's minutes in chronological order.
        val byNight = sortedMapOf<Long, MutableList<NightState>>()
        for (b in buckets.sortedBy { it.minuteEpochMs }) {
            val idx = Math.floorDiv(b.minuteEpochMs + zoneOffsetMs - boundaryMs, DAY_MS)
            val state = SleepState.classify(b, awakeDbfs, motionThresholdPercent)
            byNight.getOrPut(idx) { mutableListOf() }.add(state)
        }
        return byNight.map { (idx, states) ->
            val nightStartMs = idx * DAY_MS + boundaryMs - zoneOffsetMs
            summarizeOneNight(nightStartMs, states)
        }
    }

    private fun summarizeOneNight(nightStartMs: Long, states: List<NightState>): NightSummary {
        var asleep = 0
        var awake = 0
        var noisy = 0
        var noSignal = 0
        var longestRun = 0
        var currentRun = 0
        var wakings = 0
        var lastSolid: NightState? = null // last non-NO_SIGNAL state — gaps don't end an asleep run
        for (s in states) {
            when (s) {
                NightState.ASLEEP -> {
                    asleep++
                    currentRun++
                    if (currentRun > longestRun) longestRun = currentRun
                }
                NightState.AWAKE -> {
                    awake++
                    currentRun = 0
                    if (lastSolid == NightState.ASLEEP) wakings++
                }
                NightState.NOISY -> {
                    noisy++
                    currentRun = 0
                    if (lastSolid == NightState.ASLEEP) wakings++
                }
                NightState.NO_SIGNAL -> {
                    noSignal++
                    // A no-data gap breaks the "longest continuous sleep" run (we can't claim sleep
                    // we didn't observe) but is neutral for wakings — leave lastSolid untouched so a
                    // brief dropout between two asleep stretches isn't miscounted as a waking.
                    currentRun = 0
                }
            }
            if (s != NightState.NO_SIGNAL) lastSolid = s
        }
        return NightSummary(
            nightStartMs = nightStartMs,
            minutesAsleep = asleep,
            minutesAwake = awake,
            minutesNoisy = noisy,
            minutesNoSignal = noSignal,
            longestAsleepRun = longestRun,
            wakings = wakings
        )
    }
}
