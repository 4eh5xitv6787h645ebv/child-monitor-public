package de.rochefort.childmonitor.perf

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeGovernorTest {

    @Test fun `quality profile yields fastest snapshot`() {
        val g = RuntimeGovernor()
        val out = g.resolve(RuntimeGovernor.Inputs(profile = RuntimeGovernor.Profile.QUALITY))
        assertTrue(out.snapshotIntervalMs < 1_500)
    }

    @Test fun `battery saver slows snapshots`() {
        val g = RuntimeGovernor()
        val out = g.resolve(RuntimeGovernor.Inputs(profile = RuntimeGovernor.Profile.BATTERY_SAVER))
        assertTrue(out.snapshotIntervalMs >= 2_500)
    }

    @Test fun `thermal paused disables snapshots`() {
        val g = RuntimeGovernor()
        val out = g.resolve(RuntimeGovernor.Inputs(thermalPaused = true))
        assertEquals(Int.MAX_VALUE, out.snapshotIntervalMs)
    }

    @Test fun `battery saver on while charging keeps user profile`() {
        val g = RuntimeGovernor()
        val out = g.resolve(RuntimeGovernor.Inputs(batterySaverOn = true, charging = true))
        assertEquals(g.resolve(RuntimeGovernor.Inputs()).snapshotIntervalMs, out.snapshotIntervalMs)
    }
}
