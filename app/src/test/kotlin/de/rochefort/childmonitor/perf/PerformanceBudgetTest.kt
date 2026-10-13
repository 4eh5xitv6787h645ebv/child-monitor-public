package de.rochefort.childmonitor.perf

import org.junit.Test
import kotlin.test.assertEquals

class PerformanceBudgetTest {

    @Test fun `below warning reports OK`() {
        val a = PerformanceBudget.evaluate(PerformanceBudget.STARTUP_LATENCY_MS, observed = 900.0)
        assertEquals(PerformanceBudget.Assessment.Severity.OK, a.severity)
    }

    @Test fun `above warning reports WARNING`() {
        val a = PerformanceBudget.evaluate(PerformanceBudget.STARTUP_LATENCY_MS, observed = 2_500.0)
        assertEquals(PerformanceBudget.Assessment.Severity.WARNING, a.severity)
    }

    @Test fun `above error reports ERROR`() {
        val a = PerformanceBudget.evaluate(PerformanceBudget.STARTUP_LATENCY_MS, observed = 5_000.0)
        assertEquals(PerformanceBudget.Assessment.Severity.ERROR, a.severity)
    }
}
