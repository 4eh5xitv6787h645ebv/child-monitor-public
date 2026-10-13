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
package de.rochefort.childmonitor.alarm

/** What the parent should do for the AUDIBLE (sound) channel when the child connection drops (#290). */
enum class AlertMode { SILENT, ONE_SHOT, PERSISTENT }

/**
 * Pure decision for the disconnect SOUND behaviour (#290).
 *
 * #357: this now governs only the sound channel; vibration / screen-flash / torch are independent
 * effects (see [AlertEffects]) the caller fires separately. Pass `muted = true` when sound is muted
 * OR the sound effect is disabled.
 *
 * PERSISTENT (a loud, looping alarm on the ALARM stream that repeats until the user acknowledges or
 * the child reconnects) is an explicit opt-in, so it **overrides** the one-shot "Mute Alert Sounds"
 * toggle. Without persistent mode: muted → SILENT, else a single beep (ONE_SHOT).
 *
 * #347: the ONE_SHOT beep is not re-fired on every reconnect retry — the caller gates it on the
 * episode-scoped disconnect debounce ([ConnectionAlertState]), so a sustained outage beeps once.
 * PERSISTENT is intentionally exempt (its loop is meant to repeat until acknowledged).
 */
object AlarmPolicy {
    fun mode(persistentEnabled: Boolean, muted: Boolean): AlertMode = when {
        persistentEnabled -> AlertMode.PERSISTENT
        muted -> AlertMode.SILENT
        else -> AlertMode.ONE_SHOT
    }
}
