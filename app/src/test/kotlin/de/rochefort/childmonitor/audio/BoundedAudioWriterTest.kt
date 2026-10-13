package de.rochefort.childmonitor.audio

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedAudioWriterTest {
    @Test fun `blocked parent does not prevent another parent receiving audio`() {
        val enteredWrite = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val healthyReceived = CountDownLatch(1)
        val slow = BoundedAudioWriter(
            writeFrame = {
                enteredWrite.countDown()
                // Model a native socket write that ignores Thread interrupt until transport closes.
                while (unblock.count > 0) try { unblock.await() } catch (_: InterruptedException) {}
            },
            closeTransport = { unblock.countDown(); closed.countDown() },
            maxBufferedBytes = 4,
        )
        val healthy = BoundedAudioWriter({ healthyReceived.countDown() }, {})
        try {
            assertTrue(slow.offer(byteArrayOf(1)))
            assertTrue(enteredWrite.await(2, TimeUnit.SECONDS))
            assertTrue(slow.offer(byteArrayOf(2, 3, 4, 5)))
            assertFalse(slow.offer(byteArrayOf(6))) // bounded overflow closes only the slow listener
            assertTrue(healthy.offer(byteArrayOf(7)))
            assertTrue(healthyReceived.await(2, TimeUnit.SECONDS))
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertFalse(slow.offer(byteArrayOf(8)))
        } finally { slow.close(); healthy.close(); unblock.countDown() }
    }

    @Test fun `frames stay in order and the transport closes exactly once`() {
        val framesReceived = CountDownLatch(3)
        val closeFinished = CountDownLatch(1)
        val closes = AtomicInteger()
        val received = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val writer = BoundedAudioWriter(
            writeFrame = { received.add(it.single().toInt()); framesReceived.countDown() },
            closeTransport = { closes.incrementAndGet(); closeFinished.countDown() },
        )
        try {
            for (value in 1..3) assertTrue(writer.offer(byteArrayOf(value.toByte())))
            assertTrue(framesReceived.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(1, 2, 3), received.toList())
            writer.close()
            writer.close()
            assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
            assertEquals(1, closes.get())
            assertFalse(writer.offer(byteArrayOf(4)))
        } finally { writer.close() }
    }

    @Test fun `write failure wakes connection owner without leaving a queued worker`() {
        val closeFinished = CountDownLatch(1)
        val writer = BoundedAudioWriter({ throw IOException("peer disconnected") }, { closeFinished.countDown() })
        try {
            assertTrue(writer.offer(byteArrayOf(1)))
            assertTrue(closeFinished.await(2, TimeUnit.SECONDS))
            assertFalse(writer.offer(byteArrayOf(2)))
        } finally { writer.close() }
    }

    @Test fun `oversized frame is rejected before any socket write`() {
        val writes = AtomicInteger()
        val closed = CountDownLatch(1)
        val writer = BoundedAudioWriter({ writes.incrementAndGet() }, { closed.countDown() }, maxBufferedBytes = 4)
        try {
            assertFalse(writer.offer(ByteArray(5)))
            assertTrue(closed.await(2, TimeUnit.SECONDS))
            assertEquals(0, writes.get())
        } finally { writer.close() }
    }
}
