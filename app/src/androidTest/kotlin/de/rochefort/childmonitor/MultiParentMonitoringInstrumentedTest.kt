package de.rochefort.childmonitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.PowerManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import de.rochefort.childmonitor.parent.PinnedTls
import de.rochefort.childmonitor.security.SessionAuthority
import de.rochefort.childmonitor.security.TlsIdentity
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import fi.iki.elonen.NanoWSD
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.rules.Timeout
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real child foreground service, pinned TLS, microphone, and independent parent socket owners. */
@RunWith(AndroidJUnit4::class)
class MultiParentMonitoringInstrumentedTest {
    private val testArguments = InstrumentationRegistry.getArguments()
    private val soakSeconds = testArguments.getString("monitorSoakSeconds")
        ?.toLongOrNull()?.coerceIn(1L, 86_400L) ?: 3L
    private val parentCount = testArguments.getString("monitorParentCount")
        ?.toIntOrNull()?.coerceIn(2, MonitorService.MAX_PARENT_AUDIO_CONNECTIONS) ?: 2
    private val renewalIntervalSeconds = testArguments.getString("monitorRenewalIntervalSeconds")
        ?.toLongOrNull()?.coerceIn(1L, 86_400L) ?: 300L
    private val restartCycles = testArguments.getString("monitorCaptureRestartCycles")
        ?.toIntOrNull()?.coerceIn(1, 200) ?: 3
    @get:Rule val timeout = Timeout(
        soakSeconds + restartCycles * parentCount + 90L, TimeUnit.SECONDS,
    )
    @get:Rule val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA,
    )
    private val context: Context = ApplicationProvider.getApplicationContext()

    private var addedBatteryExemption = false

    @Before fun allowUnattendedMonitoringOnTestDevice() {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
            shell("cmd deviceidle whitelist +${context.packageName}")
            addedBatteryExemption = true
        }
    }

    @After fun restoreTestDevicePowerPolicy() {
        if (addedBatteryExemption) shell("cmd deviceidle whitelist -${context.packageName}")
    }

    private fun shell(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes() }
        }
    }

    @Test fun twoParentPhonesKeepAccessThroughRenewalAndIndependentRevocation() {
        // CI's emulator has no nursery camera. Audio remains real AudioRecord data (silence).
        context.getSharedPreferences(MonitorService.MONITOR_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("cameraEnabled", false).commit()
        android.util.Log.i("MultiParentTest", "Launching child activity")
        val scenario = ActivityScenario.launch<MonitorActivity>(Intent(context, MonitorActivity::class.java))
        android.util.Log.i("MultiParentTest", "Child activity launched")
        val sockets = mutableListOf<Socket>()
        val audioTokens = mutableListOf<String>()
        val refreshTokens = mutableListOf<String>()
        val authority = SessionAuthority.getInstance(context)
        val parentA = "parent-${UUID.randomUUID()}"
        val parentB = "parent-${UUID.randomUUID()}"
        val parentIds = mutableListOf(parentA, parentB)
        var qr: String? = null
        try {
            android.util.Log.i("MultiParentTest", "Waiting for secure child listeners")
            awaitCondition("secure child listeners") {
                MonitorService.tlsListenerPort != null && MonitorService.tlsAudioListenerPort != null
            }
            android.util.Log.i("MultiParentTest", "Secure listeners ready")
            val pin = TlsIdentity.getInstance(context).fingerprintSha256()!!
            qr = authority.issueToken("instrumented-qr", true).getString("token")
            android.util.Log.i("MultiParentTest", "Issuing independent parent credentials")
            val a = request("/api/session/refresh-credential", pin, headers = mapOf(
                "Authorization" to "Bearer $qr", "X-Parent-Device-Id" to parentA,
            ))
            val b = request("/api/session/refresh-credential", pin, headers = mapOf(
                "Authorization" to "Bearer $qr", "X-Parent-Device-Id" to parentB,
            ))
            assertEquals(parentA, a.getString("deviceId"))
            assertEquals(parentB, b.getString("deviceId"))
            android.util.Log.i("MultiParentTest", "Opening both pinned audio streams")
            refreshTokens += a.getString("refreshToken")
            refreshTokens += b.getString("refreshToken")
            audioTokens += a.getString("token")
            audioTokens += b.getString("token")
            sockets += connectAudio(audioTokens[0], pin)
            sockets += connectAudio(audioTokens[1], pin)
            repeat(parentCount - 2) {
                val id = "parent-${UUID.randomUUID()}"
                parentIds += id
                val extra = request("/api/session/refresh-credential", pin, headers = mapOf(
                    "Authorization" to "Bearer $qr", "X-Parent-Device-Id" to id,
                ))
                refreshTokens += extra.getString("refreshToken")
                audioTokens += extra.getString("token")
                sockets += connectAudio(audioTokens.last(), pin)
            }
            awaitCondition("all connected parent phones") {
                status(pin).getInt("connectedParents") == parentCount
            }
            sockets.forEach(::readAudio)

            // An expired bearer can renew without PIN/QR, and B remains authenticated.
            authority.revokeToken(a.getString("token"))
            val renewedA = request("/api/session/refresh?deviceId=$parentA", pin,
                headers = mapOf("X-Refresh-Token" to a.getString("refreshToken")))
            assertNotNull(authority.validate(b.getString("token")))
            val superseded = sockets[0]
            try {
                // Keep the old stream open until the new one connects: a real network handoff.
                // The replacement must fit even with eight parents occupying every audio slot.
                audioTokens[0] = renewedA.getString("token")
                sockets[0] = connectAudio(audioTokens[0], pin)
                awaitCondition("all parents preserved after live handoff") {
                    status(pin).getInt("connectedParents") == parentCount
                }
            } finally { superseded.close() }

            // All parents disappear and return together during Wi-Fi recovery. The old capture's
            // teardown must not stop/release the replacement microphone while new parents listen.
            repeat(restartCycles) {
                sockets.forEach { it.close() }
                sockets.clear()
                for (token in audioTokens) sockets += connectAudio(token, pin)
                awaitCondition("parents recovered after shared network outage") {
                    status(pin).getInt("connectedParents") == parentCount
                }
                sockets.forEach(::readAudio)
            }

            verifyTalkbackKeepsParentsLive(pin, renewedA.getString("token"), sockets, parentCount)

            // Optional local burn-in: pass -e monitorSoakSeconds 21600 for a six-hour run.
            val until = SystemClock.elapsedRealtime() + soakSeconds * 1_000L
            var nextStatus = 0L
            var nextRenewal = SystemClock.elapsedRealtime() + renewalIntervalSeconds * 1_000L
            var renewalRounds = 0
            while (SystemClock.elapsedRealtime() < until) {
                sockets.forEach(::readAudio)
                if (SystemClock.elapsedRealtime() >= nextStatus) {
                    assertEquals(parentCount, status(pin).getInt("connectedParents"))
                    nextStatus = SystemClock.elapsedRealtime() + 10_000L
                }
                if (SystemClock.elapsedRealtime() >= nextRenewal) {
                    for (index in parentIds.indices) {
                        // Expire a phone's bearer while its old connection stays open. Its own
                        // permanent refresh proof must restore access without PIN/QR or evicting peers.
                        authority.revokeToken(audioTokens[index])
                        val renewed = request("/api/session/refresh?deviceId=${parentIds[index]}", pin,
                            headers = mapOf("X-Refresh-Token" to refreshTokens[index]))
                        audioTokens[index] = renewed.getString("token")
                        val previous = sockets[index]
                        try { sockets[index] = connectAudio(audioTokens[index], pin) }
                        finally { previous.close() }
                        awaitCondition("other parents preserved during repeated credential renewal") {
                            status(pin).getInt("connectedParents") == parentCount
                        }
                        sockets.forEach(::readAudio)
                    }
                    renewalRounds++
                    val progress = "Soak renewal round $renewalRounds: $parentCount parents live"
                    android.util.Log.i("MultiParentTest", progress)
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                        putString("stream", "$progress\n")
                    })
                    nextRenewal = SystemClock.elapsedRealtime() + renewalIntervalSeconds * 1_000L
                }
            }

            // The display roster can be lost independently of the authoritative credentials.
            // Explicit revoke must still close A and preserve B's permanent access.
            assertTrue(PairingManager(context).revokeDevice(parentA))
            assertTrue(request("/api/pairing/revoke?deviceId=$parentA", pin).getBoolean("success"))
            awaitCondition("only revoked parent's stream closed") { status(pin).getInt("connectedParents") == parentCount - 1 }
            readAudio(sockets[1])
            assertNotNull(authority.validate(audioTokens[1]))
            val renewedB = request("/api/session/refresh?deviceId=$parentB", pin,
                headers = mapOf("X-Refresh-Token" to b.getString("refreshToken")))
            assertNotNull(authority.validate(renewedB.getString("token")))
        } finally {
            sockets.forEach { runCatching { it.close() } }
            for (id in parentIds) {
                authority.revokeAllForDevice(id)
                PairingManager(context).revokeDevice(id)
            }
            qr?.let { authority.revokeToken(it) }
            scenario.close()
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    @Test fun browserOnlyParentsStartMicrophoneAndRevocationPreservesOtherListener() {
        context.getSharedPreferences(MonitorService.MONITOR_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("cameraEnabled", false).commit()
        val scenario = ActivityScenario.launch<MonitorActivity>(Intent(context, MonitorActivity::class.java))
        val authority = SessionAuthority.getInstance(context)
        val parentIds = List(2) { "parent-${UUID.randomUUID()}" }
        val sockets = mutableListOf<Socket>()
        var qr: String? = null
        try {
            awaitCondition("browser TLS listener") { MonitorService.tlsListenerPort != null }
            val pin = TlsIdentity.getInstance(context).fingerprintSha256()!!
            assertFalse(status(pin).getBoolean("recording"))
            qr = authority.issueToken("browser-test-qr", true).getString("token")
            for (parent in parentIds) {
                val session = request("/api/session/refresh-credential", pin, headers = mapOf(
                    "Authorization" to "Bearer $qr", "X-Parent-Device-Id" to parent,
                ))
                sockets += connectWebSocket(session.getString("token"), pin, "/ws/audio")
            }
            awaitCondition("browser-only microphone capture") {
                val state = status(pin)
                state.getInt("connectedParents") == 2 && state.getBoolean("recording")
            }
            // Receive-only browser sockets must outlive NanoHTTPD's 5s inbound read timeout.
            val listeningUntil = SystemClock.elapsedRealtime() + 6_000L
            while (SystemClock.elapsedRealtime() < listeningUntil) {
                sockets.forEach { assertTrue(NanoWSD.WebSocketFrame.read(it.getInputStream()).binaryPayload.isNotEmpty()) }
            }
            assertTrue(request("/api/pairing/revoke?deviceId=${parentIds[0]}", pin).getBoolean("success"))
            awaitCondition("only revoked browser parent disconnected") { status(pin).getInt("connectedParents") == 1 }
            repeat(3) { assertTrue(NanoWSD.WebSocketFrame.read(sockets[1].getInputStream()).binaryPayload.isNotEmpty()) }
            sockets[1].close()
            awaitCondition("capture stopped after last browser parent left") {
                val state = status(pin)
                state.getInt("connectedParents") == 0 && !state.getBoolean("recording")
            }
        } finally {
            sockets.forEach { runCatching { it.close() } }
            parentIds.forEach { authority.revokeAllForDevice(it); PairingManager(context).revokeDevice(it) }
            qr?.let { authority.revokeToken(it) }
            scenario.close()
            context.stopService(Intent(context, MonitorService::class.java))
        }
    }

    private fun verifyTalkbackKeepsParentsLive(
        pin: String, bearer: String, audioSockets: List<Socket>, parentCount: Int,
    ) {
        val talkback = connectWebSocket(bearer, pin)
        val sendingAudio = AtomicBoolean(true)
        val running = AtomicBoolean(true)
        val sender = Thread({
            try {
                while (running.get()) {
                    val frame = if (sendingAudio.get()) {
                        NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Binary, true, ByteArray(800) { 0xff.toByte() })
                    } else {
                        // Keep the WebSocket alive without talkback audio. Recovery must come from
                        // packet inactivity, not the HTTP server's 5-second socket read timeout.
                        NanoWSD.WebSocketFrame(NanoWSD.WebSocketFrame.OpCode.Ping, true, ByteArray(0))
                    }
                    frame.maskingKey = ByteArray(4).also(java.security.SecureRandom()::nextBytes)
                    frame.write(talkback.getOutputStream())
                    Thread.sleep(100)
                }
            } catch (_: Exception) { running.set(false) }
        }, "instrumented-talkback-sender").apply { isDaemon = true; start() }
        try {
            awaitCondition("talkback started") { status(pin).getBoolean("talkbackPlaying") }
            assertFalse("Talkback must not persist a volume-boost recovery marker when no boost occurred",
                context.getSharedPreferences("TalkbackPreferences", Context.MODE_PRIVATE)
                    .contains("preBoostMusicVolume"))
            val until = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < until) {
                audioSockets.forEach(::readAudio)
                assertTrue("Talkback sender stopped", running.get())
            }
            assertEquals(parentCount, status(pin).getInt("connectedParents"))
            sendingAudio.set(false)
            awaitCondition("nursery mic recovered while talkback socket remained open", timeoutMs = 4_000L) {
                !status(pin).getBoolean("talkbackPlaying")
            }
            assertTrue("Socket pings should still be sending", running.get())
            audioSockets.forEach(::readAudio)
            assertEquals(parentCount, status(pin).getInt("connectedParents"))
        } finally {
            running.set(false)
            sender.interrupt()
            talkback.close()
            sender.join(2_000)
        }
    }

    private fun connectWebSocket(token: String, pin: String, path: String = "/ws/talkback"): Socket {
        val port = MonitorService.tlsListenerPort!!
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            plain.soTimeout = 5_000
            val socket = PinnedTls.upgrade(plain, "127.0.0.1", port, pin)
            socket.soTimeout = 5_000
            val nonce = android.util.Base64.encodeToString(
                ByteArray(16).also(java.security.SecureRandom()::nextBytes), android.util.Base64.NO_WRAP,
            )
            socket.getOutputStream().write((
                "GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Key: $nonce\r\nAuthorization: Bearer $token\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII))
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val byte = socket.getInputStream().read()
                assertTrue(byte >= 0)
                header.append(byte.toChar())
                assertTrue(header.length < 8_192)
            }
            assertTrue(header.startsWith("HTTP/1.1 101"))
            return socket
        } catch (e: Throwable) { plain.close(); throw e }
    }

    private fun connectAudio(token: String, pin: String): Socket {
        val port = MonitorService.tlsAudioListenerPort!!
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress("127.0.0.1", port), 5_000)
            plain.soTimeout = 5_000
            val socket = PinnedTls.upgrade(plain, "127.0.0.1", port, pin)
            socket.soTimeout = 5_000
            socket.getOutputStream().write("CMHELLO 1 $token\n".toByteArray(Charsets.US_ASCII))
            val input = PushbackInputStream(socket.getInputStream(), 128)
            assertEquals(AudioStreamPreamble.Result.AUDIO, AudioStreamPreamble.probe(input))
            return socket
        } catch (e: Throwable) { plain.close(); throw e }
    }

    private fun readAudio(socket: Socket) {
        val bytes = ByteArray(1_024)
        var used = 0
        while (used < bytes.size) {
            val read = socket.getInputStream().read(bytes, used, bytes.size - used)
            assertTrue("Parent audio stream closed", read > 0)
            used += read
        }
    }

    private fun status(pin: String): JSONObject = request("/api/status", pin, method = "GET")

    private fun request(
        path: String, pin: String, method: String = "POST", headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val conn = URL("https://127.0.0.1:${MonitorService.tlsListenerPort}$path")
            .openConnection() as HttpsURLConnection
        try {
            PinnedTls.apply(conn, pin)
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty(
                de.rochefort.childmonitor.security.LocalControlAuthority.HEADER,
                de.rochefort.childmonitor.security.LocalControlAuthority.token,
            )
            headers.forEach { (key, value) -> conn.setRequestProperty(key, value) }
            assertEquals("Request $path", 200, conn.responseCode)
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally { conn.disconnect() }
    }

    private fun awaitCondition(label: String, timeoutMs: Long = 30_000L, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for $label", condition())
    }
}
