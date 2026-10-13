package de.rochefort.childmonitor

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.nsd.NsdServiceInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLifecycleSmokeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun childControlsInflatesAcrossSupportedApiLevels() {
        val intent = Intent(context, ChildControlsActivity::class.java)
            .putExtra(ChildControlsActivity.EXTRA_ADDRESS, "127.0.0.1:65534")
            .putExtra(ChildControlsActivity.EXTRA_DEVICE_ID, "instrumented-child")

        ActivityScenario.launch<ChildControlsActivity>(intent).use { scenario ->
            assertTrue(scenario.state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED))
            scenario.onActivity { activity ->
                // #490: both independent timer controls stay visible even when neither sound nor
                // light is currently on; the old UI rendered a timer only inside the sound card.
                assertTrue(activity.findViewById<android.view.View>(R.id.nightLightTimerControls).isShown)
                assertTrue(activity.findViewById<android.view.View>(R.id.sootherTimerControls).isShown)
                assertNotNull(activity.findViewById<android.view.View>(R.id.nightLightTimerStatus))
                assertNotNull(activity.findViewById<android.view.View>(R.id.sootherTimerStatus))
                for (id in intArrayOf(
                    R.id.nightLightTimerFor,
                    R.id.nightLightTimerUntil,
                    R.id.nightLightTimerClear,
                    R.id.sootherTimerFor,
                    R.id.sootherTimerUntil,
                    R.id.sootherTimerClear,
                )) {
                    assertTrue(activity.findViewById<android.view.View>(id).isShown)
                }
            }
        }
    }

    @Test
    fun nsdResolverLoadsAcrossSupportedApiLevels() {
        // An unresolved service has no usable address across the supported Android versions.
        assertTrue(NsdResolver.addresses(NsdServiceInfo()).isEmpty())
    }

    @Test
    fun safetyCriticalServicesAndPermissionsAreDeclared() {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        val requested = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.RECORD_AUDIO in requested)
        assertTrue(Manifest.permission.CAMERA in requested)
        assertTrue(Manifest.permission.RECEIVE_BOOT_COMPLETED in requested)

        for (service in listOf(MonitorService::class.java, ListenService::class.java, BootService::class.java)) {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getServiceInfo(
                    ComponentName(context, service),
                    PackageManager.ComponentInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getServiceInfo(ComponentName(context, service), 0)
            }
            assertTrue(info.enabled)
            assertFalse(info.exported)
        }
    }

    @Test
    fun bootWaitsForUnlockedBootThenSchedulesStableWindow() {
        cancelDeferredBoot()
        val prefs = context.getSharedPreferences("ListenPreferences", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putBoolean(AutoStartGate.KEY_CHILD, true)
            .commit()

        BootReceiver().onReceive(context, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        assertNull(findDeferredBoot())

        BootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNotNull(findDeferredBoot())
        cancelDeferredBoot()
    }

    private fun findDeferredBoot(): PendingIntent? = PendingIntent.getBroadcast(
        context,
        DEFERRED_BOOT_REQUEST_CODE,
        Intent(context, DeferredBootReceiver::class.java)
            .setAction(DeferredBootReceiver.ACTION_START_AFTER_STABLE_BOOT),
        PendingIntent.FLAG_NO_CREATE or immutableFlag(),
    )

    private fun cancelDeferredBoot() {
        val pending = findDeferredBoot() ?: return
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending)
        pending.cancel()
    }

    private fun immutableFlag(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

    companion object {
        private const val DEFERRED_BOOT_REQUEST_CODE = 0xB007
    }
}
