package de.rochefort.childmonitor.audio

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One bounded downstream per listener. Capture only enqueues immutable frames; a stalled socket
 * cannot block capture or another parent. Overflow closes that listener so it can reconnect near
 * live audio instead of replaying an ever-growing backlog. Closing never blocks the capture thread.
 */
internal class BoundedAudioWriter(
    private val writeFrame: (ByteArray) -> Unit,
    private val closeTransport: () -> Unit,
    private val maxBufferedBytes: Int = 8_000,
) : AutoCloseable {
    private val lock = Object()
    private val frames = ArrayDeque<ByteArray>()
    private var bufferedBytes = 0
    private val closed = AtomicBoolean(false)
    private val worker: Thread

    init {
        require(maxBufferedBytes > 0)
        worker = Thread(::writeLoop, "parent-audio-writer").apply { isDaemon = true; start() }
    }

    /** The caller owns [frame] until here and must never mutate it after offering it. */
    fun offer(frame: ByteArray): Boolean {
        if (frame.isEmpty()) return !closed.get()
        val accepted = synchronized(lock) {
            if (closed.get()) return false
            if (frame.size > maxBufferedBytes - bufferedBytes) false
            else {
                frames.addLast(frame)
                bufferedBytes += frame.size
                lock.notifyAll()
                true
            }
        }
        if (!accepted) close()
        return accepted
    }

    private fun writeLoop() {
        try {
            while (!closed.get()) {
                val frame = synchronized(lock) {
                    while (frames.isEmpty() && !closed.get()) lock.wait()
                    if (closed.get()) return
                    frames.removeFirst().also { bufferedBytes -= it.size }
                }
                writeFrame(frame)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            // Closing the transport wakes its connection owner, which logs/removes this parent.
        } finally { close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            frames.clear()
            bufferedBytes = 0
            lock.notifyAll()
        }
        worker.interrupt()
        // SSL socket close can itself wait on a blocked write. Never do it on capture or while
        // holding the parent roster lock. Exactly one closer can run for each admitted connection.
        Thread({ runCatching(closeTransport) }, "parent-audio-close").apply { isDaemon = true; start() }
    }
}
