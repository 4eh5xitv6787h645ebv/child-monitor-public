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

import java.util.Random

/**
 * #311: synthesises white/pink/brown noise PCM for the soother. Pure (no Android deps) and
 * deterministic for a given [Random] seed + [NoiseFilter], so it can be unit-tested by its
 * spectral character. The filter state is carried across [fill] calls so streamed chunks join
 * seamlessly (no audible loop seam).
 */
object NoiseGenerator {

    /**
     * Fill [out] with [sound] noise at linear [amplitude] (0..1). [rnd] supplies the white
     * source; [filter] colours it (and persists state for continuity). PINK/BROWN are
     * gain-compensated so their perceived loudness is in the same ballpark as WHITE.
     */
    fun fill(out: ShortArray, sound: SoothingSound, amplitude: Float, rnd: Random, filter: NoiseFilter) {
        val amp = amplitude.coerceIn(0f, 1f)
        for (i in out.indices) {
            val white = rnd.nextFloat() * 2f - 1f // [-1, 1)
            val v = when (sound) {
                SoothingSound.WHITE -> white
                SoothingSound.PINK -> filter.pink(white)
                SoothingSound.BROWN -> filter.brown(white)
                // #328 melodies (lullaby != null) are rendered by MelodySynth, not here. This branch
                // is only a defensive fallback if a melody sound ever reaches the noise path.
                else -> white
            }
            val sample = (v.coerceIn(-1f, 1f) * amp * Short.MAX_VALUE).toInt()
            out[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}

/**
 * Mutable colouring filter for pink/brown noise. One instance per playback session so the 1/f
 * (pink) and integrated (brown) state survives across chunk boundaries.
 */
class NoiseFilter {
    // Paul Kellet's economical pink-noise filter (approximates -3 dB/oct across the audio band).
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    // Brown (red) noise: leaky integrator of white.
    private var brownLast = 0f

    fun pink(white: Float): Float {
        b0 = 0.99886f * b0 + white * 0.0555179f
        b1 = 0.99332f * b1 + white * 0.0750759f
        b2 = 0.96900f * b2 + white * 0.1538520f
        b3 = 0.86650f * b3 + white * 0.3104856f
        b4 = 0.55000f * b4 + white * 0.5329522f
        b5 = -0.7616f * b5 - white * 0.0168980f
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362f
        b6 = white * 0.115926f
        return pink * 0.11f // scale roughly into [-1, 1]
    }

    fun brown(white: Float): Float {
        brownLast = (brownLast + 0.02f * white) / 1.02f
        return (brownLast * 3.5f) // gain-compensate the integrator's attenuation
    }
}
