package de.rochefort.childmonitor

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/** Device-admin backed screen lock for the end of a night-light/soother timer. */
object ScreenOffController {
    private const val TAG = "ScreenOffController"

    private fun admin(context: Context) = ComponentName(context, ScreenOffAdminReceiver::class.java)

    fun isEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        return manager.isAdminActive(admin(context))
    }

    fun activationIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
            .putExtra(
                DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                context.getString(R.string.screenOffAdminExplanation),
            )

    /** Lock immediately when authorised; false means the normal display timeout is the fallback. */
    fun lockNow(context: Context): Boolean {
        val manager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return false
        if (!manager.isAdminActive(admin(context))) {
            Log.w(TAG, "Bedtime timer ended without screen-lock permission; using display timeout")
            return false
        }
        return try {
            manager.lockNow()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Device admin could not lock screen", e)
            false
        }
    }
}
