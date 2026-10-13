package de.rochefort.childmonitor.network

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionHealthScorerTest {

    @Test fun `successful writes raise score toward 100`() {
        val scorer = ConnectionHealthScorer()
        scorer.register("a")
        repeat(30) { scorer.observeSuccess("a", latencyMs = 40) }
        assertTrue(scorer.prioritized().first().score > 80)
    }

    @Test fun `failures drop score and consecutive failures accumulate`() {
        val scorer = ConnectionHealthScorer()
        scorer.observeFailure("a")
        scorer.observeFailure("a")
        scorer.observeFailure("a")
        val ep = scorer.prioritized().first()
        assertEquals("a", ep.key)
        assertTrue(ep.score < 80)
        assertEquals(3, ep.consecutiveFailures)
    }

    @Test fun `prioritizes healthiest endpoint first`() {
        val scorer = ConnectionHealthScorer()
        scorer.register("bad")
        scorer.observeFailure("bad", weight = 40.0)
        scorer.register("good")
        scorer.observeSuccess("good", latencyMs = 30)
        val order = scorer.prioritized().map { it.key }
        assertEquals(listOf("good", "bad"), order)
    }
}
