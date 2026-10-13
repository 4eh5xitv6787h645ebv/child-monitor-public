package de.rochefort.childmonitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quick-Reconnect / boot target precedence: default kid (if still paired) → most-recent → null.
 */
class QuickReconnectTargetTest {

    private fun child(id: String) =
        PairedChild(deviceId = id, name = id, lastKnownIp = "10.0.0.1", lastKnownPort = 10000, lastSeenMs = 0L)

    @Test
    fun empty_returnsNull() {
        assertNull(QuickReconnectTarget.resolve("abcd", emptyList()))
        assertNull(QuickReconnectTarget.resolve(null, emptyList()))
    }

    @Test
    fun defaultInRoster_wins_evenIfNotMostRecent() {
        val paired = listOf(child("recent"), child("mid"), child("def"))
        assertEquals("def", QuickReconnectTarget.resolve("def", paired)?.deviceId)
    }

    @Test
    fun defaultNotInRoster_fallsBackToMostRecent() {
        val paired = listOf(child("recent"), child("other"))
        assertEquals("recent", QuickReconnectTarget.resolve("gone", paired)?.deviceId)
    }

    @Test
    fun noDefault_usesMostRecent() {
        val paired = listOf(child("recent"), child("other"))
        assertEquals("recent", QuickReconnectTarget.resolve(null, paired)?.deviceId)
        assertEquals("recent", QuickReconnectTarget.resolve("  ", paired)?.deviceId)
    }
}
