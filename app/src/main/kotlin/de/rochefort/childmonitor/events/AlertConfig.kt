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
package de.rochefort.childmonitor.events

/**
 * #291: the child's sound-alert configuration as one immutable value — per-trigger toggles plus a
 * [QuietHoursRule]. Held as a single reference so a reader (the audio thread's [SoundAlertGate]
 * decision) always sees a consistent combination of all three fields rather than a torn mix of an
 * old and a new write. Defaults preserve the pre-#291 behavior: both triggers on, quiet hours off
 * → alert 24/7.
 */
data class AlertConfig(
    val cryEnabled: Boolean = true,
    val coughEnabled: Boolean = true,
    val motionEnabled: Boolean = true,
    val quietHours: QuietHoursRule = QuietHoursRule(enabled = false)
)
