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
package de.rochefort.childmonitor.nighttimeline

/**
 * Classifies a [MinuteBucket] into a [NightState] from a single user "awake" loudness
 * threshold plus a motion threshold (#283). No ML — this mirrors the proven amplitude-
 * threshold model used by phone-based monitors (e.g. Annie): below the line = asleep,
 * crossing it = awake/noisy.
 *
 * The parent's audio stream is the heartbeat: if no audio was sampled in the minute we
 * can't say anything, so it's NO_SIGNAL regardless of motion.
 */
object SleepState {
    fun classify(bucket: MinuteBucket, awakeDbfs: Double, motionThresholdPercent: Int): NightState {
        if (!bucket.hasAudio) return NightState.NO_SIGNAL
        return when {
            bucket.rmsPeakDbfs >= awakeDbfs -> NightState.NOISY
            bucket.motion >= motionThresholdPercent -> NightState.AWAKE
            else -> NightState.ASLEEP
        }
    }
}
