package de.rochefort.childmonitor

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import de.rochefort.childmonitor.LogManager.LogCategory

/**
 * Monitors battery and power state changes to detect when the device may be
 * entering sleep/doze modes that could affect app operation.
 *
 * This helps diagnose issues where phones aggressively put apps to sleep overnight
 * during charging, even with unrestricted battery access.
 */
class BatteryStateMonitor(private val context: Context) {

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var isMonitoring = false

    // Track last known states
    private var lastIsCharging: Boolean? = null
    private var lastChargingSource: String? = null
    private var lastIsDozing: Boolean? = null
    private var lastIsIgnoringBatteryOptimizations: Boolean? = null

    private val powerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    val chargingSource = getChargingSource()
                    LogManager.i(
                        LogCategory.SYSTEM,
                        "Power connected | Source: $chargingSource"
                    )
                    lastIsCharging = true
                    lastChargingSource = chargingSource
                    logCurrentPowerState()
                }

                Intent.ACTION_POWER_DISCONNECTED -> {
                    LogManager.i(LogCategory.SYSTEM, "Power disconnected")
                    lastIsCharging = false
                    lastChargingSource = null
                    logCurrentPowerState()
                }

                // ACTION_BATTERY_CHANGED is deliberately NOT registered. It's a sticky broadcast
                // that fires every ~1-3 seconds, so subscribing burns CPU walking onReceive
                // hundreds of times per minute just to print "level changed" once per 10%
                // drop. Battery level is now queried on-demand inside [logCurrentPowerState]
                // (heartbeat tick + significant power-state changes), which is plenty for
                // diagnostics and adds no continuous wake activity.

                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val isDozing = powerManager.isDeviceIdleMode
                        val changed = lastIsDozing != isDozing

                        if (changed) {
                            if (isDozing) {
                                LogManager.w(
                                    LogCategory.SYSTEM,
                                    "Device entered DOZE mode - app may be restricted"
                                )
                            } else {
                                LogManager.i(
                                    LogCategory.SYSTEM,
                                    "Device exited DOZE mode"
                                )
                            }
                            lastIsDozing = isDozing
                        }
                    }
                }

                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val isPowerSaveMode = powerManager.isPowerSaveMode
                        LogManager.i(
                            LogCategory.SYSTEM,
                            "Power save mode changed | Enabled: $isPowerSaveMode"
                        )
                        logCurrentPowerState()
                    }
                }
            }
        }
    }

    /**
     * Start monitoring battery and power state
     */
    fun startMonitoring() {
        if (isMonitoring) return

        LogManager.i(LogCategory.SYSTEM, "Starting battery state monitoring")

        // Only register for sparse, event-driven actions. Battery level is queried on-demand
        // by logCurrentPowerState() instead of subscribing to the every-second
        // ACTION_BATTERY_CHANGED firehose.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            }
        }

        try {
            context.registerReceiver(powerStateReceiver, filter)
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Failed to register battery receiver", e)
        }

        isMonitoring = true
        logCurrentPowerState()
    }

    /**
     * Stop monitoring battery and power state
     */
    fun stopMonitoring() {
        if (!isMonitoring) return

        LogManager.i(LogCategory.SYSTEM, "Stopping battery state monitoring")

        try {
            context.unregisterReceiver(powerStateReceiver)
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Failed to unregister battery receiver", e)
        }

        isMonitoring = false
    }

    /**
     * Log comprehensive current power state
     */
    fun logCurrentPowerState() {
        try {
            val details = buildString {
                // Charging state
                val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val isCharging = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    batteryManager.isCharging
                } else {
                    lastIsCharging ?: false
                }
                append("Charging: $isCharging")

                if (isCharging) {
                    append(" (${getChargingSource()})")
                }

                // Battery level
                val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                append(" | Battery: ${level}%")

                // Doze mode (Android M+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val isDozing = powerManager.isDeviceIdleMode
                    append(" | Doze: $isDozing")

                    val isPowerSaveMode = powerManager.isPowerSaveMode
                    append(" | PowerSave: $isPowerSaveMode")

                    // Battery optimization status
                    val isIgnoringOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                    append(" | IgnoringOptimizations: $isIgnoringOptimizations")

                    // Log warning if not ignoring optimizations
                    if (!isIgnoringOptimizations && lastIsIgnoringBatteryOptimizations != false) {
                        LogManager.w(
                            LogCategory.SYSTEM,
                            "App is NOT ignoring battery optimizations - may be killed during sleep"
                        )
                    }
                    lastIsIgnoringBatteryOptimizations = isIgnoringOptimizations
                }

                // App standby bucket (Android P+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
                    val standbyBucket = usageStatsManager.appStandbyBucket
                    val bucketName = getStandbyBucketName(standbyBucket)
                    append(" | StandbyBucket: $bucketName")
                }
            }

            LogManager.i(LogCategory.SYSTEM, "Power state | $details")
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Failed to log power state", e)
        }
    }

    /**
     * Get the current charging source (USB, AC, Wireless)
     */
    private fun getChargingSource(): String {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val plugType = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

            when (plugType) {
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }
    }

    /**
     * Get human-readable name for app standby bucket
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun getStandbyBucketName(bucket: Int): String {
        // STANDBY_BUCKET_EXEMPTED = 5 is intentionally undocumented in the SDK but
        // returned to apps the user has allowed to ignore battery optimizations.
        // Without this branch the log permanently showed UNKNOWN for whitelisted runs.
        return when (bucket) {
            5 -> "EXEMPTED"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_ACTIVE -> "ACTIVE"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> "WORKING_SET"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_FREQUENT -> "FREQUENT"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_RARE -> "RARE"
            android.app.usage.UsageStatsManager.STANDBY_BUCKET_RESTRICTED -> "RESTRICTED"
            // Include raw value so a future SDK adding a new bucket is diagnosable
            else -> "UNKNOWN($bucket)"
        }
    }
}
