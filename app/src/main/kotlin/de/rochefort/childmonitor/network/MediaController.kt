package de.rochefort.childmonitor.network

import de.rochefort.childmonitor.reliability.MonotonicClock

/**
 * RTT/loss-aware media controller that degrades gracefully before forcing audio-only fallback.
 * Implements CM-NET-006.
 *
 * Policy ladder:
 *  FULL         – video snapshots at peak cadence, audio at normal bitrate
 *  REDUCED      – video snapshots at reduced cadence, audio at normal bitrate
 *  AUDIO_FIRST  – video snapshots at slow cadence, audio protected
 *  AUDIO_ONLY   – video disabled, audio maintained
 */
class MediaController(
    private val nowMs: () -> Long = MonotonicClock::nowMs
) {

    enum class Level { FULL, REDUCED, AUDIO_FIRST, AUDIO_ONLY }

    data class Observation(
        val rttMs: Int,
        val lossPct: Double,
        val audioQueueDepthMs: Int
    )

    data class Policy(
        val level: Level,
        val snapshotIntervalMs: Int,
        val audioQualityBitsPerSecond: Int
    )

    @Volatile private var current: Level = Level.FULL
    @Volatile private var lastChangeMs: Long = 0L
    private val dwellMs: Long = 4_000L

    fun apply(obs: Observation): Policy {
        val now = nowMs()
        val desired = classify(obs)
        if (desired != current && now - lastChangeMs > dwellMs) {
            current = desired
            lastChangeMs = now
        }
        return policyFor(current)
    }

    private fun classify(obs: Observation): Level = when {
        obs.lossPct > 10.0 || obs.rttMs > 700 || obs.audioQueueDepthMs > 500 -> Level.AUDIO_ONLY
        obs.lossPct > 4.0 || obs.rttMs > 400 -> Level.AUDIO_FIRST
        obs.lossPct > 1.5 || obs.rttMs > 200 -> Level.REDUCED
        else -> Level.FULL
    }

    private fun policyFor(level: Level): Policy = when (level) {
        Level.FULL -> Policy(Level.FULL, 1_000, 64_000)
        Level.REDUCED -> Policy(Level.REDUCED, 2_500, 32_000)
        Level.AUDIO_FIRST -> Policy(Level.AUDIO_FIRST, 5_000, 24_000)
        Level.AUDIO_ONLY -> Policy(Level.AUDIO_ONLY, 0, 16_000)
    }

    fun currentLevel(): Level = current
}
