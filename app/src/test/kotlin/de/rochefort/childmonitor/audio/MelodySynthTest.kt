package de.rochefort.childmonitor.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #328: pure tests for the lullaby tone synthesiser. */
class MelodySynthTest {

    private fun synth(notes: List<MelodyNote>, bpm: Int = 120, sr: Int = 100) = MelodySynth(notes, bpm, sr)

    @Test fun midiToFreq_matchesEqualTemperament() {
        assertEquals(440.0, MelodySynth.midiToFreq(69), 0.001)   // A4
        assertEquals(880.0, MelodySynth.midiToFreq(81), 0.001)   // A5 (octave up)
        assertEquals(220.0, MelodySynth.midiToFreq(57), 0.001)   // A3 (octave down)
        assertEquals(261.6256, MelodySynth.midiToFreq(60), 0.001) // middle C
    }

    @Test fun pitchedMelodyProducesNonSilentBoundedOutput() {
        val s = synth(listOf(MelodyNote(69, 1f), MelodyNote(72, 1f)))
        val out = ShortArray(400)
        s.fill(out, 1f)
        assertTrue("expected some non-zero samples", out.any { it.toInt() != 0 })
        // NOTE_PEAK keeps it below full scale.
        val peak = (Short.MAX_VALUE * MelodySynth.NOTE_PEAK).toInt() + 2
        assertTrue("samples within gentle range", out.all { kotlin.math.abs(it.toInt()) <= peak })
    }

    @Test fun restOnlyMelodyIsSilent() {
        val s = synth(listOf(MelodyNote(MelodyNote.REST, 2f)))
        val out = ShortArray(500)
        s.fill(out, 1f)
        assertTrue("rest must be silent", out.all { it.toInt() == 0 })
    }

    @Test fun emptyMelodyIsSilent() {
        val s = synth(emptyList())
        val out = ShortArray(128)
        s.fill(out, 1f)
        assertTrue(out.all { it.toInt() == 0 })
    }

    @Test fun outputIsDeterministic() {
        val notes = listOf(MelodyNote(60, 1f), MelodyNote(64, 1f), MelodyNote(67, 1f))
        val a = ShortArray(600).also { synth(notes).fill(it, 1f) }
        val b = ShortArray(600).also { synth(notes).fill(it, 1f) }
        assertTrue(a.contentEquals(b))
    }

    @Test fun loopsExactlyEveryTotalSamples() {
        // sr=100, bpm=120 → samplesPerBeat = 50. Two quarter notes → total = 100 samples.
        val s = synth(listOf(MelodyNote(60, 1f), MelodyNote(67, 1f)), bpm = 120, sr = 100)
        val first = ShortArray(100).also { s.fill(it, 1f) }   // one full period
        val second = ShortArray(100).also { s.fill(it, 1f) }  // next period — identical state
        assertTrue("melody should loop seamlessly (period == total samples)", first.contentEquals(second))
    }

    @Test fun amplitudeZeroIsSilentAndScalesDown() {
        val notes = listOf(MelodyNote(69, 1f))
        val silent = ShortArray(200).also { synth(notes).fill(it, 0f) }
        assertTrue(silent.all { it.toInt() == 0 })
        val quiet = ShortArray(200).also { synth(notes).fill(it, 0.5f) }
        val loud = ShortArray(200).also { synth(notes).fill(it, 1f) }
        assertNotEquals(0, loud.maxOf { kotlin.math.abs(it.toInt()) })
        assertTrue("lower amplitude → smaller peak", quiet.maxOf { kotlin.math.abs(it.toInt()) } <= loud.maxOf { kotlin.math.abs(it.toInt()) })
    }
}
