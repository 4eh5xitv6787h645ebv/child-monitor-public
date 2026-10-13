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
 * #325 parts 1 & 3: pure decisions for the parent's live snapshot session. No Android deps, so it is
 * unit-testable without Robolectric.
 *
 * Part 1 (auto-glance): while the parent is actively watching, overlay a rolling near-live "glance"
 * on the child (`POST /api/snapshot/cadence?glanceMs=`) so the live view is smooth, then let it lapse
 * when the parent leaves so the child reverts to its base mode and saves battery. The glance never
 * changes the base mode, so the explicit cadence selector (#325 part 2) is untouched.
 *
 * Part 3 (poll alignment): align the parent's `/snapshot` poll interval to the child's effective
 * capture interval — which we DERIVE from the known base mode + our own glance state rather than
 * trusting a (transient, possibly out-of-range) wire value — so the parent neither re-fetches a slow
 * cadence nor lags a near-live one.
 */
object SnapshotSessionPolicy {

    /** Each auto-glance lasts this long on the child (the child clamps to [1s, 120s]). */
    const val GLANCE_MS = 15_000L

    /** Re-issue the glance on this cadence — shorter than [GLANCE_MS] so the near-live window never
     *  lapses mid-session, but infrequent enough to be one cheap POST every ~10s, not a spam of glances. */
    const val GLANCE_PING_INTERVAL_MS = 10_000L

    // The child's per-mode capture intervals (mirror SnapshotCadencePolicy on the child side).
    const val NEAR_LIVE_MS = 300L
    const val BALANCED_MS = 1_000L
    const val ECO_MS = 5_000L
    const val AUTO_BASE_MS = 1_000L  // AUTO ≈ 1fps at rest (battery-saver variance ignored on the parent)

    // Hard bounds on the parent poll: the floor allows near-live (300ms) but blocks a runaway child;
    // the ceiling matches the parent's poor-connection interval.
    private const val MIN_POLL_MS = 250L
    private const val MAX_POLL_MS = 10_000L

    /**
     * Part 1 gate: auto-glance only when the base cadence is AUTO ("let the app decide"). An explicit
     * ECO / BALANCED / NEAR_LIVE is the user's deliberate rate (e.g. ECO to spare the child's battery)
     * and must be respected, so no glance is overlaid. Requires the camera on, not scrubbing replay,
     * the audio-first fallback not active, and a healthy link (don't tell a struggling link to speed
     * up — part 3 wouldn't poll fast over it anyway). A null/unknown base mode does not glance.
     */
    fun shouldAutoGlance(
        baseMode: String?,
        cameraEnabled: Boolean,
        scrubActive: Boolean,
        videoPausedForAudio: Boolean,
        linkHealthy: Boolean,
    ): Boolean = baseMode == "AUTO" && cameraEnabled && !scrubActive && !videoPausedForAudio && linkHealthy

    /**
     * The child's effective capture interval, DERIVED from its base mode and whether the parent is
     * currently overlaying an auto-glance — not read from a transient wire value. This keeps the poll
     * correct the instant the user switches modes (e.g. AUTO→ECO during an active glance must align to
     * ECO immediately, not stay stuck at the lapsing glance's 300ms). -1 = unknown (→ align is a no-op).
     */
    fun childEffectiveIntervalMs(baseMode: String?, autoGlancing: Boolean): Long = when {
        autoGlancing -> NEAR_LIVE_MS
        baseMode == "ECO" -> ECO_MS
        baseMode == "BALANCED" -> BALANCED_MS
        baseMode == "NEAR_LIVE" -> NEAR_LIVE_MS
        baseMode == "AUTO" -> AUTO_BASE_MS
        else -> -1L
    }

    /**
     * Part 3: the parent poll interval, aligned to the child's [childEffectiveMs] against the parent's
     * own [adaptiveMs] (its brightness/connection-derived interval), then clamped to a safe range.
     *
     * - child paused/unknown (<= 0) → keep the parent's own [adaptiveMs] (can't align).
     * - child no faster than the parent already polls → match the child, so the parent doesn't
     *   re-fetch the same JPEG several times over a slow cadence.
     * - child faster than the parent (near-live/glance) → speed up to keep up, but only when
     *   [linkHealthy]; on a struggling link keep the safer [adaptiveMs] rather than flood it.
     */
    fun alignedPollIntervalMs(adaptiveMs: Long, childEffectiveMs: Long, linkHealthy: Boolean): Long {
        val target = when {
            childEffectiveMs <= 0L -> adaptiveMs
            childEffectiveMs >= adaptiveMs -> childEffectiveMs
            linkHealthy -> childEffectiveMs
            else -> adaptiveMs
        }
        return target.coerceIn(MIN_POLL_MS, MAX_POLL_MS)
    }
}
