package de.rochefort.childmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import de.rochefort.childmonitor.parent.PinnedTls
import de.rochefort.childmonitor.parent.SessionRenewer
import de.rochefort.childmonitor.security.SessionAuthority
import de.rochefort.childmonitor.security.TlsIdentity
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run prepare, force-stop the app externally, then restore with requireProcessRestart=true. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ProcessRestartPairingInstrumentedTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
    @get:Rule val timeout = Timeout(60, TimeUnit.SECONDS)
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val metadata get() = context.getSharedPreferences("ProcessRestartTestMetadata", Context.MODE_PRIVATE)
    private val childSlots = listOf("process-restart-test-a", "process-restart-test-b")

    @Test fun a_prepareTwoIndependentParents() = withChild { pin ->
        cleanupFixture()
        val authority = SessionAuthority.getInstance(context)
        val qr = authority.issueToken("process-restart-test-qr", true).getString("token")
        try {
            childSlots.forEach { child ->
                val parent = "parent-${UUID.randomUUID()}"
                val connection = URL("https://127.0.0.1:${MonitorService.tlsListenerPort}/api/session/refresh-credential")
                    .openConnection() as HttpsURLConnection
                val bundle = try {
                    PinnedTls.apply(connection, pin)
                    connection.requestMethod = "POST"
                    connection.connectTimeout = 5_000
                    connection.readTimeout = 5_000
                    connection.setRequestProperty("Authorization", "Bearer $qr")
                    connection.setRequestProperty("X-Parent-Device-Id", parent)
                    assertEquals(200, connection.responseCode)
                    JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                } finally { connection.disconnect() }
                ParentSessionStore.putTlsPin(context, child, pin, MonitorService.tlsListenerPort, MonitorService.tlsAudioListenerPort)
                assertTrue(ParentSessionStore.putRefreshToken(
                    context, child, bundle.getString("refreshToken"), System.currentTimeMillis(), Long.MAX_VALUE,
                    refreshDeviceId = parent, bearerToken = bundle.getString("token"),
                ))
                assertTrue(metadata.edit().putString(child, parent).commit())
                assertAudio(child, pin)
            }
            assertTrue(metadata.edit().putInt("preparingPid", android.os.Process.myPid()).commit())
        } finally { authority.revokeToken(qr) }
    }

    @Test fun b_restoreBothParentsWithoutPairingAgain() = withChild { pin ->
        val sockets = mutableListOf<Socket>()
        try {
            if (InstrumentationRegistry.getArguments().getString("requireProcessRestart") == "true") {
                assertNotEquals("Validation must run in a fresh app process", metadata.getInt("preparingPid", -1), android.os.Process.myPid())
            }
            val authority = SessionAuthority.getInstance(context)
            authority.reload()
            childSlots.forEach { child ->
                assertNotNull("Prepared credential metadata must survive restart", metadata.getString(child, null))
                assertEquals(pin, ParentSessionStore.fingerprintFor(context, child))
                assertNotNull(ParentSessionStore.getRefreshToken(context, child))
                // Short-lived bearer loss must not require the user's PIN or another QR scan.
                ParentSessionStore.get(context, child)?.let(authority::revokeToken)
                assertEquals(SessionRenewer.RenewResult.RENEWED, SessionRenewer.renewWithResult(context, child, "127.0.0.1"))
                sockets += connectAudio(child, pin)
            }
            sockets.forEach(::assertAudioFrame)
            val status = ParentControlClient(context, "127.0.0.1", childSlots.first()).get("/api/status")
            assertNotNull(status)
            assertEquals(2, status!!.getInt("connectedParents"))
        } finally {
            sockets.forEach { runCatching { it.close() } }
            cleanupFixture()
        }
    }

    private fun assertAudio(child: String, pin: String) {
        connectAudio(child, pin).use(::assertAudioFrame)
    }

    private fun connectAudio(child: String, pin: String): Socket {
        val port = MonitorService.tlsAudioListenerPort!!
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            plain.soTimeout = 5_000
            val socket = PinnedTls.upgrade(plain, "127.0.0.1", port, pin)
            val token = ParentSessionStore.get(context, child)!!
            socket.getOutputStream().write("CMHELLO 1 $token\n".toByteArray())
            socket.getOutputStream().flush()
            return socket
        } catch (failure: Exception) {
            plain.close()
            throw failure
        }
    }

    private fun assertAudioFrame(socket: Socket) {
        val frame = ByteArray(800)
        val read = socket.getInputStream().read(frame)
        assertTrue("Restored parent must receive actual audio", read > 0)
        assertFalse("Restored credential must authenticate", String(frame, 0, read).startsWith("CMERROR"))
    }

    private fun cleanupFixture() {
        val authority = SessionAuthority.getInstance(context)
        childSlots.forEach { child ->
            metadata.getString(child, null)?.let { owner ->
                authority.revokeAllForDevice(owner)
                PairingManager(context).revokeDevice(owner)
            }
            ParentSessionStore.forgetAll(context, child)
        }
        metadata.edit().clear().commit()
    }

    private fun withChild(test: (String) -> Unit) {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val addExemption = !power.isIgnoringBatteryOptimizations(context.packageName)
        if (addExemption) shell("cmd deviceidle whitelist +${context.packageName}")
        context.getSharedPreferences(MonitorService.MONITOR_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("cameraEnabled", false).commit()
        val scenario = ActivityScenario.launch<MonitorActivity>(Intent(context, MonitorActivity::class.java))
        try {
            val until = SystemClock.elapsedRealtime() + 20_000
            while ((MonitorService.tlsListenerPort == null || MonitorService.tlsAudioListenerPort == null) &&
                SystemClock.elapsedRealtime() < until) SystemClock.sleep(50)
            assertNotNull(MonitorService.tlsListenerPort)
            assertNotNull(MonitorService.tlsAudioListenerPort)
            test(TlsIdentity.getInstance(context).fingerprintSha256()!!)
        } finally {
            scenario.close()
            context.stopService(Intent(context, MonitorService::class.java))
            // stopService schedules onDestroy asynchronously. Do not hand the next test a
            // still-bound child control port while teardown is releasing its listeners.
            val stoppedBy = SystemClock.elapsedRealtime() + 5_000
            while ((MonitorService.tlsListenerPort != null || MonitorService.tlsAudioListenerPort != null) &&
                SystemClock.elapsedRealtime() < stoppedBy) SystemClock.sleep(25)
            assertNull("Child HTTPS listener must stop before the next fixture", MonitorService.tlsListenerPort)
            assertNull("Child audio listener must stop before the next fixture", MonitorService.tlsAudioListenerPort)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (addExemption) shell("cmd deviceidle whitelist -${context.packageName}")
        }
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).use { input -> input.readBytes() }
        }
    }
}
