package de.rochefort.childmonitor.video

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Thermal-aware camera profile manager.
 * Implements CM-VID-005: Thermal-aware camera profile manager.
 *
 * Observes [PowerManager.getCurrentThermalStatus] (API 29+) and scales camera
 * work accordingly:
 *  NONE/LIGHT  - full resolution + full snapshot rate
 *  MODERATE    - half snapshot rate
 *  SEVERE      - quarter snapshot rate and drop continuous encoding
 *  CRITICAL+   - pause snapshots, keep audio only
 */
class ThermalProfileManager(
    private val context: Context,
    private val listener: (Profile) -> Unit = {}
) {

    enum class Profile {
        FULL, HALF, QUARTER, PAUSED
    }

    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var started = false
    private var pollThread: Thread? = null

    fun start() {
        if (started || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        started = true
        val thread = Thread {
            var last: Profile? = null
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val cur = profileFromStatus(pm.currentThermalStatus)
                    if (cur != last) {
                        last = cur
                        listener(cur)
                    }
                    Thread.sleep(30_000L)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (_: Exception) {
                    Thread.sleep(60_000L)
                }
            }
        }
        pollThread = thread
        thread.start()
    }

    fun stop() {
        started = false
        pollThread?.interrupt()
        pollThread = null
    }

    fun currentProfile(): Profile {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Profile.FULL
        return profileFromStatus(pm.currentThermalStatus)
    }

    private fun profileFromStatus(status: Int): Profile = when (status) {
        PowerManager.THERMAL_STATUS_NONE,
        PowerManager.THERMAL_STATUS_LIGHT -> Profile.FULL
        PowerManager.THERMAL_STATUS_MODERATE -> Profile.HALF
        PowerManager.THERMAL_STATUS_SEVERE -> Profile.QUARTER
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN -> Profile.PAUSED
        else -> Profile.FULL
    }
}
