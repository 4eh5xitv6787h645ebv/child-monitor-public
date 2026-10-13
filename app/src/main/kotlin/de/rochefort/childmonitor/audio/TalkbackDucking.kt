package de.rochefort.childmonitor.audio

/**
 * Two-way talkback ducking and sidetone control.
 * Implements CM-AUD-015: Two-way talkback ducking and sidetone control.
 *
 * When the parent is pushing audio to the child, the child reduces the gain of
 * the microphone stream it sends back so the parent does not hear a looped echo
 * of their own voice. A tiny sidetone (≈10%) is mixed back so the parent feels
 * the PTT is live.
 */
class TalkbackDucking(
    private val duckRatio: Float = 0.10f,
    private val sidetoneRatio: Float = 0.10f,
    private val attackMs: Int = 80,
    private val releaseMs: Int = 350,
    private val sampleRate: Int = 8000
) {

    @Volatile private var isActive: Boolean = false
    private var gain: Float = 1.0f
    private var attackCoeff: Float = computeCoeff(attackMs)
    private var releaseCoeff: Float = computeCoeff(releaseMs)

    fun setActive(active: Boolean) {
        isActive = active
    }

    fun active(): Boolean = isActive

    fun applyDucking(micBuffer: ShortArray, length: Int) {
        val target = if (isActive) duckRatio else 1.0f
        val coeff = if (target < gain) attackCoeff else releaseCoeff
        for (i in 0 until length) {
            gain += (target - gain) * coeff
            val out = (micBuffer[i] * gain).toInt()
            micBuffer[i] = out
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    fun mixSidetone(talkback: ShortArray, length: Int, into: ShortArray) {
        val ratio = sidetoneRatio
        val n = minOf(length, into.size, talkback.size)
        for (i in 0 until n) {
            val mixed = into[i].toInt() + (talkback[i] * ratio).toInt()
            into[i] = mixed
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun computeCoeff(ms: Int): Float {
        val frames = (ms * sampleRate / 1000).coerceAtLeast(1)
        return 1.0f / frames.toFloat()
    }
}
