package de.rochefort.childmonitor.events

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device sound/cry event detector v1 and v2 baseline.
 * Implements CM-ALT-001 (v1) and CM-EVT-002 (v2 classifier baseline).
 *
 * Heuristic: detect sustained high-energy bands characteristic of a baby cry:
 *   - RMS above adaptive threshold
 *   - High zero-crossing rate consistent with vocal pitch (200-600 Hz)
 *   - Duration between 400 ms and 20 s
 *   - Emits one event per contiguous "loud" region
 *
 * This is NOT an ML classifier; it is a deterministic baseline that can feed
 * confidence scores. CM-EVT-002's full classifier can replace the scoreFrame
 * implementation without changing any downstream consumers.
 */
class SoundEventDetector(
    private val sampleRate: Int = 8_000,
    private val frameMs: Int = 20
) {

    data class Event(
        val type: String,
        val startMs: Long,
        val durationMs: Long,
        val peakDb: Double,
        val confidence: Double
    )

    private val frameSize: Int = sampleRate * frameMs / 1000
    private var adaptiveRms: Double = 300.0
    private var inRegion: Boolean = false
    private var regionStartMs: Long = 0
    private var regionPeakDb: Double = -90.0
    private val listeners = mutableListOf<(Event) -> Unit>()

    fun addListener(l: (Event) -> Unit) { listeners.add(l) }

    fun process(samples: ShortArray, length: Int, timestampMs: Long): Event? {
        if (length < frameSize) return null
        val rms = rms(samples, length)
        val zcr = zeroCrossingRate(samples, length)
        val db = 20 * log10(max(rms, 1.0))

        val isLoud = rms > max(adaptiveRms * 2.0, 500.0) && zcr in 0.05..0.45
        // #286: only track the ambient baseline while NOT in a loud region. Previously the EWMA ran
        // every frame, so a sustained cry was absorbed into the baseline in ~0.6s — the region ended
        // early and the >1500ms "cry" band was effectively unreachable (cries misclassified as
        // cough/noise). Freezing during a region lets a real cry sustain to its true duration.
        if (!isLoud) {
            adaptiveRms = adaptiveRms + (rms - adaptiveRms) * 0.02
        }

        if (isLoud) {
            if (!inRegion) {
                inRegion = true
                regionStartMs = timestampMs
                regionPeakDb = db
            } else if (db > regionPeakDb) {
                regionPeakDb = db
            }
            // #286: force-close an over-long region. Because the baseline is frozen while loud, a
            // permanently-loud input would otherwise never hit a not-loud frame and would wedge the
            // detector forever (EVENT_MAX_MS is only checked at close), silently killing all later
            // alerts. Cap the reported duration at EVENT_MAX_MS so it emits and re-arms.
            if (timestampMs - regionStartMs >= EVENT_MAX_MS) {
                return closeRegion(regionStartMs + EVENT_MAX_MS)
            }
            return null
        }
        if (!inRegion) return null
        return closeRegion(timestampMs)
    }

    /** Close the current loud region, emitting an Event when its duration is in the valid band. */
    private fun closeRegion(endMs: Long): Event? {
        val duration = endMs - regionStartMs
        inRegion = false
        if (duration !in EVENT_MIN_MS..EVENT_MAX_MS) return null
        val event = Event(
            type = classifyType(duration, regionPeakDb),
            startMs = regionStartMs,
            durationMs = duration,
            peakDb = regionPeakDb,
            confidence = confidenceFor(duration, regionPeakDb)
        )
        for (l in listeners) {
            try { l(event) } catch (_: Exception) { }
        }
        return event
    }

    private fun confidenceFor(durationMs: Long, peakDb: Double): Double {
        val durationScore = when {
            durationMs < 800 -> 0.4
            durationMs < 4000 -> 0.8
            durationMs < 12000 -> 0.95
            else -> 0.85
        }
        val loudnessScore = ((peakDb - 40.0) / 30.0).coerceIn(0.1, 1.0)
        return min(1.0, durationScore * 0.6 + loudnessScore * 0.4)
    }

    private fun classifyType(durationMs: Long, peakDb: Double): String = when {
        peakDb > 75 && durationMs > 1500 -> "cry"
        peakDb > 65 && durationMs < 900 -> "cough"
        else -> "noise"
    }

    private fun rms(samples: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / length)
    }

    private fun zeroCrossingRate(samples: ShortArray, length: Int): Double {
        var crossings = 0
        for (i in 1 until length) {
            if ((samples[i] >= 0) != (samples[i - 1] >= 0)) crossings++
        }
        return crossings.toDouble() / length
    }

    companion object {
        private const val EVENT_MIN_MS = 400L
        private const val EVENT_MAX_MS = 20_000L
    }
}
