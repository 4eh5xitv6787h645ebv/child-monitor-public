package de.rochefort.childmonitor.audio

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SampleRateNegotiatorTest {

    @Test fun `picks highest common rate`() {
        val rate = SampleRateNegotiator.negotiate(intArrayOf(8000, 16000, 48000))
        assertEquals(48000, rate)
    }

    @Test fun `falls back to 8000 when nothing common`() {
        val rate = SampleRateNegotiator.negotiate(intArrayOf(11025, 22050), intArrayOf(8000, 16000))
        assertEquals(8000, rate)
    }

    @Test fun `passes through when rates match`() {
        val n = SampleRateNegotiator()
        val input = ShortArray(160) { 1000 }
        val output = ShortArray(160)
        val written = n.resampleMono16(input, input.size, 8000, output, 8000)
        assertEquals(160, written)
        assertEquals(1000.toShort(), output.first())
    }

    @Test fun `upsamples more output samples than input`() {
        val n = SampleRateNegotiator()
        val input = ShortArray(100) { (it * 100).toShort() }
        val output = ShortArray(200)
        val written = n.resampleMono16(input, input.size, 8000, output, 16000)
        assertTrue(written > 100)
    }

    @Test fun `undersized output buffer across blocks never extrapolates out of input range`() {
        // [#263] regression: if the output buffer fills before the input block is consumed,
        // the carry must not drive a later call into wild out-of-range extrapolation.
        val n = SampleRateNegotiator()
        val inRate = 8000
        val outRate = 16000 // upsample → a full block wants ~2x the input in output
        val block = ShortArray(160) { ((it % 50) * 20).toShort() } // values within [0, 980]
        repeat(5) {
            val out = ShortArray(8) // far smaller than the block would produce
            val w = n.resampleMono16(block, block.size, inRate, out, outRate)
            for (k in 0 until w) {
                assertTrue(out[k].toInt() in -1..981, "out-of-range sample ${out[k]}")
            }
        }
    }

    @Test fun `streaming across blocks matches single-shot resample of a ramp (no boundary drift)`() {
        val inRate = 24000
        val outRate = 8000 // 3:1 downsample
        val total = 30
        val ramp = ShortArray(total) { it.toShort() } // 0,1,2,…,29

        // Single-shot reference over the whole ramp.
        val oneShot = SampleRateNegotiator()
        val refOut = ShortArray(total)
        val refN = oneShot.resampleMono16(ramp, total, inRate, refOut, outRate)

        // Same ramp fed in three back-to-back blocks of 10.
        val streamer = SampleRateNegotiator()
        val got = ArrayList<Short>()
        var off = 0
        while (off < total) {
            val len = minOf(10, total - off)
            val outBuf = ShortArray(len + 4)
            val nWritten = streamer.resampleMono16(ramp.copyOfRange(off, off + len), len, inRate, outBuf, outRate)
            for (k in 0 until nWritten) got.add(outBuf[k])
            off += len
        }

        // The previous off-by-one carry dropped a source sample at each block boundary, so the
        // streamed result lost samples and drifted from the single-shot reference. With the fix,
        // streaming must equal the single-shot result exactly.
        assertEquals(refN, got.size)
        for (k in 0 until refN) {
            assertEquals(refOut[k].toInt(), got[k].toInt(), "sample $k")
        }
    }
}
