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
package de.rochefort.childmonitor.alarm

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import de.rochefort.childmonitor.FlashOverlayActivity

/**
 * #303 (deaf/HoH alert mode): the PARENT-side non-audio disconnect alert — a sustained vibration
 * plus a camera-torch strobe, held until the user acknowledges or the child reconnects. The
 * full-screen screen-flash is delivered separately by the disconnect notification's full-screen
 * intent ([ConnectionAlertNotifier] → [FlashOverlayActivity]); [stop] dismisses that overlay too.
 *
 * Lifecycle mirrors [Alarm]: [start] is idempotent and is suppressed after [acknowledge] until the
 * next [reset] (reconnect), so a flapping link can't re-loop the alert after the user has seen it.
 * Each channel degrades gracefully: no flash unit / torch error / no vibrator simply skips that
 * modality (a baby monitor must still alert via whatever channels are available).
 */
class HighVisibilityAlerter(private val context: Context) {

    @Volatile private var active = false
    @Volatile private var suppressed = false
    private var torchThread: HandlerThread? = null
    private var torchHandler: Handler? = null
    private var torchCameraId: String? = null
    @Volatile private var torchOn = false

    /** Begin the requested non-audible effects (#357: each independently toggleable). No-op if
     *  already active, or if suppressed after an acknowledge (until the next reset) — so repeated
     *  per-reconnect-tick calls don't restart it and an acknowledged alert doesn't re-fire while
     *  the link is still down. The screen-flash effect is delivered separately via the disconnect
     *  notification's full-screen intent; [stop] dismisses it regardless. */
    @Synchronized
    fun start(vibration: Boolean = true, screenFlash: Boolean = false, torch: Boolean = true) {
        if (active || suppressed) return
        active = true
        if (vibration) startVibration()
        if (torch) startTorch()
        // The screen-flash overlay itself is launched by the disconnect notification's full-screen
        // intent — we just record this episode as active (even for flash-only) so stop()/reset()/
        // acknowledge() broadcast ACTION_STOP and dismiss it on reconnect.
        Log.i(TAG, "High-visibility alert started (vibration=$vibration screenFlash=$screenFlash torch=$torch)")
    }

    /** Stop everything (teardown). Leaves the suppression latch as-is. */
    @Synchronized
    fun stop() = stopInternal()

    /** User acknowledged (opened the app) — stop and suppress re-firing until reconnect. */
    @Synchronized
    fun acknowledge() {
        suppressed = true
        stopInternal()
    }

    /** Connection re-established — stop and re-arm for the next outage. */
    @Synchronized
    fun reset() {
        suppressed = false
        stopInternal()
    }

    private fun stopInternal() {
        if (!active) return
        active = false
        stopVibration()
        stopTorch()
        // Dismiss the FSI-launched screen-flash overlay, if showing.
        try {
            context.sendBroadcast(
                Intent(FlashOverlayActivity.ACTION_STOP).setPackage(context.packageName)
            )
        } catch (_: Exception) {}
        Log.i(TAG, "High-visibility alert stopped")
    }

    // ---- vibration ----

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (e: Exception) {
        Log.w(TAG, "vibrator unavailable", e); null
    }

    private fun startVibration() {
        try {
            val v = vibrator() ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Repeat the waveform from index 0 until cancel() — a strong, sustained buzz.
                v.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VIBRATION_PATTERN, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sustained vibration failed", e)
        }
    }

    private fun stopVibration() {
        try { vibrator()?.cancel() } catch (_: Exception) {}
    }

    // ---- torch strobe ----

    @android.annotation.SuppressLint("NewApi") // guarded at entry; keeps API 21/22 from resolving setTorchMode
    private fun startTorch() {
        // CameraManager.setTorchMode is API 23+. minSdk is 21 — on 21/22 calling it throws
        // NoSuchMethodError (an Error, not Exception), so skip torch there rather than crash.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
            val id = cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: run {
                Log.i(TAG, "no flash unit — skipping torch strobe")
                return
            }
            torchCameraId = id
            val thread = HandlerThread("hv-torch").apply { start() }
            torchThread = thread
            val h = Handler(thread.looper)
            torchHandler = h
            h.post(object : Runnable {
                override fun run() {
                    if (!active) {
                        // We were stopped mid-strobe — make sure the LED ends OFF, don't re-post.
                        try { cm.setTorchMode(id, false) } catch (_: Exception) {}
                        return
                    }
                    torchOn = !torchOn
                    try { cm.setTorchMode(id, torchOn) } catch (e: Exception) {
                        Log.w(TAG, "setTorchMode failed — stopping torch", e)
                        return
                    }
                    h.postDelayed(this, STROBE_MS)
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "torch strobe unavailable", e)
        }
    }

    private fun stopTorch() {
        val h = torchHandler
        val t = torchThread
        val id = torchCameraId
        torchHandler = null
        torchThread = null
        torchCameraId = null
        torchOn = false
        if (h != null && id != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            h.removeCallbacksAndMessages(null)
            // Post the final OFF onto the torch thread so it runs AFTER any in-flight strobe step
            // (which, with active=false, also turns OFF) — the LED is guaranteed to end off.
            h.post {
                try {
                    (context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager)
                        ?.setTorchMode(id, false)
                } catch (_: Exception) {}
            }
        }
        try { t?.quitSafely() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "HighVisAlerter"
        // Strong sustained buzz: 600ms on / 400ms off, repeated.
        private val VIBRATION_PATTERN = longArrayOf(0, 600, 400)
        // ~1.7 Hz torch strobe (matches FlashOverlayActivity; photosensitive-safe).
        private const val STROBE_MS = 300L
    }
}
