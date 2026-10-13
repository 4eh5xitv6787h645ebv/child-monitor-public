package de.rochefort.childmonitor.network

import org.junit.Test
import kotlin.test.assertEquals

class MediaControllerTest {

    // Fixed fake clock past the dwell window so the first level transition is
    // allowed (lastChangeMs starts at 0; transitions need now - last > 4s).
    private fun controller() = MediaController(nowMs = { 10_000L })

    @Test fun `healthy link stays at full quality`() {
        val ctl = controller()
        val policy = ctl.apply(MediaController.Observation(rttMs = 60, lossPct = 0.1, audioQueueDepthMs = 40))
        assertEquals(MediaController.Level.FULL, policy.level)
    }

    @Test fun `degraded link drops to REDUCED`() {
        val ctl = controller()
        val p = ctl.apply(MediaController.Observation(rttMs = 260, lossPct = 2.0, audioQueueDepthMs = 150))
        assertEquals(MediaController.Level.REDUCED, p.level)
    }

    @Test fun `loss spike triggers audio-only`() {
        val ctl = controller()
        val p = ctl.apply(MediaController.Observation(rttMs = 800, lossPct = 20.0, audioQueueDepthMs = 1000))
        assertEquals(MediaController.Level.AUDIO_ONLY, p.level)
    }
}
