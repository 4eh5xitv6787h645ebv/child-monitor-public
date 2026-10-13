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
 * #286: decides which [SoundEventDetector.Event]s are worth alerting on — "alert on crying, not
 * every creak". A cry (and a notable cough) above a confidence floor is alert-worthy; a generic
 * "noise" region is treated as ambient and suppressed. Pure/unit-testable.
 */
object SoundAlertPolicy {

    const val MIN_CONFIDENCE = 0.55

    fun isAlertWorthy(type: String, confidence: Double): Boolean =
        confidence >= MIN_CONFIDENCE && (type == "cry" || type == "cough")
}
