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
 * Resolves the label the parent sees during a live monitoring session — the on-screen
 * "Connected to" value and the foreground-service notification text.
 *
 * Precedence (Kids feature, #402): the parent-assigned kid name (e.g. "kid1") wins, then the
 * CHILD-advertised friendly name, then the deviceId. This is DISPLAY-only and must never be
 * persisted back as [PairedChild.name] (doing so clobbered the advertised name — the prior
 * Codex P2). Kept pure (no Android deps) so it is unit-testable and used identically by both
 * [ListenActivity] (session label) and [ListenService] (notification).
 */
object KidDisplayName {

    /** @return the first non-blank of [kidLabel] → [advertisedName] → [deviceId], or null if all blank. */
    fun resolve(kidLabel: String?, advertisedName: String?, deviceId: String?): String? =
        kidLabel?.takeIf { it.isNotBlank() }
            ?: advertisedName?.takeIf { it.isNotBlank() }
            ?: deviceId?.takeIf { it.isNotBlank() }
}
