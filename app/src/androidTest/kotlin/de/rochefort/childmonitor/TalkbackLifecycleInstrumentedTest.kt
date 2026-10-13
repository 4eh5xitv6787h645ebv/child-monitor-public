package de.rochefort.childmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TalkbackLifecycleInstrumentedTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)
    @get:Rule val timeout = Timeout(45, TimeUnit.SECONDS)

    @Test fun rapidReleaseAndPressPreservesNewMicrophoneAndFailureResetsButton() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val frames = ConcurrentHashMap<Any, AtomicInteger>()
        val server = WebControlServer(
            11000, { JSONObject() }, { null },
            talkbackAudioHandler = { source, _ -> frames.computeIfAbsent(source) { AtomicInteger() }.incrementAndGet() },
        )
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val intent = Intent(context, ListenActivity::class.java)
                .putExtra("address", "127.0.0.1").putExtra("deviceId", "talkback-lifecycle-test")
                .putExtra("autoReconnect", false)
            ActivityScenario.launch<ListenActivity>(intent).use { scenario ->
                scenario.onActivity { press(it, MotionEvent.ACTION_DOWN) }
                await { frames.size == 1 }
                repeat(3) { cycle ->
                    val oldSources = frames.keys.toSet()
                    scenario.onActivity {
                        press(it, MotionEvent.ACTION_UP)
                        press(it, MotionEvent.ACTION_DOWN)
                    }
                    await { frames.size == cycle + 2 }
                    val newSource = frames.keys.single { it !in oldSources }
                    val count = frames.getValue(newSource).get()
                    // A stopped worker's final cleanup must leave this attempt running.
                    await { frames.getValue(newSource).get() >= count + 3 }
                    scenario.onActivity {
                        assertEquals(context.getString(R.string.talking), it.findViewById<Button>(R.id.talkBackButton).text.toString())
                        assertTrue(boundService(it).playbackMuted)
                    }
                }
                server.stop()
                await {
                    var idle = false
                    scenario.onActivity {
                        idle = it.findViewById<Button>(R.id.talkBackButton).text.toString() == context.getString(R.string.holdToTalk)
                    }
                    idle
                }
                scenario.onActivity { assertFalse(boundService(it).playbackMuted) }
            }
        } finally {
            server.stop()
            context.stopService(Intent(context, ListenService::class.java))
        }
    }

    private fun boundService(activity: ListenActivity): ListenService =
        ListenActivity::class.java.getDeclaredField("listenService").let {
            it.isAccessible = true
            it.get(activity) as ListenService
        }

    private fun press(activity: ListenActivity, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 5f, 5f, 0)
        try { activity.findViewById<Button>(R.id.talkBackButton).dispatchTouchEvent(event) }
        finally { event.recycle() }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(30)
        assertTrue("Talkback did not reach expected state", condition())
    }
}
