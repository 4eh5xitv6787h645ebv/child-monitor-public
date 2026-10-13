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
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * #312: full-screen, dim, colour-adjustable night light on the CHILD device, driven remotely by
 * the parent (via MonitorService). A solid-colour view with the window brightness pinned; it
 * wakes the screen and shows over the lockscreen so a remote turn-on lights a sleeping phone.
 *
 * MonitorService is the source of truth: it launches this activity, pushes live colour/brightness
 * updates as package-scoped broadcasts, and sends on=false to dismiss it. A local dismiss (BACK)
 * is reported back so /api/status stays consistent.
 */
class NightLightActivity : Activity() {

    private lateinit var colorView: View
    @Volatile private var finishingFromOff = false

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            applyFromIntent(intent ?: return)
        }
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

        colorView = View(this)
        setContentView(colorView)
        apply(
            intent.getIntExtra(EXTRA_COLOR, NightLightPolicy.DEFAULT_COLOR),
            intent.getFloatExtra(EXTRA_BRIGHTNESS, NightLightPolicy.DEFAULT_BRIGHTNESS)
        )

        androidx.core.content.ContextCompat.registerReceiver(
            this, updateReceiver, IntentFilter(ACTION_UPDATE),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        Log.i(TAG, "Night light shown")
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        applyFromIntent(intent ?: return)
    }

    private fun applyFromIntent(intent: Intent) {
        if (!intent.getBooleanExtra(EXTRA_ON, true)) {
            finishingFromOff = true
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            finish()
            return
        }
        apply(
            intent.getIntExtra(EXTRA_COLOR, NightLightPolicy.DEFAULT_COLOR),
            intent.getFloatExtra(EXTRA_BRIGHTNESS, NightLightPolicy.DEFAULT_BRIGHTNESS)
        )
    }

    private fun apply(color: Int, brightness: Float) {
        colorView.setBackgroundColor(color)
        val lp = window.attributes
        // Keep at least a hair of backlight so the colour is actually visible (the whole point).
        lp.screenBrightness = NightLightPolicy.clampBrightness(brightness).coerceAtLeast(0.01f)
        window.attributes = lp
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
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { unregisterReceiver(updateReceiver) } catch (_: Exception) {}
        // If dismissed locally (BACK), tell the service so /api/status reflects off.
        if (!finishingFromOff) {
            sendBroadcast(Intent(ACTION_CLOSED).setPackage(packageName))
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NightLightActivity"
        const val ACTION_UPDATE = "de.rochefort.childmonitor.NIGHTLIGHT_UPDATE"
        const val ACTION_CLOSED = "de.rochefort.childmonitor.NIGHTLIGHT_CLOSED"
        const val EXTRA_ON = "on"
        const val EXTRA_COLOR = "color"
        const val EXTRA_BRIGHTNESS = "brightness"
    }
}
