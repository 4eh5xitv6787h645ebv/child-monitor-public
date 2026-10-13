package de.rochefort.childmonitor.audio

/**
 * Drift-safe resampler + sample-rate negotiation helper.
 * Implements CM-AUD-013: Adaptive sample-rate negotiation + drift-safe resampling.
 *
 * `negotiate` picks the highest mutually supported sample rate from the supplied candidate
 * sets. `resampleMono16` performs linear interpolation between PCM16 samples and is
 * **streaming**: it carries both the fractional read position AND the last sample of the
 * previous block across calls, so blocks fed back-to-back resample as one continuous stream
 * with no per-block boundary glitch or whole-sample drift.
 */
class SampleRateNegotiator {

    companion object {
        val STANDARD_RATES: IntArray = intArrayOf(8000, 16000, 24000, 32000, 44100, 48000)

        fun negotiate(peerSupported: IntArray, ownSupported: IntArray = STANDARD_RATES): Int {
            val mine = ownSupported.toSet()
            val commonSorted = peerSupported.filter { it in mine }.sortedDescending()
            return commonSorted.firstOrNull() ?: 8000
        }
    }

    // [#243] Streaming state. `nextPos` is the read position for the next output sample in the
    // CURRENT block's index space (input[0] == index 0). At a block boundary it is slightly
    // negative, meaning the next sample lies between the previous block's last sample [tail]
    // and this block's input[0]. The previous code carried only a [0,1) fractional part, which
    // was off by exactly one source sample whenever the read position ended in
    // [inputLength-1, inputLength) — dropping a sample and inserting a periodic glitch/desync.
    private var nextPos: Double = 0.0
    private var tail: Short = 0
    private var haveTail: Boolean = false

    fun resampleMono16(
        input: ShortArray,
        inputLength: Int,
        inputRate: Int,
        output: ShortArray,
        outputRate: Int
    ): Int {
        if (inputLength <= 0) return 0
        if (inputRate == outputRate) {
            val n = minOf(inputLength, output.size)
            System.arraycopy(input, 0, output, 0, n)
            // Preserve continuity in case the rate changes on a later call.
            tail = input[n - 1]
            haveTail = true
            nextPos = 0.0
            return n
        }

        val ratio = inputRate.toDouble() / outputRate.toDouble()
        var written = 0
        var pos = nextPos
        while (written < output.size && pos < inputLength - 1) {
            val s0: Float
            val s1: Float
            val frac: Float
            if (pos < 0.0) {
                if (!haveTail) {
                    // No previous-block sample to interpolate from yet — start at block 0.
                    pos = 0.0
                    continue
                }
                // Boundary region: interpolate between the previous block's last sample and
                // this block's first sample (conceptual indices -1 and 0).
                s0 = tail.toFloat()
                s1 = input[0].toFloat()
                frac = (pos + 1.0).toFloat()
            } else {
                val i = pos.toInt()
                s0 = input[i].toFloat()
                s1 = input[i + 1].toFloat()
                frac = (pos - i).toFloat()
            }
            val v = (s0 * (1.0f - frac) + s1 * frac).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[written++] = v.toShort()
            pos += ratio
        }
        // Re-base the carried position into the NEXT block's index space: that block's input[0]
        // is this block's sample index [inputLength]. This is the single consistent carry that
        // fixes the boundary off-by-one. [#263] Clamp the negative side to -1.0: if the OUTPUT
        // buffer (not the input) was the binding limit, pos can exit far below inputLength, and
        // an unclamped carry would drive the next call into wild out-of-range extrapolation. A
        // floor of -1.0 keeps it in the boundary-interpolation region (positive overshoot from
        // downsampling is still valid). Sizing output to hold the whole resampled block avoids
        // this path entirely.
        nextPos = (pos - inputLength).coerceAtLeast(-1.0)
        tail = input[inputLength - 1]
        haveTail = true
        return written
    }

    fun reset() {
        nextPos = 0.0
        tail = 0
        haveTail = false
    }
}
