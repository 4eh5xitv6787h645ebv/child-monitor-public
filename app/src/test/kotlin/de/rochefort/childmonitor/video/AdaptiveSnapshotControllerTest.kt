package de.rochefort.childmonitor.video

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdaptiveSnapshotControllerTest {

    @Test fun `healthy conditions give base interval`() {
        val c = AdaptiveSnapshotController(baseIntervalMs = 1000, minIntervalMs = 500, maxIntervalMs = 10000)
        val ms = c.nextIntervalMs(AdaptiveSnapshotController.Inputs())
        assertTrue(ms in 500..1000)
    }

    @Test fun `thermal paused returns MAX_VALUE`() {
        val c = AdaptiveSnapshotController()
        val ms = c.nextIntervalMs(
            AdaptiveSnapshotController.Inputs(thermal = ThermalProfileManager.Profile.PAUSED)
        )
        assertEquals(Long.MAX_VALUE, ms)
    }

    @Test fun `high motion speeds up snapshots`() {
        val c = AdaptiveSnapshotController(baseIntervalMs = 4000, minIntervalMs = 500, maxIntervalMs = 10000)
        val ms = c.nextIntervalMs(AdaptiveSnapshotController.Inputs(motionScore = 0.9))
        assertTrue(ms < 2000)
    }

    @Test fun `battery saver without charging slows snapshots`() {
        val c = AdaptiveSnapshotController(baseIntervalMs = 1000, minIntervalMs = 500, maxIntervalMs = 10000)
        val ms = c.nextIntervalMs(
            AdaptiveSnapshotController.Inputs(batterySaver = true, chargingPlugged = false)
        )
        assertTrue(ms >= 1000)
    }
}
