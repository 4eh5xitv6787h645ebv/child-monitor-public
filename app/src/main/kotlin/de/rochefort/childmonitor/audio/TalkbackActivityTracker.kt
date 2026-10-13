package de.rochefort.childmonitor.audio

import de.rochefort.childmonitor.reliability.MonotonicClock

/** Tracks actual audio packets across parent sockets, including both web-server listeners.
 * An open but silent socket cannot hold the nursery microphone muted indefinitely.
 */
internal class TalkbackActivityTracker(
    private val idleTimeoutMs: Long = 1_500L,
    private val nowMs: () -> Long = MonotonicClock::nowMs,
) {
    private val lastFrames = mutableMapOf<Any, Long>()

    init { require(idleTimeoutMs > 0) }

    @Synchronized fun markFrame(source: Any) { lastFrames[source] = nowMs() }
    @Synchronized fun remove(source: Any) { lastFrames.remove(source) }
    @Synchronized fun clear() { lastFrames.clear() }

    @Synchronized fun hasActiveFrames(): Boolean {
        val now = nowMs()
        lastFrames.entries.removeAll { now - it.value >= idleTimeoutMs }
        return lastFrames.isNotEmpty()
    }
}
