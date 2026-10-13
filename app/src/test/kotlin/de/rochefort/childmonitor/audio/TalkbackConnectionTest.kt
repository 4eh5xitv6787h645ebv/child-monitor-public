package de.rochefort.childmonitor.audio

import java.io.IOException
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class TalkbackConnectionTest {
    private class ObservedSocket : Socket() {
        val closed = CountDownLatch(1)
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }

    @Test fun cancellingOldAttemptLeavesNewAttemptOpen() {
        val old = TalkbackConnection()
        val current = TalkbackConnection()
        val oldSocket = ObservedSocket()
        val currentSocket = ObservedSocket()
        old.attach(oldSocket)
        current.attach(currentSocket)
        old.cancel()
        old.cancel()
        assertTrue(oldSocket.closed.await(2, TimeUnit.SECONDS))
        assertEquals(1, oldSocket.closes.get())
        assertFalse(current.isCancelled)
        assertEquals(0, currentSocket.closes.get())
        current.cancel()
        assertTrue(currentSocket.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun cancellationDuringConnectRejectsAndClosesLateSocket() {
        val attempt = TalkbackConnection()
        attempt.cancel()
        val socket = ObservedSocket()
        assertThrows(IOException::class.java) { attempt.attach(socket) }
        assertTrue(socket.closed.await(2, TimeUnit.SECONDS))
    }

    @Test fun cancellationDoesNotWaitForBlockedSocketClose() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val socket = object : Socket() {
            override fun close() { started.countDown(); release.await(2, TimeUnit.SECONDS) }
        }
        val attempt = TalkbackConnection()
        attempt.attach(socket)
        attempt.cancel()
        try { assertTrue(started.await(2, TimeUnit.SECONDS)); assertTrue(attempt.isCancelled) }
        finally { release.countDown() }
    }
}
