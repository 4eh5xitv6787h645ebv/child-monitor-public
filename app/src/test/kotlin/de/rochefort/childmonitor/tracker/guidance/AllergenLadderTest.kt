package de.rochefort.childmonitor.tracker.guidance

import org.junit.Test
import kotlin.test.assertEquals

class AllergenLadderTest {

    private val DAY = 24L * 3600_000L

    @Test fun `no exposures is NOT_STARTED`() {
        val e = AllergenLadder.evaluate("EGG", emptyList(), nowMs = 0L)
        assertEquals(AllergenLadder.Status.NOT_STARTED, e.status)
    }

    @Test fun `anaphylaxis locks to AVOID`() {
        val e = AllergenLadder.evaluate(
            "EGG",
            listOf(AllergenLadder.Exposure(tsMs = 0L, proteinGrams = 1.0, reaction = AllergenLadder.Reaction.ANAPHYLAXIS)),
            nowMs = 5L
        )
        assertEquals(AllergenLadder.Status.AVOID, e.status)
    }

    @Test fun `first exposure is INTRODUCED`() {
        val e = AllergenLadder.evaluate(
            "EGG",
            listOf(AllergenLadder.Exposure(tsMs = 0L, proteinGrams = 1.0)),
            nowMs = 30 * 60_000L
        )
        assertEquals(AllergenLadder.Status.INTRODUCED, e.status)
    }

    @Test fun `generic established after four weekly exposures`() {
        val now = 30L * DAY
        val exposures = listOf(
            AllergenLadder.Exposure(now - 3 * DAY, 2.0),
            AllergenLadder.Exposure(now - 10 * DAY, 2.0),
            AllergenLadder.Exposure(now - 17 * DAY, 2.0),
            AllergenLadder.Exposure(now - 24 * DAY, 2.0)
        )
        val e = AllergenLadder.evaluate("EGG", exposures, now)
        assertEquals(AllergenLadder.Status.ESTABLISHED, e.status)
    }

    @Test fun `peanut needs 3 sessions 6g two weeks`() {
        val now = 14L * DAY
        val exposures = mutableListOf<AllergenLadder.Exposure>()
        // Three inside week 0 (strict <7 days ago).
        repeat(3) { exposures.add(AllergenLadder.Exposure(now - ((it + 1) * DAY), 2.1)) }
        // Three inside week 1 (strict 7..<14 days ago).
        repeat(3) { exposures.add(AllergenLadder.Exposure(now - ((8 + it) * DAY), 2.1)) }
        val e = AllergenLadder.evaluate("PEANUT", exposures, now)
        assertEquals(AllergenLadder.Status.ESTABLISHED, e.status)
    }

    @Test fun `peanut introduced without maintenance stays INTRODUCED`() {
        val now = 14L * DAY
        val exposures = listOf(
            AllergenLadder.Exposure(now - DAY, 2.0),
            AllergenLadder.Exposure(now - 3 * DAY, 2.0),
            AllergenLadder.Exposure(now - 5 * DAY, 2.0)
        )
        val e = AllergenLadder.evaluate("PEANUT", exposures, now)
        assertEquals(AllergenLadder.Status.INTRODUCED, e.status)
    }

    @Test fun `peanut exposure on week boundary counts only once`() {
        val now = 14L * DAY
        val exposures = listOf(
            // Exactly ONE_WEEK_MS old: belongs to week 1 only (half-open rule).
            AllergenLadder.Exposure(now - 7 * DAY, 6.0),
            AllergenLadder.Exposure(now - 3 * DAY, 1.0),
            AllergenLadder.Exposure(now - 1 * DAY, 1.0)
        )
        // Week 0 has two sessions totalling 2g — fails >=3 and >=6g.
        val e = AllergenLadder.evaluate("PEANUT", exposures, now)
        assertEquals(AllergenLadder.Status.INTRODUCED, e.status)
    }
}
