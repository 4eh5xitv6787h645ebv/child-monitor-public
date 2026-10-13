package de.rochefort.childmonitor

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/** Exercises the parent's actual AudioTrack and foreground reconnect loop while its screen sleeps. */
@RunWith(AndroidJUnit4::class)
class ParentPlaybackInstrumentedTest {
    @get:Rule val timeout = Timeout(45, TimeUnit.SECONDS)

    @Test fun playbackReconnectsAfterStreamLossWithScreenOff() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val server = ServerSocket(0)
        val running = AtomicBoolean(true)
        val accepted = AtomicInteger()
        val active = AtomicReference<Socket?>()
        val writer = Thread {
            while (running.get()) {
                try {
                    server.accept().use { socket ->
                        active.set(socket)
                        accepted.incrementAndGet()
                        val silence = ByteArray(800) { 0xff.toByte() }
                        while (running.get() && !socket.isClosed) {
                            socket.getOutputStream().write(silence)
                            SystemClock.sleep(100)
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Closing the current stream simulates a dropped connection; accept its replacement.
                }
            }
        }.apply { isDaemon = true; start() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
                it.copyTo(java.io.OutputStream.nullOutputStream())
            }
        }
        try {
            val intent = Intent(context, ListenActivity::class.java)
                .putExtra("address", "127.0.0.1")
                .putExtra("port", server.localPort)
                .putExtra("deviceId", "playback-test-${UUID.randomUUID()}")
                .putExtra("autoReconnect", false)
            ActivityScenario.launch<ListenActivity>(intent).use { scenario ->
                var service: ListenService? = null
                var activityRef: ListenActivity? = null
                await {
                    scenario.onActivity { activity ->
                        activityRef = activity
                        service = ListenActivity::class.java.getDeclaredField("listenService").let {
                            it.isAccessible = true
                            it.get(activity) as? ListenService
                        }
                    }
                    service?.isAudioPlaying == true
                }
                shell("input keyevent KEYCODE_SLEEP")
                val power = context.getSystemService(PowerManager::class.java)
                await { !power.isInteractive }
                active.getAndSet(null)?.close()
                await { accepted.get() >= 2 && service?.isAudioPlaying == true }
                // Let AudioTrack consume several additional packets after recovery.
                SystemClock.sleep(700)
                assertTrue("Playback should remain active while the parent screen sleeps", service?.isAudioPlaying == true)
                // Finish while still asleep. Waking before ActivityScenario.close races Android's
                // transient RESTARTED/STARTED stages, which ActivityScenario cannot map to a state.
                InstrumentationRegistry.getInstrumentation().runOnMainSync { activityRef!!.finish() }
                await {
                    var destroyed = false
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        destroyed = ActivityLifecycleMonitorRegistry.getInstance()
                            .getLifecycleStageOf(activityRef!!) == Stage.DESTROYED
                    }
                    destroyed
                }
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
            shell("wm dismiss-keyguard")
            context.stopService(Intent(context, ListenService::class.java))
            running.set(false)
            active.getAndSet(null)?.close()
            server.close()
            writer.join(1_000)
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Parent playback did not recover", condition())
    }
}
