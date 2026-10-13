package de.rochefort.childmonitor.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Loudness normalization + compressor/limiter for quiet baby sounds.
 * Implements CM-AUD-010: Loudness normalization + compressor/limiter.
 *
 * Operates on 16-bit PCM in-place. Targets ~-18 LUFS equivalent RMS and applies
 * a soft-knee compressor with a hard limiter above the peak threshold.
 */
class LoudnessNormalizer(
    private val targetRms: Double = 5200.0,
    private val minGain: Float = 1.0f,
    private val maxGain: Float = 5.0f,
    private val peakLimit: Float = 30_500.0f,
    private val attackCoeff: Float = 0.18f,
    private val releaseCoeff: Float = 0.08f,
    private val noiseFloorRms: Double = 150.0
) {

    // Start neutral at the configured floor: amplifying before the first RMS
    // measurement exists would boost connect-time hiss (and the old 2.0 start
    // did exactly that for the first ~second of audio).
    private var smoothedGain: Float = minGain

    /**
     * Applies gain and limiting to the first [length] samples of [buffer].
     * Returns the applied gain for logging.
     */
    fun process(buffer: ShortArray, length: Int, userMultiplier: Float = 1.0f): Float {
        if (length <= 0) return smoothedGain

        var peak = 0
        var energy = 0.0
        for (i in 0 until length) {
            val v = abs(buffer[i].toInt())
            if (v > peak) peak = v
            energy += v.toDouble() * v.toDouble()
        }
        val rms = sqrt(energy / length.toDouble())

        val desiredGain = when {
            rms <= 0.0 -> maxGain
            rms < noiseFloorRms -> minGain
            else -> (targetRms / rms).toFloat().coerceIn(minGain, maxGain)
        }
        smoothedGain = smoothTowards(smoothedGain, desiredGain)
        val cappedByLimiter = if (peak > 0) {
            min(maxGain * userMultiplier, peakLimit / peak.toFloat())
        } else {
            maxGain * userMultiplier
        }
        val applied = min(smoothedGain * userMultiplier, cappedByLimiter).coerceAtLeast(minGain)
        for (i in 0 until length) {
            val amplified = (buffer[i] * applied).toInt()
            val compressed = softClip(amplified)
            buffer[i] = compressed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return applied
    }

    private fun softClip(x: Int): Int {
        val hard = Short.MAX_VALUE.toInt() - 256
        return when {
            x > hard -> hard + ((x - hard) / 4)
            x < -hard -> -hard + ((x + hard) / 4)
            else -> x
        }
    }

    private fun smoothTowards(cur: Float, target: Float): Float {
        val coeff = if (target > cur) attackCoeff else releaseCoeff
        return cur + (target - cur) * coeff
    }

    fun gain(): Float = smoothedGain
}
