package de.rochefort.childmonitor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #325 parts 1 & 3: auto-glance gating, child-interval derivation, parent poll alignment. */
class SnapshotSessionPolicyTest {

    // --- Part 1: shouldAutoGlance ---

    private fun glance(
        baseMode: String? = "AUTO",
        cameraEnabled: Boolean = true,
        scrubActive: Boolean = false,
        videoPausedForAudio: Boolean = false,
        linkHealthy: Boolean = true,
    ) = SnapshotSessionPolicy.shouldAutoGlance(baseMode, cameraEnabled, scrubActive, videoPausedForAudio, linkHealthy)

    @Test fun glance_onlyWhenBaseModeAuto() {
        assertTrue(glance(baseMode = "AUTO"))
        assertFalse(glance(baseMode = "ECO"))
        assertFalse(glance(baseMode = "BALANCED"))
        assertFalse(glance(baseMode = "NEAR_LIVE"))
    }

    @Test fun glance_notWhenModeUnknown() = assertFalse(glance(baseMode = null))
    @Test fun glance_notWhenCameraOff() = assertFalse(glance(cameraEnabled = false))
    @Test fun glance_notWhileScrubbing() = assertFalse(glance(scrubActive = true))
    @Test fun glance_notWhenVideoPausedForAudio() = assertFalse(glance(videoPausedForAudio = true))
    @Test fun glance_notWhenLinkUnhealthy() = assertFalse(glance(linkHealthy = false))

    // --- Part 3a: childEffectiveIntervalMs (derived, not from the wire) ---

    @Test fun derive_glanceOverridesToNearLive() {
        // While auto-glancing, the child is near-live regardless of base mode.
        assertEquals(300L, SnapshotSessionPolicy.childEffectiveIntervalMs("AUTO", autoGlancing = true))
    }

    @Test fun derive_perExplicitMode() {
        assertEquals(5000L, SnapshotSessionPolicy.childEffectiveIntervalMs("ECO", autoGlancing = false))
        assertEquals(1000L, SnapshotSessionPolicy.childEffectiveIntervalMs("BALANCED", autoGlancing = false))
        assertEquals(300L, SnapshotSessionPolicy.childEffectiveIntervalMs("NEAR_LIVE", autoGlancing = false))
        assertEquals(1000L, SnapshotSessionPolicy.childEffectiveIntervalMs("AUTO", autoGlancing = false))
    }

    @Test fun derive_switchingToEcoDuringGlance_alignsToEcoNotStaleGlance() {
        // The Codex P2 case: base mode is now ECO and we're no longer glancing → 5000, not 300.
        assertEquals(5000L, SnapshotSessionPolicy.childEffectiveIntervalMs("ECO", autoGlancing = false))
    }

    @Test fun derive_unknownMode() = assertEquals(-1L, SnapshotSessionPolicy.childEffectiveIntervalMs(null, autoGlancing = false))

    // --- Part 3b: alignedPollIntervalMs ---

    @Test fun align_unknownChild_keepsParentInterval() {
        assertEquals(1800L, SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveMs = 1800L, childEffectiveMs = -1L, linkHealthy = true))
    }

    @Test fun align_slowChild_matchesChild_noOverPoll() {
        assertEquals(5000L, SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveMs = 1500L, childEffectiveMs = 5000L, linkHealthy = true))
    }

    @Test fun align_nearLiveChild_healthyLink_keepsUp() {
        assertEquals(300L, SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveMs = 1900L, childEffectiveMs = 300L, linkHealthy = true))
    }

    @Test fun align_nearLiveChild_poorLink_staysSafe() {
        assertEquals(10000L, SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveMs = 10000L, childEffectiveMs = 300L, linkHealthy = false))
    }

    @Test fun align_childSlightlyFaster_healthy_matchesChild() {
        assertEquals(1000L, SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveMs = 1500L, childEffectiveMs = 1000L, linkHealthy = true))
    }

    // HIGH fix: a hostile/buggy child value can never drive the parent out of [250, 10000].
    @Test fun align_clampsRunawayFastChild() {
        assertEquals(250L, SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveMs = 1500L, childEffectiveMs = 50L, linkHealthy = true))
    }

    @Test fun align_clampsRunawaySlowChild() {
        assertEquals(10000L, SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveMs = 1500L, childEffectiveMs = 3_600_000L, linkHealthy = true))
    }

    @Test fun glanceConstants_pingShorterThanGlance() {
        assertTrue(SnapshotSessionPolicy.GLANCE_PING_INTERVAL_MS < SnapshotSessionPolicy.GLANCE_MS)
    }
}
