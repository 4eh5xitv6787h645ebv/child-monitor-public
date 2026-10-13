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

/**
 * #287: resolves the effective snapshot *encode* interval the child camera should emit at,
 * letting the user trade frame-rate vs battery/bandwidth. Layers a user-chosen mode on top of
 * the automatic [AdaptiveSnapshotController], plus a short-lived "near-live glance" override for
 * when a parent is actively watching.
 *
 * Pure + time-injected (caller passes a monotonic [nowMs] and the glance deadline). The actual
 * camera throttle (CameraStreamer) and remote control (WebControlServer) live elsewhere.
 */
object SnapshotCadencePolicy {

    /**
     * ECO/BALANCED/NEAR_LIVE pin a fixed cadence; AUTO defers to the adaptive controller
     * (motion/thermal/battery aware). A glance temporarily forces near-live regardless of mode.
     */
    enum class Mode { ECO, BALANCED, NEAR_LIVE, AUTO }

    const val NEAR_LIVE_MS = 300L
    const val BALANCED_MS = 1_000L
    const val ECO_MS = 5_000L

    /** Hard bounds for any finite interval; MAX_VALUE (thermal pause) passes through untouched. */
    const val MIN_MS = 200L
    const val MAX_MS = 10_000L

    val DEFAULT_MODE = Mode.AUTO

    /** Parse a remote/persisted mode string; null when unrecognised so callers can 400/ignore. */
    fun parseMode(raw: String?): Mode? = when (raw?.trim()?.uppercase()) {
        "ECO" -> Mode.ECO
        "BALANCED" -> Mode.BALANCED
        "NEAR_LIVE", "NEARLIVE", "NEAR-LIVE", "LIVE", "GLANCE" -> Mode.NEAR_LIVE
        "AUTO" -> Mode.AUTO
        else -> null
    }

    /**
     * Effective encode interval in ms. An active glance ([nowMs] < [glanceUntilMs]) wins over the
     * mode and forces near-live; otherwise the mode decides, with AUTO deferring to
     * [adaptiveIntervalMs]. A thermal-paused adaptive interval ([Long.MAX_VALUE]) passes through
     * (meaning "stop encoding") unless a glance is overriding it. Finite results are clamped to
     * [[MIN_MS], [MAX_MS]].
     */
    fun effectiveIntervalMs(
        mode: Mode,
        adaptiveIntervalMs: Long,
        glanceUntilMs: Long,
        nowMs: Long
    ): Long {
        if (nowMs < glanceUntilMs) return clampFinite(NEAR_LIVE_MS)
        return when (mode) {
            Mode.ECO -> clampFinite(ECO_MS)
            Mode.BALANCED -> clampFinite(BALANCED_MS)
            Mode.NEAR_LIVE -> clampFinite(NEAR_LIVE_MS)
            Mode.AUTO -> clampFinite(adaptiveIntervalMs)
        }
    }

    private fun clampFinite(v: Long): Long =
        if (v == Long.MAX_VALUE) Long.MAX_VALUE else v.coerceIn(MIN_MS, MAX_MS)
}
