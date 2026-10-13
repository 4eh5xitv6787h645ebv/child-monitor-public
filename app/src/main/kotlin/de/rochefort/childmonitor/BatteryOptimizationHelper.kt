package de.rochefort.childmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helper class to check and prompt users about battery optimization settings.
 * Ensures the app can run reliably in the background without being killed by the system.
 */
object BatteryOptimizationHelper {

    /**
     * Check if battery optimization is disabled for this app
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true // No battery optimization on older Android versions
    }

    /**
     * Show a dialog prompting the user to disable battery optimization if needed.
     * Returns true if prompt was shown, false if settings are already correct.
     */
    fun checkAndPromptIfNeeded(activity: Activity): Boolean {
        if (!isIgnoringBatteryOptimizations(activity)) {
            // Try the direct request first (shows system dialog)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                    return true
                } catch (e: Exception) {
                    // Fallback to manual dialog if direct request fails
                    showBatteryOptimizationDialog(activity)
                    return true
                }
            } else {
                showBatteryOptimizationDialog(activity)
                return true
            }
        }
        return false
    }

    /**
     * Show dialog explaining battery optimization and offering to open settings
     */
    private fun showBatteryOptimizationDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Battery Optimization Detected")
            .setMessage(
                "For reliable operation, this app needs to run without battery restrictions.\n\n" +
                        "The app may stop working overnight or disconnect randomly if battery optimization is enabled.\n\n" +
                        "Please select 'Unrestricted' in the battery settings."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                openBatteryOptimizationSettings(activity)
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("More Info") { _, _ ->
                showDetailedInstructions(activity)
            }
            .setCancelable(true)
            .show()
    }

    /**
     * Show detailed instructions for configuring battery settings
     */
    private fun showDetailedInstructions(activity: Activity) {
        val instructions = buildString {
            appendLine("Required Settings:")
            appendLine()
            appendLine("1. Battery Optimization")
            appendLine("   Settings → Apps → Child Monitor")
            appendLine("   → Battery → Unrestricted")
            appendLine()
            appendLine("2. Background Data (if available)")
            appendLine("   Settings → Apps → Child Monitor")
            appendLine("   → Mobile data & Wi-Fi")
            appendLine("   → Unrestricted data usage")
            appendLine()
            appendLine("Why this matters:")
            appendLine("Android may stop apps in the background to save battery. This causes the monitor to disconnect overnight or during charging.")
            appendLine()
            appendLine("Setting the app to 'Unrestricted' prevents this and ensures reliable operation.")
        }

        AlertDialog.Builder(activity)
            .setTitle("Battery Settings Guide")
            .setMessage(instructions)
            .setPositiveButton("Open Settings") { _, _ ->
                openBatteryOptimizationSettings(activity)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Open the battery optimization settings page for this app
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Try to open the specific app battery settings page
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            // Fallback: open general battery optimization settings
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Last resort: open general app settings
                try {
                    val intent = Intent(Settings.ACTION_SETTINGS)
                    context.startActivity(intent)
                } catch (e3: Exception) {
                    // Can't open settings at all
                }
            }
        }
    }

    /**
     * Request to ignore battery optimizations (requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission)
     * Note: This is a more aggressive approach that directly requests the exemption.
     * We're using the gentler approach of guiding users to settings instead.
     */
    @Suppress("unused")
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(activity)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${activity.packageName}")
                    }
                    activity.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to opening settings manually
                    openBatteryOptimizationSettings(activity)
                }
            }
        }
    }
}
