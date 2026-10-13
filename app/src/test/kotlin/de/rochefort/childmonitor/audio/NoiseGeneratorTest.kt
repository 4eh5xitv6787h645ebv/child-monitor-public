package de.rochefort.childmonitor.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** #311: noise synthesis — bounds, silence/scaling, and that colouring actually shapes the spectrum. */
class NoiseGeneratorTest {

    private fun gen(sound: SoothingSound, amp: Float, n: Int = 8192, seed: Long = 42L): ShortArray {
        val out = ShortArray(n)
        NoiseGenerator.fill(out, sound, amp, Random(seed), NoiseFilter())
        return out
    }

    @Test fun whiteNoiseIsBoundedAndNotConstant() {
        val s = gen(SoothingSound.WHITE, 0.8f)
        // Short range is guaranteed by the type; check it actually varies (not silence/DC).
        val distinct = s.toSet().size
        assertTrue("expected varied samples, got $distinct distinct", distinct > 100)
    }

    @Test fun zeroAmplitudeIsSilent() {
        val s = gen(SoothingSound.PINK, 0f)
        assertTrue("amplitude 0 must be pure silence", s.all { it.toInt() == 0 })
    }

    @Test fun higherAmplitudeIsLouder() {
        val quiet = peak(gen(SoothingSound.WHITE, 0.2f))
        val loud = peak(gen(SoothingSound.WHITE, 0.9f))
        assertTrue("louder amplitude should raise the peak ($quiet vs $loud)", loud > quiet)
    }

    @Test fun colouringReducesHighFrequencyContent() {
        // Mean absolute first-difference is a proxy for high-frequency energy: white is jagged,
        // pink is smoother, brown smoothest. This verifies the filters actually colour the noise.
        val white = meanAbsDiff(gen(SoothingSound.WHITE, 0.8f))
        val pink = meanAbsDiff(gen(SoothingSound.PINK, 0.8f))
        val brown = meanAbsDiff(gen(SoothingSound.BROWN, 0.8f))
        assertTrue("pink should be smoother than white ($pink < $white)", pink < white)
        assertTrue("brown should be smoother than pink ($brown < $pink)", brown < pink)
    }

    @Test fun deterministicForSameSeed() {
        assertTrue(gen(SoothingSound.PINK, 0.7f).contentEquals(gen(SoothingSound.PINK, 0.7f)))
    }

    private fun peak(s: ShortArray): Int = s.maxOf { abs(it.toInt()) }

    private fun meanAbsDiff(s: ShortArray): Double {
        var sum = 0.0
        for (i in 1 until s.size) sum += abs(s[i].toInt() - s[i - 1].toInt())
        return sum / (s.size - 1)
    }
}
