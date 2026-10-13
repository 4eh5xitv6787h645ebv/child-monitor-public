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
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * #302: parent "monitor mode" — a full-screen black, minimum-brightness screen for the bedside,
 * to save battery and avoid light at night. Audio playback and disconnect alerts keep running the
 * whole time because they live in the foreground [ListenService], which is independent of this (or
 * any) Activity — putting [ListenActivity] in the background does not stop monitoring. Tap (or back)
 * to exit. A faint "tap to exit" hint shows briefly, then fades so the screen is truly dark.
 */
class BlackoutActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var hint: TextView? = null
    private val hideHint = Runnable { hint?.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #361: keep the screen on so it stays a dim-black glanceable "monitor" rather than sleeping —
        // but only when the user wants it (default on). Off → the display follows the normal timeout.
        val keepScreenOn = getSharedPreferences("ListenPreferences", MODE_PRIVATE)
            .getBoolean("monitorModeKeepScreenOn", true)
        if (keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
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

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        hint = TextView(this).apply {
            text = getString(R.string.blackoutHint)
            setTextColor(HINT_COLOR)
            textSize = 14f
        }
        root.addView(
            hint,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        )
        setContentView(root)

        // Minimum backlight (0 = lowest) — combined with all-black this is the darkest, lowest-power
        // state while still "on". The gate keeps audio/alerts untouched (they're in the service).
        val lp = window.attributes
        lp.screenBrightness = 0.0f
        window.attributes = lp

        handler.postDelayed(hideHint, HINT_VISIBLE_MS)
        Log.i(TAG, "Monitor (blackout) mode on — audio + alerts continue in the service")
    }

    /** Tap anywhere to exit back to the monitor UI. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideHint)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BlackoutActivity"
        // ~16% white — visible enough to find in a dark room, faint enough not to light it up.
        private const val HINT_COLOR = 0x29FFFFFF
        private const val HINT_VISIBLE_MS = 3000L
    }
}
