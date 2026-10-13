package de.rochefort.childmonitor.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Packet loss concealment with gain decay and comfort-noise floor.
 * Implements CM-AUD-005 (FEC/PLC policy) and CM-AUD-016 (comfort-noise synthesis).
 *
 * Concealment strategy per lost frame:
 *  1. Copy previous frame with decaying gain (95% per frame).
 *  2. After 3+ consecutive losses, blend toward low-amplitude pink-ish noise.
 *  3. After 12 losses, output near-silence to avoid audible buzz.
 */
class PacketLossConcealer(private val frameSize: Int) {

    private val random = java.util.Random()
    private var lastFrame: ShortArray? = null
    private var consecutiveLosses: Int = 0
    private var gain: Float = 1.0f

    fun onPacketReceived(samples: ShortArray) {
        lastFrame = samples.copyOf()
        consecutiveLosses = 0
        gain = 1.0f
    }

    fun concealLostPacket(): ShortArray {
        consecutiveLosses++
        gain = max(0.05f, gain * DECAY_PER_FRAME)

        val last = lastFrame
        val out = ShortArray(frameSize)
        when {
            last == null -> {
                fillComfortNoise(out, amplitude = (COMFORT_NOISE_AMPLITUDE * 0.5f).toInt())
            }
            consecutiveLosses < 3 -> {
                for (i in 0 until min(out.size, last.size)) {
                    out[i] = (last[i] * gain).toInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            consecutiveLosses < 12 -> {
                val mix = (consecutiveLosses - 2).coerceIn(1, 10) / 10.0f
                for (i in 0 until min(out.size, last.size)) {
                    val base = (last[i] * gain * (1.0f - mix)).toInt()
                    val noise = (COMFORT_NOISE_AMPLITUDE * mix * (random.nextFloat() - 0.5f)).toInt()
                    out[i] = (base + noise)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            else -> {
                fillComfortNoise(out, amplitude = (COMFORT_NOISE_AMPLITUDE * 0.25f).toInt())
            }
        }
        return out
    }

    private fun fillComfortNoise(dst: ShortArray, amplitude: Int) {
        val amp = abs(amplitude).coerceAtMost(Short.MAX_VALUE.toInt())
        for (i in dst.indices) {
            val v = ((random.nextFloat() - 0.5f) * 2 * amp).toInt()
            dst[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun lossCount(): Int = consecutiveLosses

    companion object {
        private const val DECAY_PER_FRAME = 0.95f
        private const val COMFORT_NOISE_AMPLITUDE = 120
    }
}
