/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor.audio

/**
 * #328: a single note (or rest) in a procedurally-synthesised melody.
 * [midi] is a MIDI note number (60 = middle C, 69 = A4 440 Hz) or [REST] for silence;
 * [beats] is its length in beats (1.0 = quarter note at the tune's tempo).
 */
data class MelodyNote(val midi: Int, val beats: Float) {
    val isRest: Boolean get() = midi == REST
    companion object { const val REST = -1 }
}

// File-scope note builders. These MUST live outside the enum: enum entries are constructed before
// the enum's own companion object is initialised, so referencing a companion helper from an entry's
// constructor throws "companion object is uninitialized". Top-level functions are available first.
private const val Q = 1.0f   // quarter note
private const val H = 2.0f   // half note
private const val W = 4.0f   // whole note
private fun nt(midi: Int, beats: Float) = MelodyNote(midi, beats)

// C major around middle C: C4=60 D4=62 E4=64 F4=65 G4=67 A4=69 B4=71 C5=72; G3=55.
private fun twinkleNotes(): List<MelodyNote> = listOf(
    // Twinkle twinkle little star / how I wonder what you are
    nt(60, Q), nt(60, Q), nt(67, Q), nt(67, Q), nt(69, Q), nt(69, Q), nt(67, H),
    nt(65, Q), nt(65, Q), nt(64, Q), nt(64, Q), nt(62, Q), nt(62, Q), nt(60, H),
    // Up above the world so high / like a diamond in the sky
    nt(67, Q), nt(67, Q), nt(65, Q), nt(65, Q), nt(64, Q), nt(64, Q), nt(62, H),
    nt(67, Q), nt(67, Q), nt(65, Q), nt(65, Q), nt(64, Q), nt(64, Q), nt(62, H),
)

private fun frereJacquesNotes(): List<MelodyNote> = listOf(
    nt(60, Q), nt(62, Q), nt(64, Q), nt(60, Q),   // Frère Jacques
    nt(60, Q), nt(62, Q), nt(64, Q), nt(60, Q),
    nt(64, Q), nt(65, Q), nt(67, H),              // Dormez-vous
    nt(64, Q), nt(65, Q), nt(67, H),
    nt(67, Q), nt(69, Q), nt(67, Q), nt(65, Q), nt(64, Q), nt(60, Q),  // Sonnez les matines
    nt(67, Q), nt(69, Q), nt(67, Q), nt(65, Q), nt(64, Q), nt(60, Q),
    nt(60, Q), nt(55, Q), nt(60, H),              // Din dan don
    nt(60, Q), nt(55, Q), nt(60, H),
)

private fun maryNotes(): List<MelodyNote> = listOf(
    nt(64, Q), nt(62, Q), nt(60, Q), nt(62, Q), nt(64, Q), nt(64, Q), nt(64, H),
    nt(62, Q), nt(62, Q), nt(62, H),
    nt(64, Q), nt(67, Q), nt(67, H),
    nt(64, Q), nt(62, Q), nt(60, Q), nt(62, Q), nt(64, Q), nt(64, Q), nt(64, Q), nt(64, Q),
    nt(62, Q), nt(62, Q), nt(64, Q), nt(62, Q), nt(60, W),
)

/**
 * #328: gentle public-domain nursery melodies for the soother, encoded as note lists and
 * synthesised on the fly (see [MelodySynth]) — no bundled audio, so no assets, licensing, or APK
 * size cost. All are traditional/public-domain tunes in C major; tempos are intentionally slow.
 * Looped seamlessly by the player.
 */
enum class Lullaby(val bpm: Int, val notes: List<MelodyNote>) {
    TWINKLE(92, twinkleNotes()),
    FRERE_JACQUES(96, frereJacquesNotes()),
    MARY(100, maryNotes()),
}
