package de.rochefort.childmonitor.nighttimeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** #314: per-night rollup (grouping by night boundary, state minutes, longest run, wakings). */
class NightSummariesTest {

    private val awakeDbfs = -40.0
    private val motionPct = 18

    // Build a minute whose SleepState.classify(...) yields [state] under the thresholds above.
    private fun minute(epochMs: Long, state: NightState): MinuteBucket = when (state) {
        NightState.ASLEEP -> MinuteBucket(epochMs, -70.0, -65.0, -60.0, motion = 0, hasAudio = true)
        NightState.AWAKE -> MinuteBucket(epochMs, -70.0, -65.0, -60.0, motion = 30, hasAudio = true)
        NightState.NOISY -> MinuteBucket(epochMs, -30.0, -25.0, -20.0, motion = 0, hasAudio = true)
        NightState.NO_SIGNAL -> MinuteBucket(epochMs, -120.0, -120.0, -120.0, motion = 0, hasAudio = false)
    }

    private val DAY = 86_400_000L
    private val NIGHT0_START = 64_800_000L // 1970-01-01 18:00 UTC, with zoneOffset 0 + boundaryHour 18
    private val NIGHT1_START = NIGHT0_START + DAY

    private fun summarize(buckets: List<MinuteBucket>) =
        NightSummaries.summarizeNights(buckets, awakeDbfs, motionPct, zoneOffsetMs = 0L, nightBoundaryHour = 18)

    private fun seq(start: Long, vararg states: NightState): List<MinuteBucket> =
        states.mapIndexed { i, s -> minute(start + i * 60_000L, s) }

    @Test fun empty_returnsEmpty() {
        assertEquals(emptyList<NightSummary>(), summarize(emptyList()))
    }

    @Test fun singleNight_countsStatesRunsAndWakings() {
        // ASLEEP,ASLEEP,AWAKE,ASLEEP,ASLEEP,ASLEEP,NOISY
        val buckets = seq(
            NIGHT0_START,
            NightState.ASLEEP, NightState.ASLEEP, NightState.AWAKE,
            NightState.ASLEEP, NightState.ASLEEP, NightState.ASLEEP, NightState.NOISY
        )
        val s = summarize(buckets)
        assertEquals(1, s.size)
        val n = s[0]
        assertEquals(NIGHT0_START, n.nightStartMs)
        assertEquals(5, n.minutesAsleep)
        assertEquals(1, n.minutesAwake)
        assertEquals(1, n.minutesNoisy)
        assertEquals(3, n.longestAsleepRun)   // the A,A,A run
        assertEquals(2, n.wakings)            // ASLEEP→AWAKE and ASLEEP→NOISY
        assertEquals(7, n.minutesRecorded)
    }

    @Test fun groupsMinutesIntoSeparateNights() {
        val buckets = seq(NIGHT0_START, NightState.ASLEEP, NightState.AWAKE, NightState.ASLEEP) +
            seq(NIGHT1_START, NightState.ASLEEP, NightState.ASLEEP, NightState.ASLEEP)
        val s = summarize(buckets)
        assertEquals(2, s.size)
        assertEquals(NIGHT0_START, s[0].nightStartMs)
        assertEquals(1, s[0].wakings)
        assertEquals(NIGHT1_START, s[1].nightStartMs)
        assertEquals(3, s[1].minutesAsleep)
        assertEquals(0, s[1].wakings)
    }

    @Test fun minutesAfterBoundaryStayInSameNight() {
        // A minute at 02:00 (next calendar day) belongs to the night that started at the prior 18:00.
        val twoAm = NIGHT0_START + 8 * 3_600_000L // 18:00 + 8h = 02:00 next day
        val buckets = listOf(minute(NIGHT0_START, NightState.ASLEEP), minute(twoAm, NightState.ASLEEP))
        val s = summarize(buckets)
        assertEquals(1, s.size) // same night
        assertEquals(2, s[0].minutesAsleep)
    }

    @Test fun noSignalIsNeutralForWakings_butBreaksRun() {
        // ASLEEP, NO_SIGNAL, AWAKE → the gap must not be counted as a waking.
        val a = summarize(seq(NIGHT0_START, NightState.ASLEEP, NightState.NO_SIGNAL, NightState.AWAKE))[0]
        assertEquals(1, a.wakings)         // the real ASLEEP→AWAKE, not the gap
        assertEquals(1, a.longestAsleepRun)

        // ASLEEP, NO_SIGNAL, ASLEEP → no waking; the gap breaks the run (each side length 1).
        val b = summarize(seq(NIGHT0_START, NightState.ASLEEP, NightState.NO_SIGNAL, NightState.ASLEEP))[0]
        assertEquals(0, b.wakings)
        assertEquals(2, b.minutesAsleep)
        assertEquals(1, b.minutesNoSignal)
        assertEquals(1, b.longestAsleepRun) // gap breaks it
    }

    @Test fun nonZeroZoneOffsetGroupsAndRoundTrips() {
        // With a +2h offset, a minute at 17:30 UTC is 19:30 local → after the 18:00 local boundary,
        // so it belongs to that local night; the returned nightStartMs must be the 18:00-local epoch.
        val offset = 2 * 3_600_000L
        // 18:00 local on day idx 0 in epoch = idx*DAY + boundaryMs - offset = 64_800_000 - 7_200_000.
        val nightStartLocal0 = 64_800_000L - offset
        val justAfter = nightStartLocal0 + 90 * 60_000L // 19:30 local
        val s = NightSummaries.summarizeNights(
            listOf(minute(justAfter, NightState.ASLEEP)),
            awakeDbfs, motionPct, zoneOffsetMs = offset, nightBoundaryHour = 18
        )
        assertEquals(1, s.size)
        assertEquals(nightStartLocal0, s[0].nightStartMs)
        assertEquals(1, s[0].minutesAsleep)
    }

    @Test fun noSignalOnlyNight_hasNoSleepNoWakings() {
        val s = summarize(seq(NIGHT0_START, NightState.NO_SIGNAL, NightState.NO_SIGNAL))[0]
        assertEquals(0, s.minutesAsleep)
        assertEquals(0, s.wakings)
        assertEquals(0, s.minutesRecorded)
        assertEquals(2, s.minutesNoSignal)
        assertEquals(0, s.longestAsleepRun)
    }

    @Test fun unsortedInputIsHandled() {
        // Same as singleNight but shuffled — aggregator must sort by time first.
        val ordered = seq(
            NIGHT0_START,
            NightState.ASLEEP, NightState.ASLEEP, NightState.AWAKE, NightState.ASLEEP
        )
        val shuffled = listOf(ordered[2], ordered[0], ordered[3], ordered[1])
        val n = summarize(shuffled)[0]
        assertEquals(1, n.wakings)          // ASLEEP→AWAKE only when correctly ordered
        assertEquals(3, n.minutesAsleep)
    }
}
