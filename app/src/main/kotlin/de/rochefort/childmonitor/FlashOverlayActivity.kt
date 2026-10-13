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

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * #303 (deaf/HoH alert mode): a full-screen, max-brightness screen-flash overlay on the PARENT
 * device, shown when the link to the child drops. It strobes white↔dark at a photosensitive-safe
 * cadence (<3 flashes/sec, per WCAG) and wakes the screen over the lockscreen so a deaf/HoH parent
 * sees the alert even with the phone face-up on a nightstand.
 *
 * Launched via the disconnect notification's full-screen intent (so it works under Android 12+
 * background-activity-launch limits — see [de.rochefort.childmonitor.alarm.ConnectionAlertNotifier]).
 * Dismissed by [ACTION_STOP] (broadcast on reconnect/acknowledge), or BACK/tap.
 */
class FlashOverlayActivity : Activity() {

    private lateinit var flashView: View
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var bright = false

    private val strobe = object : Runnable {
        override fun run() {
            bright = !bright
            flashView.setBackgroundColor(if (bright) Color.WHITE else Color.BLACK)
            handler.postDelayed(this, STROBE_MS)
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showWhenLockedAndTurnScreenOn()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        run {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                )
        }
        flashView = View(this).apply { setBackgroundColor(Color.WHITE) }
        setContentView(flashView)
        // Max backlight for maximum contrast/visibility.
        val lp = window.attributes
        lp.screenBrightness = 1.0f
        window.attributes = lp

        androidx.core.content.ContextCompat.registerReceiver(
            this, stopReceiver, IntentFilter(ACTION_STOP),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        handler.post(strobe)
        Log.i(TAG, "Flash overlay shown")
    }

    /** Tap anywhere to dismiss (the user has seen it). */
    override fun onUserInteraction() {
        super.onUserInteraction()
        finish()
    }

    private fun showWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(strobe)
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FlashOverlay"
        const val ACTION_STOP = "de.rochefort.childmonitor.FLASH_OVERLAY_STOP"
        // ~1.7 Hz (300ms on / 300ms off) — well under the 3 flashes/sec photosensitive-epilepsy
        // threshold (WCAG 2.3.1) while still being clearly attention-grabbing.
        private const val STROBE_MS = 300L
    }
}
