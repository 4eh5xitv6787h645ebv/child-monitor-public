package de.rochefort.childmonitor

import de.rochefort.childmonitor.ConnectionStatePolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Either live channel (audio or video) proves the child IP is good, so only a BOTH-down failure may
 * enter the reconnect/discovery loop. This pins the 4-quadrant truth table for that decision.
 */
class ConnectionStatePolicyTest {

    @Test
    fun bothUp_isConnected() {
        assertEquals(Action.CONNECTED, ConnectionStatePolicy.onChannelDown(audioUp = true, videoUp = true))
    }

    @Test
    fun audioOnly_retriesMissingChannel() {
        assertEquals(Action.RETRY_MISSING_CHANNEL, ConnectionStatePolicy.onChannelDown(audioUp = true, videoUp = false))
    }

    @Test
    fun videoOnly_retriesMissingChannel() {
        assertEquals(Action.RETRY_MISSING_CHANNEL, ConnectionStatePolicy.onChannelDown(audioUp = false, videoUp = true))
    }

    @Test
    fun bothDown_fullReconnect() {
        assertEquals(Action.FULL_RECONNECT, ConnectionStatePolicy.onChannelDown(audioUp = false, videoUp = false))
    }
}
