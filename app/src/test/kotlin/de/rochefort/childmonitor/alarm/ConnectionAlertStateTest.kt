package de.rochefort.childmonitor.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #289: pure-logic tests for the debounced, episode-scoped disconnect-alert state. */
class ConnectionAlertStateTest {

    @Test fun postsOnceAfterDebounce_notPerRetry() {
        val s = ConnectionAlertState(debounceMs = 6_000L)
        assertFalse(s.onDisconnect(0L))        // first tick: start of outage, before debounce
        assertFalse(s.onDisconnect(3_000L))    // still within debounce
        assertTrue(s.onDisconnect(6_000L))     // crossed debounce → post
        assertFalse(s.onDisconnect(7_000L))    // already posted this outage → no re-post
        assertFalse(s.onDisconnect(60_000L))
    }

    @Test fun briefBlipNeverPosts() {
        val s = ConnectionAlertState(debounceMs = 6_000L)
        assertFalse(s.onDisconnect(0L))
        assertFalse(s.onDisconnect(2_000L))    // reconnected at 2s, before debounce
        s.onReconnect()
        // a subsequent brief blip also stays silent
        assertFalse(s.onDisconnect(10_000L))
        assertFalse(s.onDisconnect(12_000L))
    }

    @Test fun acknowledgeSuppressesUntilReconnect() {
        val s = ConnectionAlertState(debounceMs = 1_000L)
        assertTrue(s.onDisconnect(0L) || s.onDisconnect(1_000L)) // posts after debounce
        s.acknowledge()
        assertFalse(s.onDisconnect(2_000L))    // suppressed
        assertFalse(s.onDisconnect(60_000L))
        s.onReconnect()                         // reconnected — re-arm
        assertFalse(s.onDisconnect(61_000L))    // new outage starts
        assertTrue(s.onDisconnect(62_000L))     // posts again after debounce
    }

    @Test fun backoffTicksFireExactlyOneCuePerOutage() {
        // #347 repro: the reconnect loop ticks on exponential backoff (≈4/8/16/32s). The cue is now
        // gated on this return, so across a whole sustained outage it must be true EXACTLY once (the
        // first tick past the debounce), not once per tick (the observed beep/vibration-per-retry bug).
        val s = ConnectionAlertState(debounceMs = 6_000L)
        val ticks = longArrayOf(0L, 4_000L, 8_000L, 16_000L, 32_000L)
        val fired = ticks.count { s.onDisconnect(it) }
        assertEquals(1, fired)
    }

    @Test fun newOutageAfterReconnectPostsAgain() {
        val s = ConnectionAlertState(debounceMs = 1_000L)
        s.onDisconnect(0L); assertTrue(s.onDisconnect(1_000L))
        s.onReconnect()
        s.onDisconnect(10_000L); assertTrue(s.onDisconnect(11_000L))
    }
}
