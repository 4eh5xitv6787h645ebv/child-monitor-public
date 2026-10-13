package de.rochefort.childmonitor.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #313: PiP entry eligibility + aspect-ratio clamping. */
class PipPolicyTest {

    @Test fun canEnter_allConditionsMet() {
        assertTrue(PipPolicy.canEnter(hasPipFeature = true, sdkSupportsPip = true, cameraEnabled = true, hasFrame = true, scrubActive = false))
    }

    @Test fun canEnter_falseWhenFeatureMissing() {
        assertFalse(PipPolicy.canEnter(false, sdkSupportsPip = true, cameraEnabled = true, hasFrame = true, scrubActive = false))
    }

    @Test fun canEnter_falseWhenSdkTooLow() {
        assertFalse(PipPolicy.canEnter(hasPipFeature = true, sdkSupportsPip = false, cameraEnabled = true, hasFrame = true, scrubActive = false))
    }

    @Test fun canEnter_falseWhenCameraOff() {
        assertFalse(PipPolicy.canEnter(hasPipFeature = true, sdkSupportsPip = true, cameraEnabled = false, hasFrame = true, scrubActive = false))
    }

    @Test fun canEnter_falseWhenNoFrameYet() {
        assertFalse(PipPolicy.canEnter(hasPipFeature = true, sdkSupportsPip = true, cameraEnabled = true, hasFrame = false, scrubActive = false))
    }

    @Test fun canEnter_falseWhileScrubbing() {
        assertFalse(PipPolicy.canEnter(hasPipFeature = true, sdkSupportsPip = true, cameraEnabled = true, hasFrame = true, scrubActive = true))
    }

    @Test fun clampAspect_reducesTypicalFourThree() {
        // The child serves ~1024x768 (4:3 = 1.333), within bounds → GCD-reduced to 4:3.
        assertEquals(4 to 3, PipPolicy.clampAspect(1024, 768))
    }

    @Test fun clampAspect_reducesSixteenNine() {
        assertEquals(16 to 9, PipPolicy.clampAspect(1920, 1080)) // 1.78, within 2.39
    }

    @Test fun clampAspect_clampsUltraWide() {
        // 3000x500 = 6.0 > 2.39 → clamp to 2.39:1.
        assertEquals(239 to 100, PipPolicy.clampAspect(3000, 500))
    }

    @Test fun clampAspect_clampsUltraTall() {
        // 500x3000 = 0.166 < 1/2.39 → clamp to 1:2.39.
        assertEquals(100 to 239, PipPolicy.clampAspect(500, 3000))
    }

    @Test fun clampAspect_fallsBackOnNonPositive() {
        assertEquals(4 to 3, PipPolicy.clampAspect(0, 0))
        assertEquals(4 to 3, PipPolicy.clampAspect(-100, 200))
        assertEquals(4 to 3, PipPolicy.clampAspect(200, 0))
    }

    @Test fun clampAspect_reducesSquareToOneToOne() {
        // A square is well inside the bounds → reduced to 1:1.
        assertEquals(1 to 1, PipPolicy.clampAspect(1000, 1000))
    }

    @Test fun clampAspect_alreadyReducedStaysSame() {
        assertEquals(4 to 3, PipPolicy.clampAspect(4, 3))
    }
}
