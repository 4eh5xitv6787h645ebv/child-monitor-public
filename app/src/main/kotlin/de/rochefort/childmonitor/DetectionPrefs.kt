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
package de.rochefort.childmonitor

/**
 * Shared keys / defaults / range for the parent's video-detection preferences: adaptive snapshot
 * refresh and parent-side motion detection on the snapshot feed. Persisted in ListenPreferences and
 * consumed live by [ListenActivity] (the session screen); a Settings home mirrors them (settings
 * consolidation — like mute/persistent #359 and night-sensitivity). Centralized here so the two
 * surfaces can't drift on the key, default, or threshold range.
 */
object DetectionPrefs {
    // Stored in the "ListenPreferences" SharedPreferences (the parent's catch-all store).
    const val KEY_ADAPTIVE_VIDEO = "adaptiveVideoEnabled"
    const val KEY_MOTION_DETECTION = "motionDetectionEnabled"
    const val KEY_MOTION_THRESHOLD = "motionThresholdPercent"

    const val DEFAULT_ADAPTIVE_VIDEO = true
    const val DEFAULT_MOTION_DETECTION = false
    const val DEFAULT_MOTION_THRESHOLD = 18

    const val MOTION_THRESHOLD_MIN = 5
    const val MOTION_THRESHOLD_MAX = 40

    /** Clamp a raw SeekBar progress (0..[MOTION_THRESHOLD_MAX]) to the valid motion-threshold range. */
    fun clampThreshold(raw: Int): Int = raw.coerceIn(MOTION_THRESHOLD_MIN, MOTION_THRESHOLD_MAX)
}
