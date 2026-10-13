package de.rochefort.childmonitor.events

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEnvironmentAlertPolicyTest {
    @Test fun `only fresh unhandled unmuted room alerts notify`() {
        val now = 1_000_000L
        assertTrue(RemoteEnvironmentAlertPolicy.shouldNotify("temperature_high", now - 1_000, now, false, false))
        assertFalse(RemoteEnvironmentAlertPolicy.shouldNotify("temperature_high", now - 400_000, now, false, false))
        assertFalse(RemoteEnvironmentAlertPolicy.shouldNotify("temperature_high", now - 1_000, now, true, false))
        assertFalse(RemoteEnvironmentAlertPolicy.shouldNotify("temperature_high", now - 1_000, now, false, true))
        assertFalse(RemoteEnvironmentAlertPolicy.shouldNotify(null, now, now, false, false))
    }
}
