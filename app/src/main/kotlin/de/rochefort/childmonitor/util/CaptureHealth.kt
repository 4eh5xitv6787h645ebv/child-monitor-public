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
 * #299: child capture watchdog. The audio loop self-restarts on a *thrown* error, and the
 * camera has its own health check, but a mic that stops delivering bytes without throwing —
 * AudioRecord.read() wedged/blocking, or returning 0/errors in a tight loop — keeps the TCP
 * link up while no PCM flows, so the parent never even detects it. This decides when to
 * force-restart capture.
 *
 * Scope: this is a "no bytes captured" check, NOT a "captured audio is silent" check. All-zero
 * PCM from a quiet nursery is indistinguishable from a muted-but-alive mic, so content-silence
 * is deliberately out of scope (restarting on a quiet room would thrash all night).
 *
 * Pure + time-injected for unit testing. Caller supplies a monotonic clock (elapsedRealtime).
 */
object CaptureHealth {
    /**
     * Restart audio capture when we're recording for at least one parent but no PCM has been
     * read for [staleMs]. [lastDataMs] == 0 means "no data seen yet" (just started) — never a
     * restart, so a fresh start isn't immediately tripped. A backward clock ([nowMs] <
     * [lastDataMs]) yields a negative elapsed and never restarts.
     */
    fun audioNeedsRestart(
        recording: Boolean,
        hasParents: Boolean,
        lastDataMs: Long,
        nowMs: Long,
        staleMs: Long
    ): Boolean =
        recording && hasParents && lastDataMs > 0L && (nowMs - lastDataMs) >= staleMs
}
