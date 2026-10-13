package de.rochefort.childmonitor.reliability

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionStateTest {

    @Test fun `valid transitions are accepted`() {
        assertTrue(SessionState.IDLE.canTransitionTo(SessionState.STARTING))
        assertTrue(SessionState.STARTING.canTransitionTo(SessionState.CONNECTING))
        assertTrue(SessionState.CONNECTING.canTransitionTo(SessionState.CONNECTED))
        assertTrue(SessionState.CONNECTED.canTransitionTo(SessionState.ACTIVE))
        assertTrue(SessionState.ACTIVE.canTransitionTo(SessionState.DRAINING))
        assertTrue(SessionState.DRAINING.canTransitionTo(SessionState.CLOSED))
    }

    @Test fun `invalid transitions are rejected`() {
        assertFalse(SessionState.IDLE.canTransitionTo(SessionState.ACTIVE))
        assertFalse(SessionState.CLOSED.canTransitionTo(SessionState.ACTIVE))
        assertFalse(SessionState.CONNECTED.canTransitionTo(SessionState.IDLE))
    }

    @Test fun `closed can re-arm`() {
        assertTrue(SessionState.CLOSED.canTransitionTo(SessionState.STARTING))
    }

    @Test fun `failed can restart`() {
        assertTrue(SessionState.FAILED.canTransitionTo(SessionState.STARTING))
    }
}
