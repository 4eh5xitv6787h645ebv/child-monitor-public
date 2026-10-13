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
package de.rochefort.childmonitor.video

import java.util.concurrent.atomic.AtomicInteger

/**
 * #306: caps concurrent MJPEG streams. Each long-lived `/stream.mjpeg` connection holds one of the
 * web server's bounded worker threads for its whole lifetime, so without a sub-cap a few
 * auto-reconnecting viewers could starve the control/WebSocket connections. Thread-safe (CAS), and
 * the acquire/release accounting is unit-testable.
 */
class MjpegStreamLimiter(private val maxConcurrent: Int = DEFAULT_MAX) {

    private val active = AtomicInteger(0)

    /** Reserve a slot; returns false (caller should 503) when [maxConcurrent] streams are already live. */
    fun tryAcquire(): Boolean {
        while (true) {
            val current = active.get()
            if (current >= maxConcurrent) return false
            if (active.compareAndSet(current, current + 1)) return true
        }
    }

    /** Free a slot. Never drops below zero even if called more than once. */
    fun release() {
        while (true) {
            val current = active.get()
            if (current <= 0) return
            if (active.compareAndSet(current, current - 1)) return
        }
    }

    fun activeCount(): Int = active.get()

    companion object {
        const val DEFAULT_MAX = 3
    }
}
