package de.rochefort.childmonitor.events

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoundEventDetectorTest {

    @Test fun `loud sustained region is reported`() {
        val det = SoundEventDetector(sampleRate = 8000, frameMs = 20)
        // 400 Hz square wave (sign flips every 10 samples at 8 kHz): ZCR ~0.1,
        // inside the detector's 0.05-0.45 vocal gate. A per-sample alternation
        // is a ~4 kHz tone with ZCR ~1.0, which the detector correctly rejects.
        val loudFrame = ShortArray(160) { (if ((it / 10) % 2 == 0) 6_000 else -6_000).toShort() }
        val quietFrame = ShortArray(160) { 20 }
        var event: SoundEventDetector.Event? = null
        // Warm up
        repeat(5) { det.process(quietFrame, quietFrame.size, it * 20L) }
        repeat(60) { event = event ?: det.process(loudFrame, loudFrame.size, (100 + it) * 20L) }
        val trailing = det.process(quietFrame, quietFrame.size, 10_000L)
        val emitted = event ?: trailing
        assertNotNull(emitted)
        assertTrue(emitted.peakDb > 50)
    }

    @Test fun `quiet input does not emit events`() {
        val det = SoundEventDetector(sampleRate = 8000, frameMs = 20)
        val quiet = ShortArray(160) { 10 }
        var emitted: SoundEventDetector.Event? = null
        for (i in 0 until 200) {
            emitted = det.process(quiet, quiet.size, i * 20L) ?: emitted
        }
        assertTrue(emitted == null)
    }

    // Run a loud region of `frames` frames at amplitude `amp` (square wave, sign flips every
    // `squarePeriod` samples → vocal-range ZCR for period 10), bracketed by quiet, return the event.
    private fun runRegion(amp: Int, frames: Int, squarePeriod: Int = 10): SoundEventDetector.Event? {
        val det = SoundEventDetector(sampleRate = 8000, frameMs = 20)
        val loud = ShortArray(160) { (if ((it / squarePeriod) % 2 == 0) amp else -amp).toShort() }
        val quiet = ShortArray(160) { 10 }
        var ev: SoundEventDetector.Event? = null
        repeat(5) { det.process(quiet, quiet.size, it * 20L) }
        repeat(frames) { i -> ev = ev ?: det.process(loud, loud.size, (100 + i) * 20L) }
        return ev ?: det.process(quiet, quiet.size, (100 + frames) * 20L)
    }

    @Test fun `loud long region classifies as cry`() {
        val e = runRegion(amp = 6500, frames = 100) // peakDb ~76 (>75), ~2000ms (>1500)
        assertNotNull(e)
        assertEquals("cry", e!!.type)
        assertTrue(e.confidence > 0.6)
    }

    @Test fun `loud short region classifies as cough`() {
        val e = runRegion(amp = 3500, frames = 40) // peakDb ~71 (65..75), ~800ms (<900)
        assertNotNull(e)
        assertEquals("cough", e!!.type)
    }

    @Test fun `medium region classifies as generic noise`() {
        val e = runRegion(amp = 1500, frames = 60) // peakDb ~63 (<65) → not cry/cough
        assertNotNull(e)
        assertEquals("noise", e!!.type)
    }

    @Test fun `high-frequency hiss outside the vocal ZCR gate is ignored`() {
        // per-sample alternation ≈ 4 kHz, ZCR ~1.0, outside 0.05..0.45 → never "loud"
        val e = runRegion(amp = 6500, frames = 100, squarePeriod = 1)
        assertNull(e)
    }

    @Test fun `region shorter than the minimum is not reported`() {
        val e = runRegion(amp = 6500, frames = 10) // ~200ms < 400ms floor
        assertNull(e)
    }

    @Test fun `never-ending loud input force-closes and re-arms (no stuck region)`() {
        val det = SoundEventDetector(sampleRate = 8000, frameMs = 20)
        val loud = ShortArray(160) { (if ((it / 10) % 2 == 0) 6500 else -6500).toShort() }
        val quiet = ShortArray(160) { 10 }
        repeat(5) { det.process(quiet, quiet.size, it * 20L) }
        // > EVENT_MAX_MS (20s = 1000 frames) of continuous loud: must force-close + emit, not wedge.
        var forced: SoundEventDetector.Event? = null
        repeat(1100) { i -> forced = forced ?: det.process(loud, loud.size, (100 + i) * 20L) }
        assertNotNull(forced)
        // and the detector must have re-armed: a fresh loud burst after quiet emits again.
        repeat(3) { det.process(quiet, quiet.size, (2000 + it) * 20L) }
        var again: SoundEventDetector.Event? = null
        repeat(100) { i -> again = again ?: det.process(loud, loud.size, (3000 + i) * 20L) }
        val trailing = det.process(quiet, quiet.size, 3200 * 20L)
        assertNotNull(again ?: trailing)
    }
}
