package de.rochefort.childmonitor.audio

import org.junit.Assert.assertTrue
import org.junit.Test

/** #328: sanity-checks the bundled nursery-melody data. */
class LullabyTest {

    @Test fun everyTuneIsNonEmptyWithSaneTempoAndNotes() {
        for (tune in Lullaby.values()) {
            assertTrue("${tune.name} has notes", tune.notes.isNotEmpty())
            assertTrue("${tune.name} tempo sane", tune.bpm in 40..160)
            for (note in tune.notes) {
                assertTrue("${tune.name} positive beats", note.beats > 0f)
                // Either a rest, or a pitch in a gentle vocal/music-box range (C3..C6).
                assertTrue(
                    "${tune.name} midi ${note.midi} in range or rest",
                    note.isRest || note.midi in 48..84
                )
            }
        }
    }

    @Test fun tunesAreSynthesisableWithoutError() {
        // Each tune renders a chunk of non-silent audio (none is all-rests).
        for (tune in Lullaby.values()) {
            val out = ShortArray(2048)
            MelodySynth(tune, sampleRate = 8000).fill(out, 1f)
            assertTrue("${tune.name} produces sound", out.any { it.toInt() != 0 })
        }
    }
}
