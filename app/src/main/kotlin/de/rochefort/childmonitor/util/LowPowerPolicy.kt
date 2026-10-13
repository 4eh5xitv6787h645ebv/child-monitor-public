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
package de.rochefort.childmonitor.util

/**
 * #300: low-power "audio-only" mode for the child. When the user turns the camera off it is
 * now persisted, so on every launch the child can skip starting the camera entirely
 * (suspending snapshots) to save battery — and the foreground-service type drops CAMERA.
 *
 * The camera runs only when the user hasn't disabled it AND the CAMERA permission is held.
 */
object LowPowerPolicy {
    fun shouldRunCamera(cameraPref: Boolean, hasCameraPermission: Boolean): Boolean =
        cameraPref && hasCameraPermission
}
