package de.rochefort.childmonitor.audio

/** Mono PCM queue depth, including AudioTrack's unsigned 32-bit playback-head rollover. */
internal class PlaybackQueueTracker {
    private var written = 0L
    private var played = 0L
    private var previousHead = 0L

    fun recordWrite(frames: Int) {
        require(frames >= 0)
        written += frames
    }

    fun queuedFrames(playbackHead: Int): Long {
        val unsignedHead = playbackHead.toLong() and 0xffff_ffffL
        played += (unsignedHead - previousHead) and 0xffff_ffffL
        previousHead = unsignedHead
        return (written - played).coerceAtLeast(0L)
    }

    /** Call after a paused AudioTrack flush resets both the queue and playback head. */
    fun reset() {
        written = 0L
        played = 0L
        previousHead = 0L
    }
}
