package de.rochefort.childmonitor.events

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteEventAlertPolicyTest {
    @Test fun `fresh alert-worthy cry notifies`() {
        assertTrue(RemoteEventAlertPolicy.shouldNotify("cry", true, 2_000L, false, false))
    }

    @Test fun `motion is supported`() {
        assertTrue(RemoteEventAlertPolicy.shouldNotify("motion", true, 1L, false, false))
    }

    @Test fun `duplicate muted suppressed and quiet-gated events do not notify`() {
        assertFalse(RemoteEventAlertPolicy.shouldNotify("cry", true, 1L, true, false))
        assertFalse(RemoteEventAlertPolicy.shouldNotify("cry", true, 1L, false, true))
        assertFalse(RemoteEventAlertPolicy.shouldNotify("cry", false, 1L, false, false))
    }

    @Test fun `stale future and unknown events do not notify`() {
        assertFalse(RemoteEventAlertPolicy.shouldNotify("cry", true, 30_001L, false, false))
        assertFalse(RemoteEventAlertPolicy.shouldNotify("cry", true, -1L, false, false))
        assertFalse(RemoteEventAlertPolicy.shouldNotify("noise", true, 1L, false, false))
    }
}
