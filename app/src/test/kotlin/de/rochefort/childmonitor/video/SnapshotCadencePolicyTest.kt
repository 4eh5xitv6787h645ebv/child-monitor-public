package de.rochefort.childmonitor.video

import de.rochefort.childmonitor.video.SnapshotCadencePolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #287: snapshot cadence resolution (user mode + adaptive base + near-live glance). */
class SnapshotCadencePolicyTest {

    private val noGlance = 0L

    @Test fun fixedModesUseTheirInterval() {
        assertEquals(SnapshotCadencePolicy.ECO_MS, eff(Mode.ECO, adaptive = 1_000))
        assertEquals(SnapshotCadencePolicy.BALANCED_MS, eff(Mode.BALANCED, adaptive = 1_000))
        assertEquals(SnapshotCadencePolicy.NEAR_LIVE_MS, eff(Mode.NEAR_LIVE, adaptive = 1_000))
    }

    @Test fun autoDefersToAdaptiveInterval() {
        assertEquals(2_500L, eff(Mode.AUTO, adaptive = 2_500))
    }

    @Test fun autoClampsAdaptiveToBounds() {
        assertEquals(SnapshotCadencePolicy.MIN_MS, eff(Mode.AUTO, adaptive = 50))     // below floor
        assertEquals(SnapshotCadencePolicy.MAX_MS, eff(Mode.AUTO, adaptive = 99_999)) // above ceiling
    }

    @Test fun autoPassesThroughThermalPauseSentinel() {
        // Long.MAX_VALUE from a thermal-paused AdaptiveSnapshotController means "stop encoding";
        // it must NOT be clamped down to MAX_MS.
        assertEquals(Long.MAX_VALUE, eff(Mode.AUTO, adaptive = Long.MAX_VALUE))
    }

    @Test fun glanceOverridesModeToNearLive() {
        // Even in ECO, an active glance forces near-live.
        val r = SnapshotCadencePolicy.effectiveIntervalMs(
            mode = Mode.ECO, adaptiveIntervalMs = 8_000, glanceUntilMs = 5_000, nowMs = 1_000
        )
        assertEquals(SnapshotCadencePolicy.NEAR_LIVE_MS, r)
    }

    @Test fun glanceOverridesThermalPause() {
        // A user actively watching wins even over a thermal-paused adaptive interval.
        val r = SnapshotCadencePolicy.effectiveIntervalMs(
            mode = Mode.AUTO, adaptiveIntervalMs = Long.MAX_VALUE, glanceUntilMs = 5_000, nowMs = 1_000
        )
        assertEquals(SnapshotCadencePolicy.NEAR_LIVE_MS, r)
    }

    @Test fun expiredGlanceFallsBackToMode() {
        val r = SnapshotCadencePolicy.effectiveIntervalMs(
            mode = Mode.ECO, adaptiveIntervalMs = 8_000, glanceUntilMs = 5_000, nowMs = 5_000 // now == until → expired
        )
        assertEquals(SnapshotCadencePolicy.ECO_MS, r)
    }

    @Test fun parseModeAcceptsAliasesCaseInsensitively() {
        assertEquals(Mode.ECO, SnapshotCadencePolicy.parseMode("eco"))
        assertEquals(Mode.BALANCED, SnapshotCadencePolicy.parseMode(" Balanced "))
        assertEquals(Mode.NEAR_LIVE, SnapshotCadencePolicy.parseMode("near-live"))
        assertEquals(Mode.NEAR_LIVE, SnapshotCadencePolicy.parseMode("GLANCE"))
        assertEquals(Mode.AUTO, SnapshotCadencePolicy.parseMode("AUTO"))
    }

    @Test fun parseModeRejectsUnknown() {
        assertNull(SnapshotCadencePolicy.parseMode("turbo"))
        assertNull(SnapshotCadencePolicy.parseMode(null))
        assertNull(SnapshotCadencePolicy.parseMode(""))
    }

    private fun eff(mode: Mode, adaptive: Long): Long =
        SnapshotCadencePolicy.effectiveIntervalMs(mode, adaptive, noGlance, nowMs = 10_000)
}
