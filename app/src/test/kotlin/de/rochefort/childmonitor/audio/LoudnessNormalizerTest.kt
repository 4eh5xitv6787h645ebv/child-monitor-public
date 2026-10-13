package de.rochefort.childmonitor.audio

import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class LoudnessNormalizerTest {

    @Test fun `boosts quiet audio up to target`() {
        val norm = LoudnessNormalizer(targetRms = 5000.0, minGain = 1.0f, maxGain = 5.0f)
        val samples = ShortArray(800) { 400 }
        norm.process(samples, samples.size)
        val max = samples.maxOf { abs(it.toInt()) }
        assertTrue(max > 400)
    }

    @Test fun `does not clip loud audio`() {
        val norm = LoudnessNormalizer(targetRms = 5000.0, minGain = 1.0f, maxGain = 5.0f, peakLimit = 30_000.0f)
        val samples = ShortArray(800) { Short.MAX_VALUE.toInt().toShort() }
        norm.process(samples, samples.size)
        val max = samples.maxOf { abs(it.toInt()) }
        assertTrue(max <= Short.MAX_VALUE.toInt())
    }

    @Test fun `below noise floor uses min gain`() {
        val norm = LoudnessNormalizer(targetRms = 5000.0, minGain = 1.0f, maxGain = 5.0f, noiseFloorRms = 200.0)
        val samples = ShortArray(800) { 50 }
        val gain = norm.process(samples, samples.size)
        assertTrue(gain <= 1.1f)
    }
}
