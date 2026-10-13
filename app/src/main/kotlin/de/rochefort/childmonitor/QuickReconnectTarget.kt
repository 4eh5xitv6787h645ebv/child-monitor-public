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
 * Picks which paired child a one-tap Quick-Reconnect (StartActivity) or a boot auto-start
 * (BootService) should launch — BY stable deviceId, so it benefits from the permanent-link path
 * (token/pin reuse, kid name, fast+strict reconnect). Precedence (#402):
 *   1. the user's chosen **default kid** (if still in the roster),
 *   2. otherwise the **most-recent** paired child (the roster is move-to-front),
 *   3. null when nothing is paired — callers fall back to the legacy IP-only path.
 *
 * Pure (no Android deps) so it is unit-testable directly.
 */
object QuickReconnectTarget {

    fun resolve(defaultKidDeviceId: String?, paired: List<PairedChild>): PairedChild? {
        if (paired.isEmpty()) return null
        defaultKidDeviceId?.takeIf { it.isNotBlank() }?.let { def ->
            paired.firstOrNull { it.deviceId == def }?.let { return it }
        }
        return paired.first()
    }
}
