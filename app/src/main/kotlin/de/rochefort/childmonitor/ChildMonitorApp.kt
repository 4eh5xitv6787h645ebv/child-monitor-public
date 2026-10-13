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

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import de.rochefort.childmonitor.security.AppLock

/**
 * #296: app-lock host. Tracks foreground/background via ActivityLifecycleCallbacks and shows
 * [LockActivity] when the app is opened (cold start or returning from the background) while the
 * "Require unlock to open" setting is on. Content activities are gated; [LockActivity] is exempt so
 * it never gates itself. A short debounced background check ([BACKGROUND_DEBOUNCE_MS]) absorbs
 * normal activity transitions, and the gate's own unlocking flag absorbs the credential round-trip.
 *
 * The disconnect alert lives in the foreground ListenService and is unaffected — an alert still
 * fires while the app is locked; tapping its notification just opens the (locked) app.
 */
class ChildMonitorApp : Application() {

    private val handler = Handler(Looper.getMainLooper())
    private val enterBackground = Runnable { AppLock.gate.onEnteredBackground() }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (activity is LockActivity) return
                // #296: cover the cold-start flash — launch the lock BEFORE this activity's first
                // frame when we're not authenticated (onActivityStarted handles background-return).
                if (AppLock.gate.shouldLockOnCreate(AppLock.isEnabled(activity))) {
                    showLock(activity)
                }
            }

            override fun onActivityStarted(activity: Activity) {
                if (activity is LockActivity) return
                handler.removeCallbacks(enterBackground) // a transition, not a real background
                if (AppLock.gate.onActivityStarted(AppLock.isEnabled(activity))) {
                    showLock(activity)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (activity is LockActivity) return
                if (AppLock.gate.onActivityStopped()) {
                    handler.postDelayed(enterBackground, BACKGROUND_DEBOUNCE_MS)
                }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun showLock(activity: Activity) {
        AppLock.gate.beginUnlock()
        activity.startActivity(
            Intent(activity, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    companion object {
        // Long enough to bridge an activity→activity hand-off without flagging a real background.
        private const val BACKGROUND_DEBOUNCE_MS = 700L
    }
}
