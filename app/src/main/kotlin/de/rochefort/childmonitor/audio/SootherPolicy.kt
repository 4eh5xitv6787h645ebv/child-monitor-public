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

import de.rochefort.childmonitor.BedtimeTimerPolicy

/**
 * #311/#328: catalogue of soothing sounds the child can play from its own speaker (near the baby),
 * controlled remotely by the parent. Noise colours are generated procedurally: WHITE = bright/hiss,
 * PINK = warmer (1/f, common for sleep), BROWN = deep/rumble. #328 adds public-domain nursery
 * melodies synthesised by [MelodySynth]; BRAHMS plays the bundled public-domain English vocal
 * recording by Elsie Baker (1912).
 */
/**
 * The soother catalogue: spectral noise colours (rendered by [NoiseGenerator]) and #328 nursery
 * melodies (rendered by [MelodySynth] when [lullaby] is non-null). The single enum keeps the wire
 * API one `sound=` parameter; SootherPlayer routes to the right generator by [lullaby].
 */
enum class SoothingSound(
    val lullaby: Lullaby? = null,
    val bundledRecording: Boolean = false,
) {
    WHITE, PINK, BROWN,
    TWINKLE(Lullaby.TWINKLE),
    FRERE_JACQUES(Lullaby.FRERE_JACQUES),
    MARY(Lullaby.MARY),
    BRAHMS(bundledRecording = true),
}

/** Pure parsing/clamping for the soother control surface (HTTP API + persistence). */
object SootherPolicy {

    const val DEFAULT_VOLUME = 0.6f

    /** Parse a remote/persisted sound name; null when unrecognised so callers can 400/ignore. */
    fun parseSound(raw: String?): SoothingSound? = when (raw?.trim()?.uppercase()?.replace("-", "_")?.replace(" ", "_")) {
        "WHITE", "WHITE_NOISE", "WHITENOISE" -> SoothingSound.WHITE
        "PINK", "PINK_NOISE", "PINKNOISE" -> SoothingSound.PINK
        "BROWN", "BROWN_NOISE", "BROWNNOISE", "BROWNIAN", "RED" -> SoothingSound.BROWN
        // #328 nursery melodies
        "TWINKLE", "TWINKLE_TWINKLE", "STAR" -> SoothingSound.TWINKLE
        "FRERE_JACQUES", "FRERE", "BROTHER_JOHN", "ARE_YOU_SLEEPING" -> SoothingSound.FRERE_JACQUES
        "MARY", "MARY_HAD_A_LITTLE_LAMB", "LAMB" -> SoothingSound.MARY
        "BRAHMS", "BRAHMS_LULLABY", "WIEGENLIED", "CRADLE_SONG" -> SoothingSound.BRAHMS
        else -> null
    }

    /** Volume is a linear gain in [0,1]; out-of-range input is clamped, NaN coerced to default. */
    fun clampVolume(v: Float): Float = when {
        v.isNaN() -> DEFAULT_VOLUME
        else -> v.coerceIn(0f, 1f)
    }

    /** Kept as compatibility aliases; #490 gives light and sound the same one-night bounds. */
    const val MIN_DURATION_MS = BedtimeTimerPolicy.MIN_DURATION_MS
    const val MAX_DURATION_MS = BedtimeTimerPolicy.MAX_DURATION_MS

    /**
     * #328: clamp a requested sleep-timer duration (ms). null or a non-positive value ⇒ null, meaning
     * "play indefinitely until an explicit stop" — preserving the pre-#328 default. A positive value
     * is coerced into [MIN_DURATION_MS]..[MAX_DURATION_MS] so a 1ms or 30-day request becomes sane.
     */
    fun clampDurationMs(requested: Long?): Long? = BedtimeTimerPolicy.clampDurationMs(requested)
}
