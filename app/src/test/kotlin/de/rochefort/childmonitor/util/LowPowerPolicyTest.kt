package de.rochefort.childmonitor.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #300: the camera runs only when not disabled by the user AND the permission is held. */
class LowPowerPolicyTest {

    @Test fun runsWhenEnabledAndPermitted() {
        assertTrue(LowPowerPolicy.shouldRunCamera(cameraPref = true, hasCameraPermission = true))
    }

    @Test fun audioOnlyWhenUserDisabledCamera() {
        assertFalse(LowPowerPolicy.shouldRunCamera(cameraPref = false, hasCameraPermission = true))
    }

    @Test fun neverRunsWithoutPermission() {
        assertFalse(LowPowerPolicy.shouldRunCamera(cameraPref = true, hasCameraPermission = false))
        assertFalse(LowPowerPolicy.shouldRunCamera(cameraPref = false, hasCameraPermission = false))
    }
}
