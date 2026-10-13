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

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * #328: synthesises a [Lullaby] melody to PCM for the soother. One instance per playback session;
 * note position + sine phase are carried across [fill] calls so streamed chunks join seamlessly and
 * the tune loops cleanly. Pure (no Android deps) and deterministic, so it's unit-testable.
 *
 * Each note is a soft sine with a short raised-cosine attack/release envelope (no clicks) and a
 * small tail gap so repeated notes are articulated. Synthesised below full scale (gentle for a crib);
 * AudioTrack.setVolume applies the user's loudness on top, like the noise path.
 */
class MelodySynth(private val notes: List<MelodyNote>, bpm: Int, private val sampleRate: Int) {

    /** Convenience: synthesise a named [Lullaby]. */
    constructor(melody: Lullaby, sampleRate: Int) : this(melody.notes, melody.bpm, sampleRate)

    private val samplesPerBeat = (sampleRate * 60.0 / bpm.coerceAtLeast(1)).toInt().coerceAtLeast(1)
    // Per-note length in samples (>=1). The tune loops via `noteIdx % notes.size` at note boundaries.
    private val noteLen = IntArray(notes.size) { (notes[it].beats * samplesPerBeat).toInt().coerceAtLeast(1) }

    // ~12ms attack/release envelope; a ~8% tail gap articulates consecutive equal-pitch notes.
    private val envSamples = (sampleRate * 0.012).toInt().coerceAtLeast(1)

    private var noteIdx = 0
    private var samplesIntoNote = 0
    private var phase = 0.0  // radians, continuous within a note

    /** Fill [out] with the next chunk of the melody at linear [amplitude] (0..1). */
    fun fill(out: ShortArray, amplitude: Float) {
        val amp = amplitude.coerceIn(0f, 1f) * NOTE_PEAK
        for (i in out.indices) {
            if (notes.isEmpty()) { out[i] = 0; continue }
            val len = noteLen[noteIdx]
            val note = notes[noteIdx]
            val sampleVal: Float = if (note.isRest) {
                0f
            } else {
                val freq = midiToFreq(note.midi)
                phase += 2.0 * PI * freq / sampleRate
                if (phase > 2.0 * PI) phase -= 2.0 * PI
                (sin(phase).toFloat()) * envelope(samplesIntoNote, len)
            }
            out[i] = (sampleVal * amp * Short.MAX_VALUE)
                .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

            // Advance position; wrap the note, and the whole tune.
            samplesIntoNote++
            if (samplesIntoNote >= len) {
                samplesIntoNote = 0
                phase = 0.0
                noteIdx = (noteIdx + 1) % notes.size
            }
        }
    }

    /** Raised-cosine attack/release + a short tail gap, all within the note's length. */
    private fun envelope(pos: Int, len: Int): Float {
        val gap = (len * GAP_FRACTION).toInt()
        val sounded = (len - gap).coerceAtLeast(1)
        if (pos >= sounded) return 0f                       // tail gap → silence (articulation)
        val env = envSamples.coerceAtMost(sounded / 2).coerceAtLeast(1)
        return when {
            pos < env -> pos.toFloat() / env                // attack 0→1
            pos >= sounded - env -> (sounded - pos).toFloat() / env  // release 1→0
            else -> 1f
        }
    }

    companion object {
        /** Peak amplitude of the synthesised sine before the user volume — gentle for a crib. */
        const val NOTE_PEAK = 0.35f
        private const val GAP_FRACTION = 0.08f

        /** MIDI note number → frequency in Hz (A4=69=440Hz, equal temperament). */
        fun midiToFreq(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)
    }
}
