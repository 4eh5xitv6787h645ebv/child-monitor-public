package de.rochefort.childmonitor.test

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BurnInReporterTest {

    @Test fun `clean run passes`() {
        val run = BurnInReporter.Run(
            runId = "r1",
            startWallMs = 0L,
            endWallMs = 1000L,
            samples = listOf(
                BurnInReporter.Sample(0L, 10.0, 120.0, 40, 0.1, 0, 0),
                BurnInReporter.Sample(500L, 12.0, 125.0, 45, 0.2, 0, 0)
            ),
            incidents = emptyList()
        )
        val report = BurnInReporter.toWeeklyReport(run)
        assertTrue(BurnInReporter.isPassing(report))
    }

    @Test fun `error incident fails`() {
        val run = BurnInReporter.Run(
            runId = "r1",
            startWallMs = 0L,
            endWallMs = 1000L,
            samples = listOf(
                BurnInReporter.Sample(0L, 10.0, 120.0, 40, 0.1, 0, 0)
            ),
            incidents = listOf(
                BurnInReporter.Incident(500L, "ERROR", "audio", "underrun storm")
            )
        )
        val report = BurnInReporter.toWeeklyReport(run)
        assertFalse(BurnInReporter.isPassing(report))
    }

    @Test fun `cpu over budget fails`() {
        val run = BurnInReporter.Run(
            runId = "r1",
            startWallMs = 0L,
            endWallMs = 1000L,
            samples = listOf(
                BurnInReporter.Sample(0L, 90.0, 120.0, 40, 0.1, 0, 0)
            ),
            incidents = emptyList()
        )
        val report = BurnInReporter.toWeeklyReport(run)
        assertFalse(BurnInReporter.isPassing(report))
    }
}
