package de.rochefort.childmonitor

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.rochefort.childmonitor.security.AppLock
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level regression coverage for the app-lock's fail-safe no-keyguard path (#375). */
@RunWith(AndroidJUnit4::class)
class AppLockInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun cleanUp() {
        AppLock.setEnabled(context, false)
        AppLock.consumeAutoDisabledNotice(context)
        AppLock.gate.onUnlockSucceeded()
    }

    @Test
    fun missingDeviceLockDisablesAppLockAndSurfacesOneNotice() {
        // The CI safety emulators intentionally have no PIN/keyguard. A developer running this on
        // a secured personal device skips instead of opening an interactive system prompt.
        assumeFalse(AppLock.isDeviceSecure(context))
        AppLock.consumeAutoDisabledNotice(context)
        AppLock.setEnabled(context, true)
        AppLock.gate.requireReauth()

        ActivityScenario.launch(LockActivity::class.java).use {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }

        assertFalse(AppLock.isEnabled(context))
        assertTrue(AppLock.consumeAutoDisabledNotice(context))
        assertFalse("notice must be one-shot", AppLock.consumeAutoDisabledNotice(context))
    }
}
