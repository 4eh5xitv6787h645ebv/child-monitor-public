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
 */
package de.rochefort.childmonitor

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Shared control-plane session for the parent's child-control screens ([ChildControlsActivity],
 * [ChildAlertsActivity]). Resolves the CURRENT live child each call — following a mid-session
 * child-swap (#366) by reading [ListenActivity.liveEndpoint], with the launch-time address/deviceId
 * only as a fallback — builds a [ParentControlClient] over the existing pinned/bearer transport, and
 * serializes every request on ONE process-scoped thread so commands reach the child in order and a
 * slow status read can't overtake a control POST (Codex). Fails closed (null client) when there is
 * no reachable, [PairingValidation.isSafeHost]-valid child.
 */
class ParentControlSession(
    context: Context,
    private val initialAddress: String?,
    private val initialDeviceId: String?,
) {
    // Hold the application context so a queued task that outlives the Activity can't leak it.
    private val appContext = context.applicationContext

    /** The deviceId of the current live child (for token/pin lookups), falling back to launch-time. */
    fun deviceId(): String? = ListenActivity.liveEndpoint?.deviceId ?: initialDeviceId

    /** A client for the current live child, or null when there's no safe, reachable target. */
    fun resolveClient(): ParentControlClient? {
        // Snapshot the live endpoint ONCE so address+deviceId are a consistent pair, never torn (Codex).
        val live = ListenActivity.liveEndpoint
        val liveAddr = live?.address?.takeIf { it.isNotBlank() }
        val address = liveAddr ?: initialAddress?.takeIf { it.isNotBlank() } ?: return null
        if (!PairingValidation.isSafeHost(address)) return null
        // Pair the deviceId with whichever address won, so token/pin lookups stay consistent.
        val deviceId = if (liveAddr != null) live.deviceId else initialDeviceId
        return ParentControlClient(appContext, address, deviceId)
    }

    /** Whether a usable target exists right now (used to decide whether to even open a screen). */
    fun hasTarget(): Boolean = resolveClient() != null

    /**
     * Run [task] on the shared serialized control thread (off the UI thread; the task marshals its
     * own UI work). The task calls [resolveClient] at each network op so every POST/GET targets the
     * CURRENT live child — re-resolving per op (not snapshotting one client per task) keeps a
     * follow-up status read from applying an old child's state after a mid-task swap (Codex).
     */
    fun submit(task: () -> Unit) {
        try {
            controlQueue.execute(task)
        } catch (_: RejectedExecutionException) {
            // Executor is process-scoped and never shut down; this is defensive only.
        }
    }

    companion object {
        // Process-scoped, single-thread, daemon — see class doc. Kept off any Activity lifecycle so a
        // command tapped just before a screen closes still reaches the child, and a relaunched screen
        // can't race a fresh executor against a draining old one.
        private val controlQueue = Executors.newSingleThreadExecutor { r ->
            Thread(r, "child-controls").apply { isDaemon = true }
        }
    }
}
