/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Child Monitor. If not, see <http://www.gnu.org/licenses/>.
 */
package de.rochefort.childmonitor

import de.rochefort.childmonitor.LogManager.LogCategory
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import de.rochefort.childmonitor.util.ForegroundServiceTypePolicy
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.nsd.NsdManager
import android.net.nsd.NsdManager.RegistrationListener
import android.net.nsd.NsdServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class MonitorService : LifecycleService() {
    private val binder: IBinder = MonitorBinder()
    private lateinit var nsdManager: NsdManager
    // [#235] @Volatile + nsdLock: NSD (re)registration is driven from the network-available
    // callback (refreshNsdRegistration), the heartbeat self-heal, and the monitor thread.
    // Without serialization two of them can both see registrationListener == null and both call
    // nsdManager.registerService for the same service name, leaking one listener and churning
    // the advertised name on Wi-Fi handoff.
    @Volatile private var registrationListener: RegistrationListener? = null
    private val nsdLock = Any()
    // [#234] @Volatile: written by the monitor thread / NSD callbacks and read by the heartbeat
    // self-heal loop without a shared lock, so they need a happens-before for visibility.
    @Volatile private var currentSocket: ServerSocket? = null
    @Volatile private var tlsAudioSocket: ServerSocket? = null
    // [#237] Bounds concurrent pre-auth connection handlers on the raw audio port so a TCP
    // connection flood can't spawn unbounded threads and starve the foreground service.
    private val connectionLimiter = ConnectionLimiter(maxTotal = 32, maxPerIp = MAX_PARENT_AUDIO_CONNECTIONS * 2)
    @Volatile private var connectionToken: Any? = null
    private var currentPort = 0
    private lateinit var notificationManager: NotificationManager
    @Volatile private var monitorThread: Thread? = null
    @Volatile private var tlsAudioThread: Thread? = null
    @Volatile private var tlsAudioToken: Any? = null
    // [#234] Serialises ensureMonitorThread() so concurrent callers (onStartCommand, the
    // heartbeat self-heal, and the network-available callback) can't each start a monitor
    // thread and race to bind the audio ServerSocket on the same port.
    private val monitorThreadLock = Any()
    private val tlsAudioThreadLock = Any()
    private var audioRecordThread: Thread? = null
    var monitorActivity: MonitorActivity? = null

    // Track all connected parent devices
    private val connectedParents = CopyOnWriteArrayList<ParentConnection>()
    // #284: rolling ~60s ring of recent mu-law audio for post-alert replay ("what just happened").
    private val audioReplay = de.rochefort.childmonitor.audio.AudioReplayBuffer(maxSeconds = 60)
    // #337: rolling ring of recent JPEG snapshot frames — the visual half of post-alert replay.
    private val snapshotReplay = de.rochefort.childmonitor.video.SnapshotReplayBuffer()
    // #286: on-device cry/sound detector fed from the capture loop; alert-worthy events fire the
    // event hooks (#308) and surface in /api/status.soundEvent.
    private val soundDetector = de.rochefort.childmonitor.events.SoundEventDetector()
    private data class StoredSoundEvent(
        val event: de.rochefort.childmonitor.events.SoundEventDetector.Event,
        val storeId: Long,
        val occurredAtEpochMs: Long,
        val observedAtElapsedMs: Long,
    )
    @Volatile private var lastSoundEvent: StoredSoundEvent? = null
    @Volatile private var lastSoundAlertMs = 0L
    private data class StoredMotionEvent(
        val event: de.rochefort.childmonitor.events.MotionEventDetector.Event,
        val storeId: Long,
        val occurredAtEpochMs: Long,
        val observedAtElapsedMs: Long,
    )
    @Volatile private var lastMotionEvent: StoredMotionEvent? = null
    private lateinit var eventStore: de.rochefort.childmonitor.events.EventStore
    private var mqttIntegration: de.rochefort.childmonitor.integrations.mqtt.MqttIntegration? = null
    @Volatile private var lastEnvironmentAlert: de.rochefort.childmonitor.integrations.mqtt.EnvironmentReading.Alert? = null
    // #291: per-trigger alert toggles + quiet-hours suppression, applied in onSoundEvent. Loaded
    // from prefs in onCreate, configured via POST /api/alerts/config. Held as one immutable value
    // and swapped atomically so the audio thread always reads a consistent combination of all three
    // fields. Defaults preserve the prior behavior (both triggers on, quiet hours off → alert 24/7)
    // so an un-configured install is unchanged.
    @Volatile private var alertConfig = de.rochefort.childmonitor.events.AlertConfig()
    private var audioRecord: AudioRecord? = null
    // Best-effort suppression of the child's own soother speaker from the parent-bound mic stream.
    private var sootherEchoCanceler: AcousticEchoCanceler? = null
    @Volatile private var isRecording = false
    // #299: SystemClock.elapsedRealtime() of the last AudioRecord.read() that returned PCM.
    // The heartbeat watchdog restarts capture if this goes stale while streaming — i.e. the
    // mic stopped delivering bytes (read() wedged/blocking, or returning errors), which would
    // otherwise stream nothing while the TCP link stays up so the parent never detects it.
    // Monotonic clock so an overnight NTP/wall-clock jump can't spuriously trip it. 0 = no
    // data yet (fresh start), never treated as stale.
    // NOTE: detects "no bytes captured", NOT "captured audio is silent" — all-zero PCM from a
    // quiet nursery is indistinguishable from a muted mic, so content-silence is out of scope
    // by design (restarting on a quiet room would thrash all night).
    @Volatile private var lastAudioReadMs = 0L
    // Guards audioRecord lifecycle (start/stop) — otherwise heartbeat-prune,
    // serviceConnection's first-parent path, and the audio-loop teardown can
    // race on audioRecord.release(), causing double-release or use-after-release.
    private val audioRecordLock = Any()
    private val captureStartPending = java.util.concurrent.atomic.AtomicBoolean(false)

    // Network monitoring for diagnostics
    private var networkMonitor: NetworkStateMonitor? = null
    private var batteryMonitor: BatteryStateMonitor? = null

    // Audio streaming metrics - atomic so they can be read from web server / status JSON safely
    private val totalBytesSent = AtomicLong(0)
    private val audioPacketsSent = AtomicLong(0)
    private var lastMetricsLogTime: Long = 0

    // Cap parent connections to prevent thread-explosion DoS from a misbehaving peer
    private val maxParents = MAX_PARENT_AUDIO_CONNECTIONS

    // Single Handler for all delayed-task scheduling on the main looper. Held as a field
    // so onDestroy() can call removeCallbacksAndMessages(null) to cancel anything pending —
    // otherwise the audio-recorder restart, camera (re)start, etc. can fire after teardown
    // (#184B). All postDelayed lambdas additionally check isServiceAlive before doing work.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var isServiceAlive = true

    // Service lifecycle and heartbeat tracking
    private var serviceStartTime: Long = 0
    private var lastHeartbeatTime: Long = 0
    private var heartbeatThread: Thread? = null
    @Volatile private var isHeartbeatActive = false
    private val audioRecorderRestartDelayMs = 1000L

    // CM-FEAT-007 (#132): retention runs once per hour from the heartbeat tick.
    private var lastRetentionRunMs: Long = 0L
    private val RETENTION_INTERVAL_MS = 60L * 60L * 1000L
    private val captureTargetRms = 5200.0
    private val captureMinGain = 1.5f
    private val captureMaxGain = 5.5f
    private val captureLimiterPeak = 30_500.0f
    private val captureNoiseFloorRms = 110.0
    @Volatile private var captureCalibrationMultiplier = AudioCalibrationPrefs.DEFAULT_CAPTURE_GAIN_MULTIPLIER

    // NSD re-registration to keep service discoverable
    private var lastNsdRegistrationTime: Long = 0
    private val nsdRefreshInterval = 2 * 60 * 1000L  // Re-register every 2 minutes

    // Web console and camera
    // Review-pass-2 HIGH-2: @Volatile because the heartbeat thread now reads
    // these to drive the TLS-listener retry, and NanoHTTPD's httpsPortProvider
    // lambda reads webServerTls from its IO threads on every /api/fingerprint.
    @Volatile private var webServer: WebControlServer? = null
    @Volatile private var webServerTls: WebControlServer? = null
    private val webServerLock = Any()
    // Review-pass-2 MED-1: when TlsIdentity.lastInitError starts with "TLS
    // unsupported" the retry can't help — that's pre-API-23 or similar. Skip
    // the heartbeat retry once we've recorded this so we don't log "TLS
    // unavailable" 720 times over an overnight monitor session.
    @Volatile private var tlsPermanentlyUnavailable: Boolean = false
    // Review-pass-3 HIGH-1: emit the "TLS not ready yet" INFO line only on the
    // first observation per recoverable-failure streak, instead of every 60s
    // heartbeat tick. Reset when the listener finally comes up (so the next
    // failure window also gets a fresh diagnostic line).
    @Volatile private var tlsRetryLogged: Boolean = false
    private lateinit var pairingManager: PairingManager
    private lateinit var sessionAuthority: de.rochefort.childmonitor.security.SessionAuthority
    private val castSessionAuthority = de.rochefort.childmonitor.cast.CastSessionAuthority()
    private var tlsIdentity: de.rochefort.childmonitor.security.TlsIdentity? = null
    private val authRateLimiter = de.rochefort.childmonitor.security.AuthRateLimiter()
    private var cameraStreamer: CameraStreamer? = null
    // #287: adaptive snapshot cadence — user-chosen mode layered over the battery/charging-aware
    // AdaptiveSnapshotController, plus a short-lived near-live "glance" for active watching.
    private val adaptiveSnapshot = de.rochefort.childmonitor.video.AdaptiveSnapshotController()
    @Volatile private var snapshotMode = de.rochefort.childmonitor.video.SnapshotCadencePolicy.DEFAULT_MODE
    @Volatile private var snapshotGlanceUntilMs = 0L
    // #311: soothing-sound (white/pink/brown noise) player on the child speaker, parent-controlled.
    private val sootherPlayer by lazy {
        de.rochefort.childmonitor.audio.SootherPlayer(applicationContext)
    }
    // #328: wall-clock deadline of the soother sleep timer (0 = none armed). Surfaced as remainingMs.
    @Volatile private var sootherAutoStopAtMs = 0L
    // #312: remote night-light state (child screen as a dim colour lamp). This service is the
    // source of truth; NightLightActivity renders it.
    @Volatile private var nightLightOn = false
    @Volatile private var nightLightColor = NightLightPolicy.DEFAULT_COLOR
    @Volatile private var nightLightBrightness = NightLightPolicy.DEFAULT_BRIGHTNESS
    @Volatile private var nightLightAutoStopAtMs = 0L
    private val nightLightLauncher by lazy { NightLightLauncher(this) }
    // #308: opt-in event hooks — broadcast Intents (Tasker) + a local webhook on monitor events.
    private var eventHooks: de.rochefort.childmonitor.events.EventHookDispatcher? = null
    private val nightLightClosedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            // The night-light activity was dismissed locally (BACK) → reflect off in state.
            nightLightOn = false
            cancelNightLightAutoStop()
            LogManager.i(LogCategory.SYSTEM, "Night light dismissed on device")
        }
    }
    // Single reusable glance-expiry revert; re-posted (not stacked) on each glance so repeated
    // near-live requests collapse to one pending callback aligned to the latest deadline.
    private val snapshotGlanceRevert = Runnable { if (isServiceAlive) recomputeSnapshotCadence() }
    private val webServerPort = 11000
    private val webServerTlsPort = 11443
    @Volatile private var cameraEnabled = true
    @Volatile private var cameraActive = false

    // Talkback audio playback (receive audio from parent)
    // [#228] @Volatile + talkbackLock: the AudioTrack lifecycle (create/assign/write/
    // stop/release) is driven from NanoWSD WebSocket worker threads (playTalkbackAudio,
    // and stopTalkback on socket close/exception) and the main thread (onDestroy). Without
    // mutual exclusion a concurrent write() and release() hit a freed native handle -> SIGSEGV.
    @Volatile private var talkbackTrack: AudioTrack? = null
    private val talkbackLock = Any()
    private val talkbackActivity = de.rochefort.childmonitor.audio.TalkbackActivityTracker(
        nowMs = android.os.SystemClock::elapsedRealtime,
    )
    private val talkbackIdleExecutor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "talkback-idle-watchdog").apply { isDaemon = true }
    }
    private var talkbackIdleFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private val talkbackSampleRate = 8000
    @Volatile private var isTalkbackPlaying = false

    // Power & WiFi locks — keep CPU + WiFi radio awake during long sessions so
    // Doze mode + default WiFi sleep policy can't kill the connection overnight.
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * NOT a data class — data-class equality would include the mutable bytesSent counter,
     * which means removeAll/contains could falsely miss a parent after writes happened.
     * Use referential / socket-identity equality instead.
     */
    private class ParentConnection(
        val socket: Socket,
        outputStream: OutputStream,
        val deviceId: String,
        onSent: (Int) -> Unit,
    ) {
        // Atomic so audio thread + status JSON thread can read/write concurrently
        val bytesSent = AtomicLong(0)
        val writer = de.rochefort.childmonitor.audio.BoundedAudioWriter(
            writeFrame = { frame ->
                outputStream.write(frame)
                bytesSent.addAndGet(frame.size.toLong())
                onSent(frame.size)
            },
            closeTransport = { socket.close() },
        )
    }

    /**
     * Close the socket and write a structured audit-log entry for a failed audio
     * authentication attempt. [reason] is a short token-free string identifying
     * the failure mode (format / protocol:X / version:N / token / read_timeout /
     * deadline / eof / io_error); [tokenPrefix] is up to 8 chars of the offered
     * bearer for correlation with SessionAuthority's prefix logs — never the full
     * token (review #3 + #10).
     */
    private fun closeAndAuditAudioAuthFail(
        socket: Socket,
        remoteAddress: String?,
        reason: String,
        tokenPrefix: String
    ) {
        de.rochefort.childmonitor.security.AuditLog.append(
            applicationContext,
            de.rochefort.childmonitor.security.AuditLog.EVT_AUTH_FAIL,
            mapOf(
                "uri" to "audio:$currentPort",
                "remote" to (remoteAddress ?: ""),
                "reason" to reason,
                "tokenPrefix" to tokenPrefix
            )
        )
        try {
            // Send a tiny "GO AWAY" line so a future curl/nc attacker sees the rejection.
            socket.getOutputStream().write("CMERROR 401 unauthorized\n".toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        } catch (_: Exception) {}
        try { socket.close() } catch (_: Exception) {}
    }

    private fun serviceConnection(socket: Socket, secureTransport: Boolean = false) {
        val remoteAddress = socket.inetAddress.hostAddress
        val remotePort = socket.port
        val connectionStartTime = System.currentTimeMillis()
        var addedConnection: ParentConnection? = null
        var shouldStartRecording = false
        var authenticatedDeviceId = ""

        // #188: require a CMHELLO handshake before entering the audio loop.
        // Anyone on the LAN can connect to port 10000 and previously got a free
        // live audio stream — classic CWE-306 / OWASP-Mobile-M3. The handshake
        // is a single line: "CMHELLO 1 <bearer_token>\n", validated against the
        // process-wide SessionAuthority. Failed/missing handshake closes the
        // socket within HELLO_TIMEOUT_MS. Authenticated parents proceed as before.
        try {
            socket.soTimeout = HELLO_PER_READ_TIMEOUT_MS
            val deadline = android.os.SystemClock.elapsedRealtime() + HELLO_TOTAL_TIMEOUT_MS
            val helloBuf = ByteArray(HELLO_MAX_BYTES)
            var read = 0
            val helloStream = socket.getInputStream()
            while (read < HELLO_MAX_BYTES) {
                // Review-finding #2: wall-clock deadline guard against slowloris.
                if (android.os.SystemClock.elapsedRealtime() > deadline) {
                    LogManager.w(LogCategory.CONNECTION,
                        "CMHELLO from $remoteAddress exceeded ${HELLO_TOTAL_TIMEOUT_MS}ms total budget — rejecting (slowloris?)")
                    closeAndAuditAudioAuthFail(socket, remoteAddress, "deadline", "")
                    return
                }
                val b = helloStream.read()
                if (b < 0) {
                    LogManager.w(LogCategory.CONNECTION, "Parent $remoteAddress closed before sending CMHELLO — rejecting")
                    closeAndAuditAudioAuthFail(socket, remoteAddress, "eof", "")
                    return
                }
                if (b == '\n'.code) break
                helloBuf[read++] = b.toByte()
            }
            val hello = String(helloBuf, 0, read, Charsets.UTF_8).trim('\r', ' ', '\t', '\n')
            val parts = hello.split(' ', limit = 3)
            val cmd = parts.getOrNull(0) ?: ""
            val session = if (parts.size == 3 && cmd == "CMHELLO" && parts[1] == "1") {
                sessionAuthority.validate(parts[2])
            } else null
            val accepted = parts.size == 3 &&
                cmd == "CMHELLO" &&
                parts[1] == "1" &&
                session != null
            if (!accepted) {
                // Review-finding #3: never log the rejected token — even an
                // expired legitimate token is a credential. Categorise the
                // rejection mode and keep at most an 8-char prefix for cross-
                // referencing with SessionAuthority's listSessions().
                val reason = when {
                    parts.size != 3 -> "format"
                    cmd != "CMHELLO" -> "protocol:${cmd.take(8)}"
                    parts[1] != "1" -> "version:${parts[1].take(4)}"
                    else -> "token"
                }
                val tokenPrefix = if (parts.size == 3) parts[2].take(8) else ""
                LogManager.w(LogCategory.CONNECTION,
                    "Rejecting unauthenticated audio stream connection from $remoteAddress (reason=$reason)")
                closeAndAuditAudioAuthFail(socket, remoteAddress, reason, tokenPrefix)
                return
            }
            if (!AudioTransportPolicy.acceptsSession(session!!.tlsAudioRequired, secureTransport)) {
                de.rochefort.childmonitor.security.AuditLog.append(
                    applicationContext,
                    de.rochefort.childmonitor.security.AuditLog.EVT_AUTH_FAIL,
                    mapOf(
                        "uri" to "audio:$currentPort",
                        "remote" to (remoteAddress ?: ""),
                        "reason" to "tls_required",
                        "tokenPrefix" to session.token.take(8)
                    )
                )
                LogManager.w(LogCategory.CONNECTION,
                    "Rejecting cleartext audio for TLS-required session from $remoteAddress")
                try {
                    socket.getOutputStream().write("CMERROR 426 tls-required\n".toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                } catch (_: Exception) {}
                try { socket.close() } catch (_: Exception) {}
                return
            }
            authenticatedDeviceId = session.deviceId
            socket.soTimeout = 0  // back to blocking reads for the audio loop
            LogManager.i(LogCategory.CONNECTION,
                "Parent $remoteAddress passed CMHELLO 1 handshake (${if (secureTransport) "TLS" else "legacy cleartext"})")
        } catch (e: java.net.SocketTimeoutException) {
            LogManager.w(LogCategory.CONNECTION,
                "CMHELLO per-read timeout from $remoteAddress after ${HELLO_PER_READ_TIMEOUT_MS}ms — rejecting")
            closeAndAuditAudioAuthFail(socket, remoteAddress, "read_timeout", "")
            return
        } catch (e: Exception) {
            LogManager.w(LogCategory.CONNECTION, "CMHELLO read failed from $remoteAddress: ${e.message}")
            closeAndAuditAudioAuthFail(socket, remoteAddress, "io_error", "")
            return
        }

        try {
            val outputStream = socket.getOutputStream()
            val connection = ParentConnection(
                socket, outputStream, authenticatedDeviceId,
                onSent = { bytes ->
                    totalBytesSent.addAndGet(bytes.toLong())
                    audioPacketsSent.incrementAndGet()
                },
            )

            val superseded = mutableListOf<ParentConnection>()
            synchronized(connectedParents) {
                // Independently paired phones have a stable parent identity. A fresh stream from
                // that phone replaces its own stale stream, even when all household slots are full.
                // Legacy QR sessions share the child ID; never deduplicate those across phones.
                if (authenticatedDeviceId.startsWith("parent-")) {
                    superseded += connectedParents.filter { it.deviceId == authenticatedDeviceId }
                    connectedParents.removeAll(superseded.toSet())
                }
                if (connectedParents.size >= maxParents) {
                    LogManager.w(
                        LogCategory.CONNECTION,
                        "Rejecting parent $remoteAddress — cap of $maxParents reached"
                    )
                    connection.writer.close()
                    return
                }
                connectedParents.add(connection)
                // #308: set only after a successful add so the finally's disconnect hook (and the
                // bytesSent log) never fire for a parent rejected at the cap.
                addedConnection = connection
                shouldStartRecording = connectedParents.size == 1
                Log.i(TAG, "Parent connected. Total parents: ${connectedParents.size}")
                LogManager.logConnection(
                    "Parent device connected",
                    ip = remoteAddress,
                    port = remotePort,
                    details = "Total parents: ${connectedParents.size}, Local port: $currentPort"
                )
            }
            superseded.forEach { it.writer.close() }
            updateStatusUI()
            // #308: fire the parent-connected event hook (no-op unless the user enabled hooks).
            // The IP goes only to the targeted webhook, NOT the broadcast (any local app can read
            // that). `parents` count is a best-effort snapshot — do NOT move this into the sync
            // block (the comment below warns why) and never pass `address` to LogManager.
            eventHooks?.dispatch(
                de.rochefort.childmonitor.events.EventHookPolicy.EVENT_PARENT_CONNECTED,
                details = mapOf("address" to (remoteAddress ?: ""), "parents" to connectedParents.size.toString()),
                broadcastDetails = mapOf("parents" to connectedParents.size.toString())
            )
            mqttIntegration?.publishParentConnected(true)
            // Start audio recording OUTSIDE the sync block — startAudioRecording can spawn
            // a thread that re-enters synchronized(connectedParents) on cleanup; calling it
            // from inside the same sync was a deadlock waiting to happen.
            if (shouldStartRecording) {
                startAudioRecording()
            }

            // Instead of busy-waiting with Thread.sleep(1000), block on read() until the parent
            // either disconnects (read returns -1) or the socket is closed (IOException).
            try {
                val drain = ByteArray(64)
                val input = socket.getInputStream()
                while (!Thread.currentThread().isInterrupted) {
                    val r = input.read(drain)
                    if (r < 0) break
                    // We don't expect parent → child data on this socket; ignore any bytes.
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "Connection thread interrupted")
            } catch (e: IOException) {
                // Normal disconnection path — socket closed locally or remotely
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            LogManager.e(LogCategory.CONNECTION, "Connection failed for $remoteAddress:$remotePort", e)
        } finally {
            addedConnection?.writer?.close()
            val connectionDuration = (System.currentTimeMillis() - connectionStartTime) / 1000
            var shouldStopRecording = false

            synchronized(connectedParents) {
                // Use socket-identity comparison so we don't trip over mutable bytesSent
                connectedParents.removeAll { it.socket === socket }
                val bytesSent = addedConnection?.bytesSent?.get() ?: 0L
                Log.i(TAG, "Parent disconnected. Total parents: ${connectedParents.size}")
                LogManager.logConnection(
                    "Parent device disconnected",
                    ip = remoteAddress,
                    port = remotePort,
                    details = "Duration: ${connectionDuration}s, Bytes sent: $bytesSent, Remaining parents: ${connectedParents.size}"
                )
                shouldStopRecording = connectedParents.isEmpty()
            }
            updateStatusUI()
            // #308: fire parent-disconnected ONLY if this thread actually held a counted
            // connection (addedConnection set after a successful add), so a cap-rejected parent
            // doesn't emit a phantom disconnect. IP → webhook only, not the broadcast.
            if (addedConnection != null) {
                eventHooks?.dispatch(
                    de.rochefort.childmonitor.events.EventHookPolicy.EVENT_PARENT_DISCONNECTED,
                    details = mapOf("address" to (remoteAddress ?: ""), "parents" to connectedParents.size.toString()),
                    broadcastDetails = mapOf("parents" to connectedParents.size.toString())
                )
                mqttIntegration?.publishParentConnected(hasAudioListeners())
            }
            if (shouldStopRecording) {
                stopAudioRecording(onlyIfNoParents = true)
            }
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing socket", e)
            }
        }
    }

    private fun audioListenerCount(): Int = connectedParents.size +
        (webServer?.audioListenerCount() ?: 0) + (webServerTls?.audioListenerCount() ?: 0)

    private fun hasAudioListeners(): Boolean = audioListenerCount() > 0

    private fun webAudioListenersChanged() {
        mainHandler.post {
            if (!isServiceAlive) return@post
            if (hasAudioListeners()) startAudioRecording() else stopAudioRecording(onlyIfNoParents = true)
            mqttIntegration?.publishParentConnected(hasAudioListeners())
            updateStatusUI()
        }
    }

    private fun startAudioRecording() {
        synchronized(audioRecordLock) {
            if (!isServiceAlive || isRecording) return
            // An old native read may still be unwinding after stop(). Starting now would let its
            // finally block release the new recorder. Keep a single deferred start until it exits.
            if (audioRecordThread?.isAlive == true) {
                if (captureStartPending.compareAndSet(false, true)) {
                    mainHandler.postDelayed({
                        captureStartPending.set(false)
                        if (isServiceAlive && hasAudioListeners()) startAudioRecording()
                    }, 100L)
                }
                return
            }
            startAudioRecordingLocked()
        }
    }

    // All access to audioRecord must be inside synchronized(audioRecordLock).
    private fun startAudioRecordingLocked() {
        val frequency: Int = AudioCodecDefines.FREQUENCY
        val channelConfiguration: Int = AudioCodecDefines.CHANNEL_CONFIGURATION_IN
        val audioEncoding: Int = AudioCodecDefines.ENCODING
        val bufferSize = AudioRecord.getMinBufferSize(frequency, channelConfiguration, audioEncoding)
        if (bufferSize <= 0) {
            LogManager.e(
                LogCategory.AUDIO,
                "Invalid AudioRecord min buffer size: $bufferSize (freq=$frequency, channel=$channelConfiguration, encoding=$audioEncoding)"
            )
            return
        }

        LogManager.logAudio(
            "Initializing audio recording",
            details = "Frequency: ${frequency}Hz, Buffer: $bufferSize bytes, Codec: G.711 μ-law"
        )

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                frequency,
                channelConfiguration,
                audioEncoding,
                bufferSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            LogManager.e(LogCategory.AUDIO, "Failed to create AudioRecord - missing permission?", e)
            surfaceNotificationStatus("Audio unavailable — RECORD_AUDIO permission denied")
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            LogManager.e(LogCategory.AUDIO, "AudioRecord failed to initialize (state=${recorder.state})")
            surfaceNotificationStatus("Audio unavailable — recorder failed to initialize")
            try {
                recorder.release()
            } catch (_: Exception) {
                // Ignore
            }
            return
        }
        audioRecord = recorder
        sootherEchoCanceler = try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                    enabled = sootherPlayer.isPlaying()
                    Log.i(TAG, "Child capture AEC attached (enabled=$enabled)")
                }
            } else null
        } catch (e: Exception) {
            Log.w(TAG, "Child capture AEC unavailable", e)
            null
        }

        isRecording = true
        totalBytesSent.set(0)
        audioPacketsSent.set(0)
        lastMetricsLogTime = System.currentTimeMillis()
        // #299: baseline for the audio watchdog — if no read lands within the stale window
        // after starting, the heartbeat restarts capture (covers a mic that never produces).
        lastAudioReadMs = android.os.SystemClock.elapsedRealtime()

        audioRecordThread = Thread {
            // getMinBufferSize is bytes, while AudioRecord.read(ShortArray) counts samples.
            // Read at most 100ms at a time; the recorder still retains its full native buffer.
            val frameSamples = minOf(bufferSize / 2, frequency / 10).coerceAtLeast(1)
            val pcmBuffer = ShortArray(frameSamples)
            val ulawBuffer = ByteArray(frameSamples)
            var smoothedCaptureGain = 2.0f

            try {
                recorder.startRecording()
                Log.i(TAG, "Started audio recording")
                LogManager.i(LogCategory.AUDIO, "Audio recording started - streaming to ${connectedParents.size} parent(s)")

                while (isRecording && !Thread.currentThread().isInterrupted) {
                    val read = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read <= 0) {
                        when (read) {
                            AudioRecord.ERROR_DEAD_OBJECT -> throw IOException("AudioRecord dead object")
                            AudioRecord.ERROR_BAD_VALUE -> throw IOException("AudioRecord bad value")
                            AudioRecord.ERROR_INVALID_OPERATION -> throw IOException("AudioRecord invalid operation")
                        }
                        continue
                    }
                    // #299: mark capture alive (read produced PCM) for the watchdog. Done
                    // before talkback suppression so intentional mute isn't seen as a capture stall.
                    lastAudioReadMs = android.os.SystemClock.elapsedRealtime()

                    val encoded: Int
                    if (isTalkbackPlaying) {
                        // Preserve the audio cadence during intentional echo suppression. Silence is
                        // valid mu-law audio, so other parents' 8s read watchdogs stay connected.
                        ulawBuffer.fill(0xff.toByte(), 0, read)
                        encoded = read
                    } else {
                        // Detect against pre-gain mic PCM; never report speaker echo as a cry.
                        soundDetector.process(pcmBuffer, read, android.os.SystemClock.elapsedRealtime())
                            ?.let { onSoundEvent(it) }
                        smoothedCaptureGain = applyCaptureLoudnessGain(pcmBuffer, read, smoothedCaptureGain)
                        encoded = AudioCodecDefines.CODEC.encode(pcmBuffer, read, ulawBuffer, 0)
                    }

                    audioReplay.append(ulawBuffer, 0, encoded, android.os.SystemClock.elapsedRealtime())

                    // Copy once: every downstream queue shares an immutable frame. Socket writes
                    // happen on independent bounded workers, never on the microphone thread.
                    val frame = ulawBuffer.copyOf(encoded)
                    for (parent in connectedParents) parent.writer.offer(frame)

                    webServer?.broadcastAudio(frame, frame.size)
                    webServerTls?.broadcastAudio(frame, frame.size)

                    // Log metrics every 60 seconds
                    val now = System.currentTimeMillis()
                    if (now - lastMetricsLogTime >= 60000) {
                        val tbs = totalBytesSent.getAndSet(0)
                        val pkts = audioPacketsSent.getAndSet(0)
                        val rateKBps = tbs / 60.0 / 1024.0
                        LogManager.logAudio(
                            "Audio streaming metrics",
                            bytesTransferred = tbs,
                            details = "Rate: ${"%.2f".format(rateKBps)} KB/s, Packets: $pkts, Parents: ${connectedParents.size}, Gain: ${"%.2f".format(smoothedCaptureGain)}x"
                        )
                        lastMetricsLogTime = now
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                LogManager.e(LogCategory.AUDIO, "Audio recording thread crashed", e)
            } finally {
                // Serialize teardown with start/stop to prevent double-release races.
                var shouldRestart = false
                synchronized(audioRecordLock) {
                    if (audioRecord === recorder) {
                        shouldRestart = isRecording && hasAudioListeners()
                        isRecording = false
                        audioRecordThread = null
                        try { sootherEchoCanceler?.release() } catch (_: Exception) {}
                        sootherEchoCanceler = null
                        audioRecord = null
                    }
                    try { recorder.stop() } catch (_: Exception) {}
                    try { recorder.release() } catch (_: Exception) {}
                }
                Log.i(TAG, "Stopped audio recording")
                LogManager.i(LogCategory.AUDIO, "Audio recording stopped")
                if (shouldRestart) {
                    LogManager.w(LogCategory.AUDIO, "Audio recorder stopped unexpectedly; scheduling restart")
                    mainHandler.postDelayed({
                        if (!isServiceAlive) return@postDelayed
                        if (!isRecording && hasAudioListeners()) {
                            startAudioRecording()
                        }
                    }, audioRecorderRestartDelayMs)
                }
            }
        }
        audioRecordThread?.start()
    }

    private fun stopAudioRecording(onlyIfNoParents: Boolean = false) {
        // #323: never release() the AudioRecord from this (caller, often main/watchdog) thread — the
        // capture loop reads WITHOUT the lock, so it may still be inside a native read() on it. Only
        // stop() here (which unblocks a blocked read()); the capture thread's own finally then does
        // release()+null on its thread, after read() has unwound. Join OUTSIDE the lock (so the
        // finally can take it) to keep this method's "fully stopped on return" contract and stop a
        // restart racing the old teardown. We only release() here as a fallback when there is no live
        // capture thread to do it — then nothing can be inside read(), so a same-thread release is safe.
        val thread: Thread?
        val ownedRecorder: AudioRecord?
        synchronized(audioRecordLock) {
            // Re-check under the capture lock: the last disconnect's earlier empty-roster
            // decision may already have been superseded by a newly connected parent.
            if (onlyIfNoParents && hasAudioListeners()) return
            isRecording = false
            thread = audioRecordThread
            ownedRecorder = audioRecord
            try { ownedRecorder?.stop() } catch (_: Exception) {}
        }
        if (thread != null) {
            thread.interrupt()
            try {
                thread.join(AUDIO_STOP_JOIN_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (thread.isAlive) {
                LogManager.w(
                    LogCategory.AUDIO,
                    "Audio capture thread still running ${AUDIO_STOP_JOIN_MS}ms after stop(); it will release on exit"
                )
            }
        }
        synchronized(audioRecordLock) {
            if (ownedRecorder != null && audioRecord === ownedRecorder &&
                (thread == null || !thread.isAlive)) {
                try { sootherEchoCanceler?.release() } catch (_: Exception) {}
                sootherEchoCanceler = null
                try { audioRecord?.stop() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
            }
        }
    }

    private fun setSootherEchoCancellation(enabled: Boolean) {
        synchronized(audioRecordLock) {
            try {
                sootherEchoCanceler?.enabled = enabled
                if (sootherEchoCanceler != null) {
                    Log.i(TAG, "Child capture AEC enabled=$enabled for soother playback")
                }
                Unit
            } catch (e: Exception) {
                Log.w(TAG, "Could not update child capture AEC", e)
            }
        }
    }

    /**
     * Applies adaptive loudness gain directly on captured PCM before encoding.
     * Keeps baby sounds audible while limiting clipping on louder spikes.
     */
    private fun applyCaptureLoudnessGain(
        samples: ShortArray,
        length: Int,
        previousGain: Float
    ): Float {
        if (length <= 0) return previousGain

        var peak = 0
        var energy = 0.0
        for (i in 0 until length) {
            val v = abs(samples[i].toInt())
            peak = maxOf(peak, v)
            energy += (v * v).toDouble()
        }
        val rms = sqrt(energy / length.toDouble())

        val desiredGain = when {
            rms <= 0.0 -> captureMaxGain
            rms < captureNoiseFloorRms -> captureMinGain
            else -> (captureTargetRms / rms).toFloat().coerceIn(captureMinGain, captureMaxGain)
        }

        val smoothed = if (desiredGain > previousGain) {
            previousGain + ((desiredGain - previousGain) * 0.18f)
        } else {
            previousGain + ((desiredGain - previousGain) * 0.10f)
        }

        val userMultiplier = captureCalibrationMultiplier
        val gainCap = captureMaxGain * userMultiplier
        val limiterGain = if (peak > 0) {
            min(gainCap, captureLimiterPeak / peak.toFloat())
        } else {
            gainCap
        }
        val appliedGain = min(smoothed * userMultiplier, limiterGain).coerceAtLeast(1.0f)

        for (i in 0 until length) {
            val amplified = (samples[i] * appliedGain).toInt()
            samples[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return appliedGain
    }

    private fun updateStatusUI() {
        val ma = this.monitorActivity
        ma?.runOnUiThread {
            val statusText = ma.findViewById<TextView>(R.id.textStatus)
            val listeners = audioListenerCount()
            if (listeners == 0) {
                statusText.setText(R.string.waitingForParent)
            } else {
                statusText.text = resources.getQuantityString(
                    R.plurals.streamingParents,
                    listeners,
                    listeners,
                )
            }
        }
    }

    override fun onCreate() {
        Log.i(TAG, "ChildMonitor start")
        super.onCreate()

        serviceStartTime = System.currentTimeMillis()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(serviceStartTime)

        // Initialize diagnostic logging
        LogManager.initialize(applicationContext, LogManager.DeviceType.CHILD)
        LogManager.i(LogCategory.SYSTEM, "========================================")
        LogManager.i(LogCategory.SYSTEM, "MonitorService onCreate() called at $timestamp")
        LogManager.i(LogCategory.SYSTEM, "Service instance created - initializing child device")

        // Start network monitoring + wire IP-change handler so NSD re-registers immediately
        // when the device roams to a new WiFi (don't wait for the 2-minute heartbeat tick).
        //
        // NetworkStateMonitor suppresses only networks already present at registration time. If
        // the child starts offline, its first onAvailable is therefore a REAL restoration and must
        // refresh NSD immediately (the former local skip-first flag incorrectly discarded it).
        networkMonitor = NetworkStateMonitor(applicationContext)
        networkMonitor?.onNetworkAvailable = networkAvailable@{
            // A real restoration can race the remainder of onCreate. If NSD/currentPort are not
            // initialized yet, onStartCommand's normal initial registration will see the live
            // network moments later; do not start an ephemeral port-0 monitor thread here.
            if (!::nsdManager.isInitialized || currentPort <= 0) {
                LogManager.d(LogCategory.NETWORK,
                    "Network restored during service initialization — initial NSD registration will handle it")
                return@networkAvailable
            }
            LogManager.i(LogCategory.NETWORK, "Network restored — refreshing NSD registration")
            try { refreshNsdRegistration() } catch (e: Exception) {
                LogManager.e(LogCategory.NETWORK, "NSD refresh on network-available failed", e)
            }
            val mt = monitorThread
            if (mt == null || !mt.isAlive) {
                ensureMonitorThread()
            }
        }
        // Same-network IP change (DHCP renew / AP roam) does NOT fire onAvailable, so the NSD
        // advertisement keeps publishing the OLD IP until restart. Refresh it here. The audio
        // ServerSocket is wildcard-bound (0.0.0.0) so it needs no rebind, and we deliberately do
        // NOT touch connected parents — only the advertisement is stale.
        networkMonitor?.onIpAddressChanged = {
            LogManager.i(LogCategory.NETWORK, "Local IP changed — refreshing NSD registration")
            try { refreshNsdRegistration() } catch (e: Exception) {
                LogManager.e(LogCategory.NETWORK, "NSD refresh on IP change failed", e)
            }
            val mt = monitorThread
            if (mt == null || !mt.isAlive) ensureMonitorThread()
        }
        networkMonitor?.startMonitoring()

        // Start battery/power state monitoring to detect Doze mode
        batteryMonitor = BatteryStateMonitor(applicationContext)
        batteryMonitor?.startMonitoring()

        // Initialize cameras but defer binding until start
        cameraStreamer = CameraStreamer(applicationContext)
        // #337: feed each published JPEG into the snapshot replay ring. The listener simply isn't
        // invoked while the camera stream is stopped (camera off), so the ring goes stale on its own.
        cameraStreamer?.frameListener = { jpeg, ts -> snapshotReplay.append(jpeg, ts) }
        cameraStreamer?.motionListener = { event -> onMotionEvent(event) }
        // #327/#328: if the soother writer dies on its own, clear the now-moot sleep timer and
        // re-sync the FGS type (drop MEDIA_PLAYBACK). The callback fires on the soother writer thread,
        // so marshal to main — never call back into sootherPlayer.stop() synchronously from there.
        sootherPlayer.onUnexpectedStop = {
            mainHandler.post {
                if (!isServiceAlive) return@post
                // A newer session B may have started between the writer's death and this callback —
                // only clear the timer / drop the FGS bit if the soother is actually stopped now, so
                // we don't disarm B's timer (B's own play already set its timer + FGS type).
                if (sootherPlayer.isPlaying()) return@post
                cancelSootherAutoStop()
                setSootherEchoCancellation(false)
                refreshForegroundServiceType()
            }
        }

        // #308: event hooks (broadcast + webhook), restored from prefs (opt-in, default off).
        eventHooks = de.rochefort.childmonitor.events.EventHookDispatcher(applicationContext).also {
            val p = getSharedPreferences(MONITOR_PREFS, MODE_PRIVATE)
            it.enabled = p.getBoolean(PREF_HOOKS_ENABLED, false)
            it.webhookUrl = p.getString(PREF_HOOKS_URL, null)
            it.lanOnly = p.getBoolean(PREF_HOOKS_LAN_ONLY, false) // #332: default off (back-compat)
        }

        // #266/#475: the live detector pipeline owns one durable event timeline. Detector timing
        // stays monotonic, while EventStore receives an explicitly converted wall-clock timestamp.
        eventStore = de.rochefort.childmonitor.events.EventStore(applicationContext)

        // #305/#315: opt-in local MQTT bridge. Broker work stays off the realtime capture paths;
        // alert transitions join the existing durable timeline for later diagnosis.
        mqttIntegration = de.rochefort.childmonitor.integrations.mqtt.MqttIntegration(
            applicationContext,
            ChildIdentity.getId(this),
            ChildIdentity.getName(this),
            parentConnected = { hasAudioListeners() },
        ) { reading, alert ->
            if (alert != null && alert != lastEnvironmentAlert && ::eventStore.isInitialized) {
                try {
                    eventStore.insertGeneric(
                        type = "environment_${alert.name.lowercase()}",
                        confidence = 1.0,
                        durationMs = 0L,
                        attrs = mapOf(
                            "temperatureC" to reading.temperatureC,
                            "humidityPct" to reading.humidityPct,
                        ),
                    )
                } catch (e: Exception) {
                    LogManager.e(LogCategory.SYSTEM, "Failed to persist environment alert", e)
                }
            }
            lastEnvironmentAlert = alert
        }.also { it.start() }

        // #291: restore per-trigger alert toggles + quiet-hours config (opt-in; defaults leave
        // both triggers on and quiet hours off). Defaults come from AlertConfig()/QuietHoursRule so
        // there's a single source of truth. Persisted values are clamped to valid ranges on load —
        // prefs are a trust boundary, and an out-of-range hour/floor would otherwise produce a
        // nonsensical quiet window.
        getSharedPreferences(MONITOR_PREFS, MODE_PRIVATE).let { p ->
            val d = de.rochefort.childmonitor.events.AlertConfig()
            alertConfig = de.rochefort.childmonitor.events.AlertConfig(
                cryEnabled = p.getBoolean(PREF_CRY_ALERTS, d.cryEnabled),
                coughEnabled = p.getBoolean(PREF_COUGH_ALERTS, d.coughEnabled),
                motionEnabled = p.getBoolean(PREF_MOTION_ALERTS, d.motionEnabled),
                quietHours = de.rochefort.childmonitor.events.QuietHoursRule(
                    startHour = p.getInt(PREF_QUIET_START_H, d.quietHours.startHour).coerceIn(0, 23),
                    startMinute = p.getInt(PREF_QUIET_START_M, d.quietHours.startMinute).coerceIn(0, 59),
                    endHour = p.getInt(PREF_QUIET_END_H, d.quietHours.endHour).coerceIn(0, 23),
                    endMinute = p.getInt(PREF_QUIET_END_M, d.quietHours.endMinute).coerceIn(0, 59),
                    escalationFloor = p.getFloat(PREF_QUIET_FLOOR, d.quietHours.escalationFloor.toFloat())
                        .toDouble().coerceIn(0.0, 1.0),
                    enabled = p.getBoolean(PREF_QUIET_ENABLED, d.quietHours.enabled)
                )
            )
        }

        // #312: listen for a local night-light dismissal so /api/status stays in sync.
        androidx.core.content.ContextCompat.registerReceiver(
            this, nightLightClosedReceiver,
            android.content.IntentFilter(NightLightActivity.ACTION_CLOSED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )

        this.notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        this.nsdManager = this.getSystemService(NSD_SERVICE) as NsdManager
        this.pairingManager = PairingManager(this)
        this.sessionAuthority = de.rochefort.childmonitor.security.SessionAuthority.getInstance(applicationContext)
        // Review-pass-1 #1+#2: prime the singleton off the main thread so the
        // RSA-2048 keygen on first install doesn't ANR onCreate; the field is
        // populated to the same singleton instance once ensureWebServer runs.
        de.rochefort.childmonitor.security.TlsIdentity.prime(applicationContext)
        this.captureCalibrationMultiplier = AudioCalibrationPrefs.getCaptureGainMultiplier(this)
        this.currentPort = 10000
        this.currentSocket = null

        // Acquire CPU wake lock so the device stays able to record + transmit audio
        // overnight even with screen off and Doze mode active. AudioRecord does NOT
        // automatically hold a wake lock the way AudioTrack/AudioFlinger does for
        // playback, so we keep this explicit one on the child.
        //
        // We deliberately do NOT take a WIFI_MODE_FULL_HIGH_PERF WifiLock here.
        // That API has been effectively a no-op for "keep WiFi awake" since API 25
        // (2016) — modern Android keeps Wi-Fi associated for the life of an active
        // TCP socket on a battery-whitelisted foreground service, and the bugreport
        // showed only 2m05s of Full Wifi Lock attribution over a 54-minute session
        // even when we were holding it the entire time, confirming it's not buying
        // anything. The "high perf" mode also disables radio sleep heuristics, which
        // can actually increase battery use without improving the streaming link.
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ChildMonitor:ChildWakeLock"
        )
        wakeLock?.setReferenceCounted(false)
        acquireChildWakeLock()

        // If the prior service instance was killed during talkback, the boosted music
        // volume would persist until the user noticed. Restore it now.
        restoreOrphanedTalkbackVolume()

        LogManager.i(LogCategory.SYSTEM, "Service initialization complete - will listen on port $currentPort")
    }

    private fun acquireChildWakeLock() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(12 * 60 * 60 * 1000L)
                LogManager.i(LogCategory.SYSTEM, "Wake lock acquired — CPU stays awake for audio recording")
            }
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseChildLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                LogManager.i(LogCategory.SYSTEM, "Wake lock released")
            }
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Failed to release wake lock", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "Received start id $startId: $intent")
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(System.currentTimeMillis())
        LogManager.i(LogCategory.SYSTEM, "onStartCommand() called at $timestamp | startId: $startId")

        // Log any gap since service was created
        val timeSinceCreation = System.currentTimeMillis() - serviceStartTime
        if (timeSinceCreation > 5000) {
            LogManager.w(
                LogCategory.SYSTEM,
                "Gap detected: ${timeSinceCreation}ms between onCreate() and onStartCommand()"
            )
        }

        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel()
        val n = buildNotification()

        // [#230] Promote with only the FGS types whose runtime permission is actually
        // granted. On API 34+, startForeground() with FOREGROUND_SERVICE_TYPE_MICROPHONE /
        // _CAMERA throws SecurityException when the matching permission is missing, and
        // START_REDELIVER_INTENT would then crash-loop after a mid-session revoke. The
        // audio capture path (initializeAudioRecording) already degrades gracefully when
        // RECORD_AUDIO is denied; here we avoid requesting an ungranted FGS type and skip
        // the camera entirely when CAMERA is denied.
        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCamera = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        // #300: persisted audio-only (low-power) mode — when the user has turned the camera
        // off, skip starting it entirely (suspend snapshots, save battery) and drop the
        // CAMERA foreground-service type.
        val prefs = getSharedPreferences(MONITOR_PREFS, MODE_PRIVATE)
        val cameraPref = prefs.getBoolean(PREF_CAMERA_ENABLED, true)
        val runCamera = de.rochefort.childmonitor.util.LowPowerPolicy.shouldRunCamera(cameraPref, hasCamera)
        cameraEnabled = runCamera
        // #287: restore the persisted snapshot cadence mode (defaults to AUTO).
        snapshotMode = de.rochefort.childmonitor.video.SnapshotCadencePolicy.parseMode(
            prefs.getString(PREF_SNAPSHOT_CADENCE, null)
        ) ?: de.rochefort.childmonitor.video.SnapshotCadencePolicy.DEFAULT_MODE
        // #327: include MEDIA_PLAYBACK if audio is already playing (e.g. a START_REDELIVER_INTENT
        // redelivery mid-session). refreshForegroundServiceType() keeps it in sync thereafter.
        val playingAudio = isTalkbackPlaying || sootherPlayer.isPlaying()
        val foregroundServiceType =
            ForegroundServiceTypePolicy.typeFor(Build.VERSION.SDK_INT, hasMic, runCamera, playingAudio)
        ServiceCompat.startForeground(this, ID, n, foregroundServiceType)
        ensureMonitorThread()
        ensureWebServer()
        ensureTlsAudioThread()
        startHeartbeat()
        if (runCamera) {
            // Delay camera start to allow lifecycle to fully enter STARTED state
            mainHandler.postDelayed({
                if (!isServiceAlive) return@postDelayed
                startCameraStream()
            }, 500)
        } else {
            LogManager.i(
                LogCategory.SYSTEM,
                if (!hasCamera) "CAMERA permission not granted — running audio-only (no camera snapshots)"
                else "Audio-only (low-power) mode — camera suspended to save battery"
            )
        }
        return START_REDELIVER_INTENT
    }

    private fun startHeartbeat() {
        if (isHeartbeatActive) return

        isHeartbeatActive = true
        lastHeartbeatTime = System.currentTimeMillis()
        lastNsdRegistrationTime = System.currentTimeMillis()

        heartbeatThread = Thread {
            LogManager.i(LogCategory.HEALTH, "Heartbeat thread started - will log every 5 minutes, refresh NSD every 2 minutes")

            while (isHeartbeatActive && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(60 * 1000) // Check every 1 minute

                    val now = System.currentTimeMillis()

                    // We used to refresh the NSD registration here every 2 minutes
                    // "to stay discoverable", but the unregister-then-re-register sequence
                    // (with a 500 ms sleep in between) left a visible gap on the parent
                    // side — any active discovery during the gap saw the service disappear
                    // and would start a reconnect campaign. NSD on Android does not need
                    // periodic refreshing; we still re-register on:
                    //   - network restored (WiFi handoff changed our IP)
                    //   - onRegistrationFailed (listener is nulled, re-registered below)
                    // which are the two real triggers that actually need it. See #127.

                    // CRITICAL recovery: if monitor thread died (e.g. ServerSocket bind failed
                    // on startup, port was in TIME_WAIT), the child becomes invisible to all
                    // parents while the service still appears running. Self-heal by spinning
                    // the accept thread back up.
                    val mt = monitorThread
                    if (mt == null || !mt.isAlive) {
                        LogManager.w(
                            LogCategory.CONNECTION,
                            "Monitor thread is not alive — restarting accept loop"
                        )
                        ensureMonitorThread()
                    }

                    // Review-pass-1 #4 + pass-2 MED-1: re-try TLS listener if the
                    // keystore wasn't ready when ensureWebServer first ran (keygen
                    // still in flight) or if a transient port bind failed. Skipped
                    // when TLS is known permanently unavailable (pre-API-23) so the
                    // heartbeat doesn't burn a wake-up + log line every minute for
                    // 12h of overnight monitoring.
                    if (webServer != null && webServerTls == null && !tlsPermanentlyUnavailable) {
                        ensureWebServer()
                    }
                    if (webServerTls != null && (tlsAudioThread?.isAlive != true || tlsAudioSocket == null)) {
                        ensureTlsAudioThread()
                    }

                    // If NSD registration was lost (e.g. onRegistrationFailed), re-attempt.
                    // [#235] Serialize with refreshNsdRegistration via nsdLock and re-check
                    // under the lock so the two paths can't both register the same service
                    // name (which leaks one listener and churns the advertised name).
                    if (registrationListener == null && currentSocket != null) {
                        synchronized(nsdLock) {
                            if (registrationListener == null && currentSocket != null) {
                                LogManager.w(
                                    LogCategory.DISCOVERY,
                                    "NSD listener is null — re-registering service"
                                )
                                try {
                                    registerService(currentPort)
                                    lastNsdRegistrationTime = now
                                } catch (e: Exception) {
                                    LogManager.e(LogCategory.DISCOVERY, "Re-register attempt failed", e)
                                }
                            }
                        }
                    }

                    // Proactively check for stale parent connections
                    pruneStaleConnections()

                    // Check camera health and restart if needed
                    checkCameraHealth()
                    // #299: check audio-capture health and restart if the mic wedged silently
                    checkAudioHealth()
                    // #287: refresh snapshot cadence so battery-saver/charging changes (and any
                    // lingering glance) are reflected even without a remote cadence request.
                    recomputeSnapshotCadence()

                    // Log heartbeat every 5 minutes
                    if (now - lastHeartbeatTime >= 5 * 60 * 1000) {
                        val timeSinceLastHeartbeat = now - lastHeartbeatTime
                        val uptimeSeconds = (now - serviceStartTime) / 1000

                        // Log heartbeat with service status
                        val details = buildString {
                            append("Service uptime: ${uptimeSeconds}s")
                            append(" | Connected parents: ${connectedParents.size}")
                            append(" | Audio recording: $isRecording")

                            // Warn if there's a gap (should be ~5 min, but allow some tolerance)
                            if (timeSinceLastHeartbeat > 6 * 60 * 1000) {
                                append(" | WARNING: Gap of ${timeSinceLastHeartbeat / 1000}s detected")
                            }
                        }

                        LogManager.i(LogCategory.HEALTH, "Heartbeat | $details")

                        // Also log current battery/power state periodically
                        batteryMonitor?.logCurrentPowerState()

                        lastHeartbeatTime = now
                    }

                    // CM-FEAT-007 (#132): once per hour, prune old events / logs /
                    // media. On a CRITICAL filesystem-pressure snapshot, halve the
                    // media ring immediately. This is what stops the device from
                    // silently filling up after weeks of unattended operation.
                    if (now - lastRetentionRunMs >= RETENTION_INTERVAL_MS) {
                        lastRetentionRunMs = now
                        try {
                            val engine = de.rochefort.childmonitor.storage.RetentionEngine(applicationContext)
                            val report = engine.run(
                                eventStore = if (::eventStore.isInitialized) eventStore else null,
                                mediaBuffer = null,
                            )
                            LogManager.i(LogCategory.SYSTEM,
                                "RetentionEngine: pressure=${report.pressure} logsDeleted=${report.logsDeleted} eventsDeleted=${report.eventsDeleted} mediaDeleted=${report.mediaDeleted}")
                            de.rochefort.childmonitor.security.AuditLog.append(
                                applicationContext,
                                "retention.run",
                                mapOf(
                                    "pressure" to report.pressure.name,
                                    "logsDeleted" to report.logsDeleted.toString(),
                                    "eventsDeleted" to report.eventsDeleted.toString(),
                                    "mediaDeleted" to report.mediaDeleted.toString()
                                )
                            )
                        } catch (e: Exception) {
                            LogManager.e(LogCategory.SYSTEM, "RetentionEngine.run failed", e)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    LogManager.e(LogCategory.HEALTH, "Heartbeat error", e)
                }
            }

            LogManager.i(LogCategory.HEALTH, "Heartbeat thread stopped")
        }
        heartbeatThread?.start()
    }

    /**
     * Re-register the NSD service after a real trigger (network change, registration
     * failure). Tear down the old listener first, then register a fresh one — the gap
     * is unavoidable because Android does not allow two registrations for the same
     * service name to coexist, but this path is now only used for genuine re-registration
     * triggers (not the discarded periodic refresh), so the gap is rare and acceptable.
     */
    private fun refreshNsdRegistration() = synchronized(nsdLock) {
        try {
            val socket = currentSocket
            if (socket == null || socket.isClosed) {
                // The accept-loop owns initial registration after a successful bind. Advertising
                // while no socket is listening creates a discoverable-but-unreachable child and can
                // race the accept-loop into a duplicate registration.
                LogManager.d(LogCategory.DISCOVERY,
                    "Skipping NSD refresh until the monitor socket is listening")
                return@synchronized
            }
            LogManager.d(LogCategory.DISCOVERY, "Re-registering NSD service (network change or prior registration failure)")

            // Unregister first
            registrationListener?.let { listener ->
                try {
                    nsdManager.unregisterService(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering NSD service during refresh", e)
                }
            }
            registrationListener = null

            // Small delay to allow unregistration to complete
            Thread.sleep(500)

            // Re-register
            registerService(currentPort)

            LogManager.d(LogCategory.DISCOVERY, "NSD registration refreshed successfully")
        } catch (e: Exception) {
            LogManager.e(LogCategory.DISCOVERY, "Failed to refresh NSD registration", e)
        }
    }

    /** Check local socket state. Real writes and TCP keepalive detect remote failures.
     * Never send urgent data: SSLSocket does not support it and healthy secure parents would be
     * disconnected whenever talkback paused microphone delivery for more than 90 seconds.
     */
    private fun pruneStaleConnections() {
        val deadConnections = connectedParents.filter { parent ->
            try {
                parent.socket.isClosed || !parent.socket.isConnected ||
                    parent.socket.isInputShutdown || parent.socket.isOutputShutdown
            } catch (_: Exception) { true }
        }

        if (deadConnections.isEmpty()) return

        var shouldStopRecording = false
        synchronized(connectedParents) {
            connectedParents.removeAll { p -> deadConnections.any { it.socket === p.socket } }
            LogManager.w(
                LogCategory.CONNECTION,
                "Pruned ${deadConnections.size} stale connection(s) during health check, ${connectedParents.size} remaining"
            )
            shouldStopRecording = connectedParents.isEmpty()
        }
        deadConnections.forEach { it.writer.close() }
        if (shouldStopRecording) {
            stopAudioRecording(onlyIfNoParents = true)
        }
        updateStatusUI()
    }

    /**
     * Check if camera should be running and restart if it has silently stopped.
     * CameraX can stop producing frames after extended runtime without errors.
     */
    private fun checkCameraHealth() {
        if (!cameraEnabled) return

        val streamer = cameraStreamer ?: return

        // In night mode, frames update slowly (every ~5s) — don't misinterpret as failure
        if (streamer.isInNightMode()) return

        val isActuallyRunning = streamer.isRunning()
        val hasFrame = streamer.latestJpeg() != null

        if (!isActuallyRunning || !hasFrame) {
            LogManager.w(
                LogCategory.SYSTEM,
                "Camera health check: running=$isActuallyRunning, hasFrame=$hasFrame - restarting camera"
            )
            // #194-1 CRITICAL: stopCameraStream() ultimately calls
            // LifecycleRegistry.handleLifecycleEvent which is main-thread-only.
            // checkCameraHealth runs on the heartbeat thread, so the old direct
            // call would throw IllegalStateException, the heartbeat catch
            // swallowed it, and the camera never recovered. Marshal BOTH the
            // stop and start onto the main handler.
            mainHandler.post {
                if (!isServiceAlive) return@post
                stopCameraStream()
                mainHandler.postDelayed({
                    if (!isServiceAlive) return@postDelayed
                    startCameraStream()
                }, 5000)
            }
        }
    }

    /**
     * #299: restart audio capture if the mic stopped delivering PCM while we're streaming —
     * AudioRecord.read() wedged/blocking or returning errors for >= [AUDIO_STALE_MS] — which
     * would otherwise stream nothing while the TCP link stays up so the parent never detects
     * it. Detects "no bytes captured", not "captured audio is silent" (see [lastAudioReadMs]).
     * Mirrors checkCameraHealth: detect on the heartbeat thread, restart via the established
     * main-thread [stopAudioRecording] path (which serializes teardown under audioRecordLock).
     */
    private fun checkAudioHealth() {
        val now = android.os.SystemClock.elapsedRealtime()
        val needsRestart = de.rochefort.childmonitor.util.CaptureHealth.audioNeedsRestart(
            recording = isRecording,
            hasParents = hasAudioListeners(),
            lastDataMs = lastAudioReadMs,
            nowMs = now,
            staleMs = AUDIO_STALE_MS
        )
        if (!needsRestart) return
        // Re-arm the staleness clock now so a second heartbeat tick can't queue a duplicate
        // restart before this one settles (keeps correctness independent of the heartbeat
        // period); startAudioRecording() resets it to a real baseline once capture is back.
        lastAudioReadMs = now
        LogManager.w(
            LogCategory.AUDIO,
            "Audio health check: no captured PCM for >=${AUDIO_STALE_MS / 1000}s while streaming to " +
                "${connectedParents.size} parent(s) — restarting capture"
        )
        mainHandler.post {
            if (!isServiceAlive) return@post
            stopAudioRecording()
            mainHandler.postDelayed({
                if (!isServiceAlive) return@postDelayed
                if (hasAudioListeners()) startAudioRecording()
            }, 1000)
        }
    }

    /**
     * #287: compute the effective snapshot encode interval (user mode + adaptive battery/charging
     * signals + any active near-live glance) and push it to the camera streamer. Cheap; safe to
     * call from the heartbeat thread, the camera-start path, and remote cadence handlers.
     */
    private fun recomputeSnapshotCadence() {
        val streamer = cameraStreamer ?: return
        val inputs = de.rochefort.childmonitor.video.AdaptiveSnapshotController.Inputs(
            batterySaver = isPowerSaveMode(),
            chargingPlugged = isDeviceCharging()
        )
        val adaptiveMs = adaptiveSnapshot.nextIntervalMs(inputs)
        streamer.encodeIntervalMs = de.rochefort.childmonitor.video.SnapshotCadencePolicy.effectiveIntervalMs(
            mode = snapshotMode,
            adaptiveIntervalMs = adaptiveMs,
            glanceUntilMs = snapshotGlanceUntilMs,
            nowMs = android.os.SystemClock.elapsedRealtime()
        )
    }

    private fun isPowerSaveMode(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    } catch (e: Exception) {
        false
    }

    /** Read charge state via the sticky battery intent (works on API 21+, no extra permission). */
    private fun isDeviceCharging(): Boolean = try {
        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        (intent?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1) > 0
    } catch (e: Exception) {
        true // assume plugged → don't apply the battery-saver slowdown if we can't tell
    }

    /** #287: set the snapshot cadence mode (persisted) and re-push. Returns current cadence JSON. */
    fun setSnapshotMode(mode: de.rochefort.childmonitor.video.SnapshotCadencePolicy.Mode): JSONObject {
        snapshotMode = mode
        getSharedPreferences(MONITOR_PREFS, MODE_PRIVATE).edit()
            .putString(PREF_SNAPSHOT_CADENCE, mode.name).apply()
        recomputeSnapshotCadence()
        LogManager.i(LogCategory.SYSTEM, "Snapshot cadence mode set to ${mode.name}")
        return snapshotCadenceJson()
    }

    /** #287: trigger a near-live glance for [durationMs] (clamped); auto-reverts at expiry. */
    fun requestSnapshotGlance(durationMs: Long): JSONObject {
        val dur = durationMs.coerceIn(1_000L, SNAPSHOT_GLANCE_MAX_MS)
        snapshotGlanceUntilMs = android.os.SystemClock.elapsedRealtime() + dur
        recomputeSnapshotCadence()
        // Revert precisely at expiry instead of waiting for the next heartbeat tick. Re-post the
        // single shared callback so back-to-back glances don't pile up on the handler.
        mainHandler.removeCallbacks(snapshotGlanceRevert)
        mainHandler.postDelayed(snapshotGlanceRevert, dur + 50L)
        LogManager.i(LogCategory.SYSTEM, "Snapshot near-live glance requested for ${dur}ms")
        return snapshotCadenceJson()
    }

    /** #287: current cadence as JSON (also embedded in /api/status). MAX_VALUE interval → -1 (paused). */
    fun snapshotCadenceJson(): JSONObject {
        val glanceRemaining = (snapshotGlanceUntilMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val effective = cameraStreamer?.encodeIntervalMs ?: 0L
        return JSONObject().apply {
            put("mode", snapshotMode.name)
            put("effectiveIntervalMs", if (effective == Long.MAX_VALUE) -1 else effective)
            put("glanceActive", glanceRemaining > 0L)
            put("glanceRemainingMs", glanceRemaining)
        }
    }

    /**
     * #311: handle a remote soother command. action=play|start (optionally sound=&volume=),
     * action=stop, or action=volume (volume=). Returns the resulting state with a success flag;
     * `success:false` => the caller should surface a 400.
     */
    private fun handleSootherCommand(action: String?, soundStr: String?, volume: Float?, durationMs: Long?): JSONObject {
        return when (action?.trim()?.lowercase()) {
            "play", "start" -> {
                val sound = if (soundStr == null) {
                    sootherPlayer.sound()
                } else {
                    de.rochefort.childmonitor.audio.SootherPolicy.parseSound(soundStr)
                        ?: return JSONObject().put("success", false).put("error", "invalid_sound")
                }
                val vol = volume ?: sootherPlayer.volume()
                val wasPlaying = sootherPlayer.isPlaying()
                val ok = sootherPlayer.start(sound, vol)
                if (ok) {
                    setSootherEchoCancellation(true)
                    // #328 sleep timer:
                    //  - durationMs explicitly given → (re)arm (clamp; <=0 clears it).
                    //  - omitted on a FRESH start → no timer (indefinite).
                    //  - omitted while ALREADY playing (a sound/volume switch) → leave the running
                    //    timer alone, so switching the noise colour mid-countdown doesn't disarm it.
                    when {
                        durationMs != null -> scheduleSootherAutoStop(BedtimeTimerPolicy.clampDurationMs(durationMs))
                        !wasPlaying -> cancelSootherAutoStop()
                    }
                    refreshForegroundServiceType() // #327: advertise MEDIA_PLAYBACK while soother plays
                }
                sootherStateJson().put("success", ok)
            }
            "stop" -> {
                cancelSootherAutoStop() // #328
                sootherPlayer.stop()
                setSootherEchoCancellation(false)
                refreshForegroundServiceType() // #327: drop MEDIA_PLAYBACK when soother stops (unless talkback still plays)
                sootherStateJson().put("success", true)
            }
            "volume" -> {
                if (volume == null) JSONObject().put("success", false).put("error", "volume_required")
                else {
                    sootherPlayer.setVolume(volume) // a volume change leaves the sleep timer running
                    sootherStateJson().put("success", true)
                }
            }
            "timer" -> {
                if (durationMs == null) {
                    JSONObject().put("success", false).put("error", "duration_required")
                } else if (durationMs > 0L && !sootherPlayer.isPlaying()) {
                    JSONObject().put("success", false).put("error", "soother_not_playing")
                } else {
                    scheduleSootherAutoStop(BedtimeTimerPolicy.clampDurationMs(durationMs))
                    sootherStateJson().put("success", true)
                }
            }
            else -> JSONObject().put("success", false).put("error", "invalid_action")
        }
    }

    /**
     * #328: arm (or clear) the soother sleep timer. [durationMs] is already clamped; null clears any
     * existing timer and leaves the soother playing indefinitely. Scheduling/cancellation go through
     * mainHandler (thread-safe) so the auto-stop runs on the main thread like the other lifecycle ops.
     */
    // #328 / Codex: monotonic soother timer generation. A scheduled auto-stop captures the id at
    // schedule time and no-ops if it changed before it fires — the runnable may already have been
    // dequeued (so removeCallbacks can't stop it) when a newer play started session B; without this
    // the stale runnable would stop B. Bumped on every (re)schedule and cancel.
    private val sootherTimerGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var sootherAutoStopRunnable: Runnable? = null

    private fun scheduleSootherAutoStop(durationMs: Long?) {
        val gen = sootherTimerGeneration.incrementAndGet()
        sootherAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        sootherAutoStopRunnable = null
        if (durationMs == null) {
            sootherAutoStopAtMs = 0L
            return
        }
        sootherAutoStopAtMs = System.currentTimeMillis() + durationMs
        val r = Runnable {
            if (!isServiceAlive) return@Runnable
            // Superseded by a newer schedule/cancel (e.g. a fresh play armed session B) → no-op so we
            // never stop a newer soother session with a stale timer.
            if (gen != sootherTimerGeneration.get()) return@Runnable
            LogManager.i(LogCategory.AUDIO, "Soother sleep-timer elapsed — stopping")
            sootherAutoStopAtMs = 0L
            sootherAutoStopRunnable = null
            sootherPlayer.stop()
            setSootherEchoCancellation(false)
            refreshForegroundServiceType()
            lockScreenIfBedtimeFinished("Sound")
        }
        sootherAutoStopRunnable = r
        mainHandler.postDelayed(r, durationMs)
    }

    private fun cancelSootherAutoStop() {
        sootherTimerGeneration.incrementAndGet() // invalidate any already-dequeued auto-stop runnable
        sootherAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        sootherAutoStopRunnable = null
        sootherAutoStopAtMs = 0L
    }

    /** #311: current soother state (also embedded in /api/status). */
    fun sootherStateJson(): JSONObject = JSONObject().apply {
        put("playing", sootherPlayer.isPlaying())
        put("sound", sootherPlayer.sound().name)
        put("volume", sootherPlayer.volume())
        // #328: ms left on the sleep timer, or 0 when none is armed / already elapsed.
        val remaining = BedtimeTimerPolicy.remainingMs(sootherAutoStopAtMs, System.currentTimeMillis())
        put("remainingMs", remaining)
        put("autoStopAtMs", if (remaining > 0L) sootherAutoStopAtMs else 0L)
    }

    /**
     * #312: handle a remote night-light command. on=true|false|on|off (omit to keep current and
     * just change colour/brightness); optional color (preset/hex) + brightness (0..1). Validates
     * before applying so a bad value can't half-apply. Returns state with a success flag.
     */
    private fun handleNightLightCommand(
        onStr: String?,
        colorStr: String?,
        brightness: Float?,
        durationMs: Long?,
    ): JSONObject {
        val color = if (colorStr == null) null else (NightLightPolicy.parseColor(colorStr)
            ?: return JSONObject().put("success", false).put("error", "invalid_color"))
        val on = when (onStr?.trim()?.lowercase()) {
            null -> nightLightOn // no on= → keep current on/off, just change colour/brightness
            "true", "1", "on", "yes" -> true
            "false", "0", "off", "no" -> false
            else -> return JSONObject().put("success", false).put("error", "invalid_on")
        }
        if (durationMs != null && durationMs > 0L && !on) {
            return JSONObject().put("success", false).put("error", "night_light_not_on")
        }
        val wasOn = nightLightOn
        if (color != null) nightLightColor = color
        if (brightness != null) nightLightBrightness = NightLightPolicy.clampBrightness(brightness)
        nightLightOn = on
        when {
            !on -> cancelNightLightAutoStop()
            durationMs != null -> scheduleNightLightAutoStop(BedtimeTimerPolicy.clampDurationMs(durationMs))
            !wasOn -> cancelNightLightAutoStop()
        }
        applyNightLight()
        LogManager.i(LogCategory.SYSTEM, "Night light: on=$on color=${nightLightColor and 0xFFFFFF} brightness=$nightLightBrightness")
        return nightLightStateJson().put("success", true)
    }

    /**
     * #312: sync [NightLightActivity] to the current state via one relaunch-safe path. When on,
     * (re)launch/raise via [NightLightLauncher] — a full-screen-intent cold launch (BAL-safe on a
     * locked device) plus a direct start that delivers onNewIntent for live colour/brightness
     * updates (and relaunches it if the OS reclaimed it). When off, finish the activity and drop
     * the launcher notification.
     */
    private fun applyNightLight() {
        if (nightLightOn) {
            nightLightLauncher.show(nightLightColor, nightLightBrightness)
        } else {
            sendBroadcast(
                Intent(NightLightActivity.ACTION_UPDATE).setPackage(packageName)
                    .putExtra(NightLightActivity.EXTRA_ON, false)
            )
            nightLightLauncher.hide()
        }
    }

    /** #312: current night-light state (also embedded in /api/status). Colour as #RRGGBB. */
    fun nightLightStateJson(): JSONObject = JSONObject().apply {
        put("on", nightLightOn)
        put("color", String.format("#%06X", nightLightColor and 0xFFFFFF))
        put("brightness", nightLightBrightness)
        val remaining = BedtimeTimerPolicy.remainingMs(nightLightAutoStopAtMs, System.currentTimeMillis())
        put("remainingMs", remaining)
        put("autoStopAtMs", if (remaining > 0L) nightLightAutoStopAtMs else 0L)
    }

    // #490: the night light has its own timer. It never stops the soother; the screen locks only
    // after both comfort features are off, so "sound until 8, light until 9" remains possible.
    private val nightLightTimerGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var nightLightAutoStopRunnable: Runnable? = null

    private fun scheduleNightLightAutoStop(durationMs: Long?) {
        val gen = nightLightTimerGeneration.incrementAndGet()
        nightLightAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        nightLightAutoStopRunnable = null
        if (durationMs == null) {
            nightLightAutoStopAtMs = 0L
            return
        }
        nightLightAutoStopAtMs = System.currentTimeMillis() + durationMs
        val r = Runnable {
            if (!isServiceAlive || gen != nightLightTimerGeneration.get()) return@Runnable
            nightLightAutoStopAtMs = 0L
            nightLightAutoStopRunnable = null
            nightLightOn = false
            applyNightLight()
            LogManager.i(LogCategory.SYSTEM, "Night-light timer elapsed — light off")
            lockScreenIfBedtimeFinished("Night-light")
        }
        nightLightAutoStopRunnable = r
        mainHandler.postDelayed(r, durationMs)
    }

    private fun cancelNightLightAutoStop() {
        nightLightTimerGeneration.incrementAndGet()
        nightLightAutoStopRunnable?.let { mainHandler.removeCallbacks(it) }
        nightLightAutoStopRunnable = null
        nightLightAutoStopAtMs = 0L
    }

    private fun lockScreenIfBedtimeFinished(source: String) {
        if (!BedtimeTimerPolicy.shouldLockScreen(nightLightOn, sootherPlayer.isPlaying())) {
            LogManager.i(LogCategory.SYSTEM, "$source timer finished — other comfort feature remains on")
            return
        }
        val locked = ScreenOffController.lockNow(applicationContext)
        LogManager.i(LogCategory.SYSTEM, "$source timer finished — screen lock=$locked")
    }

    /**
     * #308: configure event hooks. enabled=true|false; webhookUrl set/clear (blank or "none" =
     * clear). Validates the URL before persisting. Returns state with a success flag.
     */
    private fun handleHooksCommand(enabledStr: String?, urlStr: String?, lanOnlyStr: String?): JSONObject {
        val hooks = eventHooks ?: return JSONObject().put("success", false).put("error", "not_ready")
        // #332: parse + validate EVERY param into a local before mutating `hooks` — otherwise an
        // invalid later param (e.g. lanOnly) returns an error after an earlier one already flipped
        // hooks.enabled in memory, so a "failed" request could still start sending events.
        var newUrl = hooks.webhookUrl
        if (urlStr != null) {
            newUrl = if (urlStr.isBlank() || urlStr.trim().equals("none", ignoreCase = true)) {
                null
            } else {
                de.rochefort.childmonitor.events.EventHookPolicy.validateWebhookUrl(urlStr)
                    ?: return JSONObject().put("success", false).put("error", "invalid_webhook_url")
            }
        }
        var newEnabled = hooks.enabled
        when (enabledStr?.trim()?.lowercase()) {
            null -> { /* leave as-is */ }
            "true", "1", "on", "yes" -> newEnabled = true
            "false", "0", "off", "no" -> newEnabled = false
            else -> return JSONObject().put("success", false).put("error", "invalid_enabled")
        }
        var newLanOnly = hooks.lanOnly
        if (lanOnlyStr != null) {
            newLanOnly = parseBoolOrNull(lanOnlyStr)
                ?: return JSONObject().put("success", false).put("error", "invalid_lanOnly")
        }
        // All inputs valid → commit atomically.
        hooks.webhookUrl = newUrl
        hooks.enabled = newEnabled
        hooks.lanOnly = newLanOnly
        getSharedPreferences(MONITOR_PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_HOOKS_ENABLED, hooks.enabled)
            .putString(PREF_HOOKS_URL, hooks.webhookUrl)
            .putBoolean(PREF_HOOKS_LAN_ONLY, hooks.lanOnly)
            .apply()
        LogManager.i(LogCategory.SYSTEM, "Event hooks: enabled=${hooks.enabled} webhook=${hooks.webhookUrl != null} lanOnly=${hooks.lanOnly}")
        return hooksStateJson().put("success", true)
    }

    /** #308: fire a synthetic "test" event so the user can verify their hooks are wired up. */
    private fun handleHooksTest(): JSONObject {
        val hooks = eventHooks ?: return JSONObject().put("success", false).put("error", "not_ready")
        if (!hooks.enabled) return JSONObject().put("success", false).put("error", "hooks_disabled")
        hooks.dispatch(de.rochefort.childmonitor.events.EventHookPolicy.EVENT_TEST, mapOf("source" to "api"))
        return hooksStateJson().put("success", true).put("dispatched", true)
    }

    /** #305/#315: authenticated, TLS-only MQTT configuration update from the parent. */
    private fun handleMqttConfigCommand(request: JSONObject): JSONObject {
        val deviceId = ChildIdentity.getId(this)
        val stored = de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.load(this, deviceId)
        val old = stored.config

        fun value(name: String, fallback: String): String =
            if (request.has(name)) request.optString(name, fallback) else fallback
        fun strictBool(name: String, fallback: Boolean): Boolean? {
            if (!request.has(name)) return fallback
            return when (request.optString(name).lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
        }
        fun number(name: String, fallback: Double): Double? =
            if (!request.has(name)) fallback else request.optString(name).toDoubleOrNull()

        val enabled = strictBool("enabled", old.enabled)
            ?: return JSONObject().put("success", false).put("error", "invalid_enabled")
        val tMin = number("temperature-min", old.temperatureMinC)
            ?: return JSONObject().put("success", false).put("error", "invalid_temperature_range")
        val tMax = number("temperature-max", old.temperatureMaxC)
            ?: return JSONObject().put("success", false).put("error", "invalid_temperature_range")
        val hMin = number("humidity-min", old.humidityMinPct)
            ?: return JSONObject().put("success", false).put("error", "invalid_humidity_range")
        val hMax = number("humidity-max", old.humidityMaxPct)
            ?: return JSONObject().put("success", false).put("error", "invalid_humidity_range")
        val config = old.copy(
            enabled = enabled,
            serverUri = value("server", old.serverUri).trim(),
            username = value("username", old.username).trim(),
            baseTopic = value("base-topic", old.baseTopic).trim(),
            temperatureTopic = value("temperature-topic", old.temperatureTopic).trim(),
            humidityTopic = value("humidity-topic", old.humidityTopic).trim(),
            temperatureMinC = tMin,
            temperatureMaxC = tMax,
            humidityMinPct = hMin,
            humidityMaxPct = hMax,
        )
        val passwordUpdate = when (request.optString("password-action", "keep").lowercase()) {
            "keep" -> de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.PasswordUpdate.Keep
            "clear" -> de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.PasswordUpdate.Clear
            "set" -> {
                val password = request.optString("password", "")
                if (password.isBlank() || password.length > 512) {
                    return JSONObject().put("success", false).put("error", "invalid_password")
                }
                de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.PasswordUpdate.Set(password)
            }
            else -> return JSONObject().put("success", false).put("error", "invalid_password_action")
        }
        val effectivePasswordSet = when (passwordUpdate) {
            de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.PasswordUpdate.Clear -> false
            is de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.PasswordUpdate.Set -> true
            de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.PasswordUpdate.Keep -> stored.passwordSet
        }
        val validation = de.rochefort.childmonitor.integrations.mqtt.MqttConfigValidation
            .error(config, effectivePasswordSet)
        if (validation != null) return JSONObject().put("success", false).put("error", validation)
        if (!de.rochefort.childmonitor.integrations.mqtt.MqttConfigStore.save(
                this, deviceId, config, passwordUpdate,
            )
        ) return JSONObject().put("success", false).put("error", "save_failed")
        mqttIntegration?.reload()
        return (mqttIntegration?.stateJson() ?: JSONObject())
            .put("success", true)
            .put("enabled", config.enabled)
            .put("baseTopic", config.baseTopic)
            .put("passwordSet", effectivePasswordSet)
    }

    /** #284: last [seconds] (clamped 1..60) of buffered mu-law audio (8 kHz mono) for replay. */
    fun replayAudio(seconds: Int): ByteArray =
        audioReplay.lastSeconds(seconds.coerceIn(1, 60), android.os.SystemClock.elapsedRealtime(), REPLAY_STALE_MS)

    /** #337: monotonic timestamps of buffered replay snapshots (within the staleness window). */
    fun replaySnapshotTimestamps(): List<Long> =
        snapshotReplay.timestamps(android.os.SystemClock.elapsedRealtime(), REPLAY_STALE_MS)

    /** #337: the buffered JPEG snapshot captured at [timestampMs], or null if gone/stale. */
    fun replaySnapshotAt(timestampMs: Long): ByteArray? =
        snapshotReplay.frameAt(timestampMs, android.os.SystemClock.elapsedRealtime(), REPLAY_STALE_MS)?.jpeg

    /** #286/#266/#475: persist a detected sound event, expose it to parents, and fire hooks. */
    private fun onSoundEvent(ev: de.rochefort.childmonitor.events.SoundEventDetector.Event) {
        val observedElapsed = android.os.SystemClock.elapsedRealtime()
        // The detector's startMs is monotonic. Convert its age at observation to a wall-clock event
        // time rather than ever treating elapsedRealtime as an epoch timestamp (#475).
        val occurredAtEpoch = (
            System.currentTimeMillis() - (observedElapsed - ev.startMs).coerceAtLeast(0L)
        ).coerceAtLeast(0L)
        val storeId = try {
            eventStore.insertSoundEvent(ev, occurredAtEpochMs = occurredAtEpoch)
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Failed to persist sound event", e)
            -1L
        }
        lastSoundEvent = StoredSoundEvent(ev, storeId, occurredAtEpoch, observedElapsed)
        LogManager.i(
            LogCategory.AUDIO,
            "Sound event: ${ev.type} conf=${"%.2f".format(ev.confidence)} dur=${ev.durationMs}ms peak=${"%.0f".format(ev.peakDb)}dB"
        )
        // #291: the per-trigger toggle + alert-worthiness + quiet-hours escalation must all pass.
        // One volatile read → a consistent snapshot of all three config fields.
        if (de.rochefort.childmonitor.events.SoundAlertGate.shouldAlert(ev.type, ev.confidence, alertConfig)) {
            // Cooldown so a baby crying in bursts doesn't fire a webhook/broadcast per burst.
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSoundAlertMs >= SOUND_ALERT_COOLDOWN_MS) {
                lastSoundAlertMs = now
                val privateDetails = mapOf(
                    "type" to ev.type,
                    "confidence" to "%.2f".format(ev.confidence),
                    "durationMs" to ev.durationMs.toString(),
                    "eventId" to storeId.toString(),
                )
                val publicDetails = mapOf(
                    "type" to ev.type,
                    "confidence" to "%.2f".format(ev.confidence),
                )
                // #333: keep the generic automation event and emit the richer classifier event.
                // The implicit broadcast intentionally omits duration and durable database ID.
                eventHooks?.dispatch(
                    de.rochefort.childmonitor.events.EventHookPolicy.EVENT_SOUND,
                    privateDetails,
                    publicDetails,
                )
                if (ev.type == "cry") {
                    eventHooks?.dispatch(
                        de.rochefort.childmonitor.events.EventHookPolicy.EVENT_CRY,
                        privateDetails,
                        publicDetails,
                    )
                }
                mqttIntegration?.publishSound()
            }
        }
    }

    /** #286: most recent (and recent-enough) sound event, for /api/status. */
    fun soundEventJson(): JSONObject {
        val stored = lastSoundEvent
        val ageMs = stored?.let { android.os.SystemClock.elapsedRealtime() - it.observedAtElapsedMs }
            ?: Long.MAX_VALUE
        if (stored == null || ageMs > SOUND_EVENT_STALE_MS) {
            return JSONObject().put("type", JSONObject.NULL)
        }
        val ev = stored.event
        return JSONObject().apply {
            put("id", stored.storeId)
            put("type", ev.type)
            put("confidence", ev.confidence)
            put("durationMs", ev.durationMs)
            put("occurredAtMs", stored.occurredAtEpochMs)
            put("ageMs", ageMs)
            // alertWorthy = intrinsic policy verdict; wouldAlert = full gate under the current
            // per-trigger toggles + quiet hours (#291), useful for verifying config from /api/status.
            put("alertWorthy", de.rochefort.childmonitor.events.SoundAlertPolicy.isAlertWorthy(ev.type, ev.confidence))
            put("wouldAlert", de.rochefort.childmonitor.events.SoundAlertGate.shouldAlert(
                ev.type, ev.confidence, alertConfig))
        }
    }

    /** #266/#333/#343: persist continuous child-side motion and dispatch gated automation. */
    private fun onMotionEvent(ev: de.rochefort.childmonitor.events.MotionEventDetector.Event) {
        val observedElapsed = android.os.SystemClock.elapsedRealtime()
        val occurredAtEpoch = (
            System.currentTimeMillis() - (observedElapsed - ev.startMs).coerceAtLeast(0L)
        ).coerceAtLeast(0L)
        val storeId = try {
            eventStore.insertGeneric(
                type = "motion",
                confidence = ev.confidence,
                durationMs = ev.durationMs,
                occurredAtEpochMs = occurredAtEpoch,
                attrs = mapOf("changedPercent" to ev.changedPercent),
            )
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Failed to persist motion event", e)
            -1L
        }
        lastMotionEvent = StoredMotionEvent(ev, storeId, occurredAtEpoch, observedElapsed)
        LogManager.i(
            LogCategory.SYSTEM,
            "Motion event: changed=${ev.changedPercent}% conf=${"%.2f".format(ev.confidence)}",
        )
        if (de.rochefort.childmonitor.events.SoundAlertGate.shouldAlert(
                "motion", ev.confidence, alertConfig
            )
        ) {
            eventHooks?.dispatch(
                de.rochefort.childmonitor.events.EventHookPolicy.EVENT_MOTION,
                details = mapOf(
                    "confidence" to "%.2f".format(ev.confidence),
                    "changedPercent" to ev.changedPercent.toString(),
                    "durationMs" to ev.durationMs.toString(),
                    "eventId" to storeId.toString(),
                ),
                broadcastDetails = mapOf(
                    "confidence" to "%.2f".format(ev.confidence),
                    "changedPercent" to ev.changedPercent.toString(),
                ),
            )
            mqttIntegration?.publishMotion()
        }
    }

    fun motionEventJson(): JSONObject {
        val stored = lastMotionEvent
        val ageMs = stored?.let { android.os.SystemClock.elapsedRealtime() - it.observedAtElapsedMs }
            ?: Long.MAX_VALUE
        if (stored == null || ageMs > MOTION_EVENT_STALE_MS) {
            return JSONObject().put("type", JSONObject.NULL)
        }
        val ev = stored.event
        return JSONObject().apply {
            put("id", stored.storeId)
            put("type", "motion")
            put("confidence", ev.confidence)
            put("changedPercent", ev.changedPercent)
            put("durationMs", ev.durationMs)
            put("occurredAtMs", stored.occurredAtEpochMs)
            put("ageMs", ageMs)
            put("alertWorthy", ev.confidence >= 0.18)
            put("wouldAlert", de.rochefort.childmonitor.events.SoundAlertGate.shouldAlert(
                "motion", ev.confidence, alertConfig,
            ))
        }
    }

    /** #291: current per-trigger alert toggles + quiet-hours config, for /api/status. */
    fun alertConfigJson(): JSONObject {
        val cfg = alertConfig
        val q = cfg.quietHours
        return JSONObject().apply {
            put("cryAlerts", cfg.cryEnabled)
            put("coughAlerts", cfg.coughEnabled)
            put("motionAlerts", cfg.motionEnabled)
            put("quietHours", JSONObject().apply {
                put("enabled", q.enabled)
                put("start", WireFormat.hourMinute(q.startHour, q.startMinute))
                put("end", WireFormat.hourMinute(q.endHour, q.endMinute))
                // Round for display: prefs store the floor as a float, so the widened double would
                // otherwise read e.g. 0.8500000238 in the JSON. The gate still uses the raw value.
                put("escalationFloor", Math.round(q.escalationFloor * 100.0) / 100.0)
                put("activeNow", q.isActiveNow())
            })
        }
    }

    /**
     * #291: configure per-trigger alert toggles + quiet hours. All params optional (omit = leave
     * as-is): cry/cough = true|false; quiet = true|false (enable the quiet window); quietStart /
     * quietEnd = "HH:MM" (24h); floor = 0..1 escalation floor (events below it are suppressed
     * during quiet hours). Persists to prefs. Read state via /api/status.alertConfig.
     */
    private fun handleAlertConfigCommand(
        cryStr: String?, coughStr: String?, motionStr: String?,
        quietStr: String?, quietStartStr: String?, quietEndStr: String?, floorStr: String?
    ): JSONObject {
        var cfg = alertConfig
        if (cryStr != null) {
            val v = parseBoolOrNull(cryStr)
                ?: return JSONObject().put("success", false).put("error", "invalid_cry")
            cfg = cfg.copy(cryEnabled = v)
        }
        if (coughStr != null) {
            val v = parseBoolOrNull(coughStr)
                ?: return JSONObject().put("success", false).put("error", "invalid_cough")
            cfg = cfg.copy(coughEnabled = v)
        }
        if (motionStr != null) {
            val v = parseBoolOrNull(motionStr)
                ?: return JSONObject().put("success", false).put("error", "invalid_motion")
            cfg = cfg.copy(motionEnabled = v)
        }

        var q = cfg.quietHours
        if (quietStr != null) {
            val en = parseBoolOrNull(quietStr)
                ?: return JSONObject().put("success", false).put("error", "invalid_quiet")
            q = q.copy(enabled = en)
        }
        if (quietStartStr != null) {
            val parsed = de.rochefort.childmonitor.events.SoundAlertGate.parseHhMm(quietStartStr)
                ?: return JSONObject().put("success", false).put("error", "invalid_quietStart")
            q = q.copy(startHour = parsed.first, startMinute = parsed.second)
        }
        if (quietEndStr != null) {
            val parsed = de.rochefort.childmonitor.events.SoundAlertGate.parseHhMm(quietEndStr)
                ?: return JSONObject().put("success", false).put("error", "invalid_quietEnd")
            q = q.copy(endHour = parsed.first, endMinute = parsed.second)
        }
        if (floorStr != null) {
            val f = floorStr.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
                ?: return JSONObject().put("success", false).put("error", "invalid_floor")
            q = q.copy(escalationFloor = f)
        }
        cfg = cfg.copy(quietHours = q)

        // Single atomic swap so onSoundEvent never sees a half-applied config.
        alertConfig = cfg
        getSharedPreferences(MONITOR_PREFS, MODE_PRIVATE).edit()
            .putBoolean(PREF_CRY_ALERTS, cfg.cryEnabled)
            .putBoolean(PREF_COUGH_ALERTS, cfg.coughEnabled)
            .putBoolean(PREF_MOTION_ALERTS, cfg.motionEnabled)
            .putBoolean(PREF_QUIET_ENABLED, q.enabled)
            .putInt(PREF_QUIET_START_H, q.startHour)
            .putInt(PREF_QUIET_START_M, q.startMinute)
            .putInt(PREF_QUIET_END_H, q.endHour)
            .putInt(PREF_QUIET_END_M, q.endMinute)
            .putFloat(PREF_QUIET_FLOOR, q.escalationFloor.toFloat())
            .apply()
        LogManager.i(
            LogCategory.SYSTEM,
            "Alert config: cry=${cfg.cryEnabled} cough=${cfg.coughEnabled} motion=${cfg.motionEnabled} quiet=${q.enabled} ${q.startHour}:${q.startMinute}-${q.endHour}:${q.endMinute} floor=${q.escalationFloor}"
        )
        return alertConfigJson().put("success", true)
    }

    /** Tri-state boolean parse for API flags: true/1/on/yes, false/0/off/no, else null (invalid). */
    private fun parseBoolOrNull(s: String): Boolean? = when (s.trim().lowercase()) {
        "true", "1", "on", "yes" -> true
        "false", "0", "off", "no" -> false
        else -> null
    }

    /** #308: current event-hook config (also embedded in /api/status). */
    fun hooksStateJson(): JSONObject = JSONObject().apply {
        put("enabled", eventHooks?.enabled ?: false)
        put("webhookUrl", eventHooks?.webhookUrl ?: JSONObject.NULL)
        put("lanOnly", eventHooks?.lanOnly ?: false) // #332
    }

    /**
     * #301 (child side): battery telemetry so the parent can show the camera phone's charge and
     * warn before it dies mid-nap. level is 0..100 or -1 when unknown.
     */
    fun batteryStateJson(): JSONObject {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val rawLevel = try {
            bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
        val level = de.rochefort.childmonitor.util.BatteryTelemetryPolicy.normalizeLevel(rawLevel)
        val plugged = try {
            registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        } catch (e: Exception) {
            -1
        }
        val charging = plugged > 0 ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && try { bm.isCharging } catch (e: Exception) { false })
        return JSONObject().apply {
            put("level", level)
            put("charging", charging)
            put("chargingSource", de.rochefort.childmonitor.util.BatteryTelemetryPolicy.chargingSource(plugged))
            put("powerSaveMode", isPowerSaveMode())
            put("low", de.rochefort.childmonitor.util.BatteryTelemetryPolicy.isLow(level, charging))
        }
    }

    private fun stopHeartbeat() {
        isHeartbeatActive = false
        val t = heartbeatThread
        heartbeatThread = null
        t?.interrupt()
        // #194-9: join the heartbeat thread before returning. Without this, an
        // onDestroy that races with refreshNsdRegistration → nsdManager
        // .unregisterService could double-unregister the listener and throw
        // IllegalArgumentException, OR the heartbeat could call into the
        // RetentionEngine after Stores are torn down. 500 ms is enough for the
        // Thread.sleep(60_000) tick to wake on interrupt and exit cleanly.
        try { t?.join(500) } catch (_: InterruptedException) {}
    }

    @androidx.annotation.OptIn(
        markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class]
    )
    private fun startCameraStream() {
        if (!cameraEnabled) {
            LogManager.i(LogCategory.SYSTEM, "Camera streaming is disabled by user; not starting")
            cameraActive = false
            return
        }
        try {
            cameraStreamer?.onCameraStateChanged = { running, error ->
                cameraActive = running
                if (running) {
                    LogManager.i(LogCategory.SYSTEM, "Camera snapshot stream started successfully")
                } else if (error != null) {
                    LogManager.e(LogCategory.SYSTEM, "Camera stream failed: $error")
                }
                // Update UI on main thread
                monitorActivity?.runOnUiThread {
                    monitorActivity?.updateCameraUi(cameraEnabled, cameraActive)
                }
            }
            cameraStreamer?.start(this)
            recomputeSnapshotCadence() // #287: apply the current cadence to the fresh streamer
            LogManager.i(LogCategory.SYSTEM, "Camera stream initialization requested")
            // Don't set cameraActive here - wait for callback
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Camera stream failed to start", e)
            cameraActive = false
        }
    }

    private fun ensureWebServer() {
        // Review-pass-2 HIGH-2: serialise so the heartbeat retry path and the
        // initial onStartCommand entry can't race past the (webServer == null)
        // check and double-bind port 11000.
        synchronized(webServerLock) {
            if (tlsIdentity == null) {
                tlsIdentity = de.rochefort.childmonitor.security.TlsIdentity.getInstance(applicationContext)
            }
            val needsTls = !tlsPermanentlyUnavailable && webServerTls == null
            if (webServer != null && !needsTls) return

            if (webServer == null) {
                try {
                    val srv = buildWebServer(port = webServerPort, sslFactory = null)
                    try {
                        srv.start()
                        webServer = srv
                        LogManager.i(LogCategory.SYSTEM, "Web console available on port $webServerPort")
                    } catch (e: Exception) {
                        // Review-pass-2 MED-2: stop the half-initialised instance so the
                        // NanoHTTPD-allocated thread pool / socket doesn't leak.
                        try { srv.stop() } catch (_: Exception) {}
                        throw e
                    }
                } catch (e: Exception) {
                    LogManager.e(LogCategory.SYSTEM, "Failed to start web console", e)
                }
            }

            if (webServerTls == null && !tlsPermanentlyUnavailable) {
                val sslFactory = tlsIdentity?.newServerSocketFactory()
                if (sslFactory == null) {
                    val err = tlsIdentity?.lastInitError ?: "no identity yet"
                    // Pass-3 HIGH-2: classify via TlsIdentity so gen-failed,
                    // OOM, etc. don't keep re-burning a heartbeat tick.
                    if (tlsIdentity?.isInitErrorPermanent() == true) {
                        tlsPermanentlyUnavailable = true
                        LogManager.w(LogCategory.SYSTEM, "TLS permanently unavailable ($err); plaintext only — no further retries")
                    } else if (!tlsRetryLogged) {
                        // Pass-3 HIGH-1: log this exactly once per retry
                        // streak. The user reading the diagnostic bundle
                        // sees one line per occurrence, not 720.
                        tlsRetryLogged = true
                        LogManager.i(LogCategory.SYSTEM, "TLS not ready yet ($err); will retry every heartbeat")
                    }
                } else {
                    try {
                        // Pass-3 MED-1: the lock held here covers
                        // newServerSocketFactory() (cheap — keygen happens in
                        // TlsIdentity.loadOrCreate, not here) and NanoHTTPD
                        // srv.start() (socket bind + thread-pool init,
                        // bounded to tens of ms; no blocking I/O). Safe to
                        // hold across both.
                        val srv = buildWebServer(port = webServerTlsPort, sslFactory = sslFactory)
                        try {
                            srv.start()
                            webServerTls = srv
                            setTlsListenerPortInternal(webServerTlsPort)
                            tlsRetryLogged = false  // reset for any future failure window
                            LogManager.i(LogCategory.SYSTEM, "Web console TLS on port $webServerTlsPort (fp=${tlsIdentity?.fingerprintSha256()?.take(20)}…)")
                        } catch (e: Exception) {
                            try { srv.stop() } catch (_: Exception) {}
                            throw e
                        }
                    } catch (e: Exception) {
                        LogManager.e(LogCategory.SYSTEM, "Failed to start TLS web console", e)
                    }
                }
            }
        }
    }

    private fun buildWebServer(port: Int, sslFactory: javax.net.ssl.SSLServerSocketFactory?): WebControlServer {
        return WebControlServer(
            port,
            statusProvider = { buildStatusJson() },
            // #300: in audio-only mode return no frame (204) rather than a stale last frame
            // cached before a runtime toggle — so /snapshot reflects "camera off", not "frozen".
            snapshotProvider = { if (cameraEnabled) cameraStreamer?.latestJpeg() else null },
            // #287: remote snapshot-cadence control. glanceMs takes priority (near-live while
            // actively watching); otherwise set the mode. parseMode rejects unknown strings.
            snapshotCadenceHandler = { modeStr, glanceMs ->
                if (glanceMs != null) {
                    requestSnapshotGlance(glanceMs).put("success", true)
                } else {
                    val m = de.rochefort.childmonitor.video.SnapshotCadencePolicy.parseMode(modeStr)
                    if (m == null) JSONObject().put("success", false).put("error", "invalid_mode")
                    else setSnapshotMode(m).put("success", true)
                }
            },
            // #311: remote soother (white/pink/brown noise) control.
            sootherHandler = { action, sound, volume, durationMs ->
                handleSootherCommand(action, sound, volume, durationMs)
            },
            // #312: remote night-light control.
            nightLightHandler = { on, color, brightness, durationMs ->
                handleNightLightCommand(on, color, brightness, durationMs)
            },
            // #284: last-N-seconds audio replay.
            replayAudioProvider = { seconds -> replayAudio(seconds) },
            // #337: snapshot replay — list buffered frame timestamps + fetch a frame by timestamp.
            replaySnapshotTimestampsProvider = { replaySnapshotTimestamps() },
            replaySnapshotFrameProvider = { ts -> replaySnapshotAt(ts) },
            // #308: event-hook config + test.
            hooksHandler = { enabled, url, lanOnly -> handleHooksCommand(enabled, url, lanOnly) },
            hooksTestHandler = { handleHooksTest() },
            // #291: per-trigger alert toggles + quiet hours.
            alertConfigHandler = { cry, cough, motion, quiet, quietStart, quietEnd, floor ->
                handleAlertConfigCommand(cry, cough, motion, quiet, quietStart, quietEnd, floor)
            },
            mqttConfigHandler = { request -> handleMqttConfigCommand(request) },
            cameraToggleHandler = {
                val newState = !cameraEnabled
                setCameraEnabled(newState)
                newState
            },
            talkbackAudioHandler = { source, audioData -> playTalkbackAudio(source, audioData) },
            talkbackStoppedHandler = { source ->
                synchronized(talkbackLock) {
                    talkbackActivity.remove(source)
                    if (!talkbackActivity.hasActiveFrames()) stopTalkbackLocked()
                }
            },
            pairingChallengeProvider = {
                val host = localAddresses().firstOrNull() ?: "0.0.0.0"
                // Review-pass-1 #5: scope the challenge to the port the caller
                // actually reached us on so a TLS-bound pairing challenge can't
                // be cross-wired with the plaintext port in a future auth path.
                val challenge = pairingManager.createChallenge(host, port)
                de.rochefort.childmonitor.security.AuditLog.append(
                    applicationContext,
                    de.rochefort.childmonitor.security.AuditLog.EVT_PAIR_START,
                    mapOf("host" to host, "port" to port)
                )
                challenge
            },
            pairingCompleteHandler = { code, deviceId, deviceName ->
                val result = pairingManager.completePairing(code, deviceId, deviceName)
                val ok = result.optBoolean("success", false)
                de.rochefort.childmonitor.security.AuditLog.append(
                    applicationContext,
                    if (ok) de.rochefort.childmonitor.security.AuditLog.EVT_PAIR_COMPLETE
                    else de.rochefort.childmonitor.security.AuditLog.EVT_PAIR_REJECT,
                    mapOf("deviceId" to deviceId, "deviceName" to deviceName)
                )
                result
            },
            pairedDevicesProvider = {
                pairingManager.devicesAsJson()
            },
            pairingRevokeHandler = { deviceId ->
                val removed = pairingManager.revokeDevice(deviceId)
                val hadCredentials = sessionAuthority.hasCredentialsForDevice(deviceId)
                if (removed || hadCredentials) {
                    sessionAuthority.revokeAllForDevice(deviceId)
                    connectedParents.filter { it.deviceId == deviceId }.forEach { it.writer.close() }
                    webServer?.revokeDevice(deviceId)
                    webServerTls?.revokeDevice(deviceId)
                    castSessionAuthority.revokeForParent(deviceId)
                    de.rochefort.childmonitor.security.AuditLog.append(
                        applicationContext,
                        de.rochefort.childmonitor.security.AuditLog.EVT_PAIR_REVOKE,
                        mapOf("deviceId" to deviceId)
                    )
                }
                removed || hadCredentials
            },
            castSessionIssuer = { bearer ->
                val parent = sessionAuthority.validate(bearer)
                if (parent == null) {
                    null
                } else {
                    val grant = castSessionAuthority.issue(parent.deviceId)
                    de.rochefort.childmonitor.security.AuditLog.append(
                        applicationContext,
                        de.rochefort.childmonitor.security.AuditLog.EVT_CAST_ISSUE,
                        mapOf(
                            "deviceId" to parent.deviceId,
                            "tokenPrefix" to grant.token.take(8),
                            "expiresAtMs" to grant.expiresAtMs,
                        ),
                    )
                    JSONObject()
                        .put("token", grant.token)
                        .put("expiresAtMs", grant.expiresAtMs)
                        .put("ttlMs", grant.expiresAtMs - grant.createdAtMs)
                }
            },
            castSessionRevoker = { bearer ->
                val parent = sessionAuthority.validate(bearer)
                if (parent == null) {
                    false
                } else {
                    val revoked = castSessionAuthority.revokeForParent(parent.deviceId)
                    if (revoked > 0) {
                        de.rochefort.childmonitor.security.AuditLog.append(
                            applicationContext,
                            de.rochefort.childmonitor.security.AuditLog.EVT_CAST_REVOKE,
                            mapOf("deviceId" to parent.deviceId, "count" to revoked),
                        )
                    }
                    revoked > 0
                }
            },
            castTokenValidator = { token, address ->
                castSessionAuthority.validateAndBind(token, address)
            },
            castViewerTextProvider = {
                getString(R.string.localDisplayViewerTitle) to
                    getString(R.string.localDisplayViewerCaption)
            },
            tokenValidator = { token -> sessionAuthority.validate(token) != null },
            localAdminValidator = de.rochefort.childmonitor.security.LocalControlAuthority::validates,
            authenticatedDeviceIdProvider = { token -> sessionAuthority.validate(token)?.deviceId },
            audioListenersChangedHandler = ::webAudioListenersChanged,
            tokenIssuer = { code, deviceId, deviceName ->
                if (!authRateLimiter.allow(deviceId)) {
                    de.rochefort.childmonitor.security.AuditLog.append(
                        applicationContext,
                        de.rochefort.childmonitor.security.AuditLog.EVT_AUTH_FAIL,
                        mapOf("deviceId" to deviceId, "reason" to "rate_limited")
                    )
                    null
                } else {
                    val result = pairingManager.completePairing(code, deviceId, deviceName)
                    if (!result.optBoolean("success", false)) {
                        de.rochefort.childmonitor.security.AuditLog.append(
                            applicationContext,
                            de.rochefort.childmonitor.security.AuditLog.EVT_AUTH_FAIL,
                            mapOf("deviceId" to deviceId, "reason" to result.optString("error"))
                        )
                        null
                    } else {
                        val token = sessionAuthority.issueToken(deviceId)
                        de.rochefort.childmonitor.security.AuditLog.append(
                            applicationContext,
                            de.rochefort.childmonitor.security.AuditLog.EVT_SESSION_ISSUE,
                            mapOf("deviceId" to deviceId, "tokenPrefix" to token.optString("token").take(8))
                        )
                        token
                    }
                }
            },
            // #298: rotate the caller's bearer to a fresh token (same device), invalidating the old.
            tokenRotator = { currentToken ->
                val rotated = sessionAuthority.rotateToken(currentToken)
                if (rotated != null) {
                    de.rochefort.childmonitor.security.AuditLog.append(
                        applicationContext,
                        de.rochefort.childmonitor.security.AuditLog.EVT_SESSION_ROTATE,
                        mapOf(
                            "deviceId" to rotated.optString("deviceId"),
                            "tokenPrefix" to rotated.optString("token").take(8)
                        )
                    )
                }
                rotated
            },
            // #348: mint a durable refresh credential for the authenticated caller's device (the
            // bearer is already validated by the central gate; we re-resolve it to the deviceId).
            refreshCredentialIssuer = { bearer, parentDeviceId ->
                val session = sessionAuthority.validate(bearer)
                if (session == null) {
                    null
                } else {
                    val deviceId = session.deviceId
                    val cred = if (parentDeviceId == null) {
                        // Compatibility: old parents redeem with the QR's child identity.
                        sessionAuthority.issueRefreshToken(deviceId, session.tlsAudioRequired)
                    } else {
                        sessionAuthority.bootstrapParent(bearer, parentDeviceId)
                    }
                    if (cred != null && parentDeviceId != null) {
                        pairingManager.registerAuthenticatedDevice(
                            parentDeviceId, getString(R.string.pairedParentName, parentDeviceId.takeLast(6)),
                        )
                    }
                    de.rochefort.childmonitor.security.AuditLog.append(
                        applicationContext,
                        de.rochefort.childmonitor.security.AuditLog.EVT_SESSION_REFRESH_ISSUE,
                        mapOf("deviceId" to (cred?.optString("deviceId") ?: deviceId))
                    )
                    cred
                }
            },
            // #348: redeem a refresh token for a fresh bearer (rate-limited per device).
            refreshRedeemer = { deviceId, refreshToken ->
                when {
                    // #354: a malformed/missing deviceId is a definitive invalid → null (route → 401).
                    deviceId.isNullOrBlank() -> null
                    // #354: rate-limited → a sentinel (route → 429) so the parent keeps the credential
                    // and backs off, distinct from a genuinely-revoked token (null → 401).
                    !authRateLimiter.allow(deviceId) -> {
                        de.rochefort.childmonitor.security.AuditLog.append(
                            applicationContext,
                            de.rochefort.childmonitor.security.AuditLog.EVT_AUTH_FAIL,
                            mapOf("deviceId" to deviceId, "reason" to "rate_limited", "uri" to "/api/session/refresh")
                        )
                        org.json.JSONObject().put("rateLimited", true)
                    }
                    else -> {
                        val bearer = sessionAuthority.redeemRefreshToken(deviceId, refreshToken)
                        de.rochefort.childmonitor.security.AuditLog.append(
                            applicationContext,
                            if (bearer != null) de.rochefort.childmonitor.security.AuditLog.EVT_SESSION_REFRESH
                            else de.rochefort.childmonitor.security.AuditLog.EVT_AUTH_FAIL,
                            mapOf("deviceId" to deviceId, "reason" to if (bearer == null) "refresh_reject" else "ok")
                        )
                        bearer
                    }
                }
            },
            authFailureListener = { uri, addr ->
                de.rochefort.childmonitor.security.AuditLog.append(
                    applicationContext,
                    de.rochefort.childmonitor.security.AuditLog.EVT_AUTH_FAIL,
                    mapOf("uri" to uri, "remote" to addr)
                )
            },
            metricsProvider = {
                de.rochefort.childmonitor.observability.Metrics.snapshot(
                    status = buildStatusJson(),
                    sessions = sessionAuthority.listSessions()
                )
            },
            fingerprintProvider = { tlsIdentity?.fingerprintSha256() },
            httpsPortProvider = { if (webServerTls != null) webServerTlsPort else null },
            audioTlsPortProvider = { tlsAudioListenerPort },
            sslSocketFactory = sslFactory,
            // #348: this instance is the TLS listener iff it was built with an SSL factory. The
            // refresh endpoints reject cleartext-LAN callers (TLS-or-loopback only).
            isTlsListener = sslFactory != null
        )
    }

    private fun stopWebServer() {
        try {
            webServer?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Web server stop failed", e)
        }
        webServer = null
        try {
            webServerTls?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "TLS web server stop failed", e)
        }
        webServerTls = null
        setTlsListenerPortInternal(null)
    }

    fun setCameraEnabled(enabled: Boolean) {
        cameraEnabled = enabled
        // #300: persist so audio-only (camera off) survives restarts as a low-power mode.
        getSharedPreferences(MONITOR_PREFS, MODE_PRIVATE).edit().putBoolean(PREF_CAMERA_ENABLED, enabled).apply()
        // Review-pass-3 CRITICAL: stopCameraStream / startCameraStream ultimately
        // call LifecycleRegistry.handleLifecycleEvent which is main-thread-only.
        // This entry point is reachable from the HTTP /api/camera/* endpoints
        // (which run on NanoHTTPD worker threads) as well as the MonitorActivity
        // toggle (already on main). Always marshal to the main handler so the
        // worker-thread path doesn't throw IllegalStateException.
        mainHandler.post {
            if (!isServiceAlive) return@post
            // #327: keep the CAMERA foreground-service type in sync with the runtime toggle. We're on
            // the main thread here, so promote SYNCHRONOUSLY in the right order: when enabling, add the
            // CAMERA type BEFORE starting the camera (running it while the type omits CAMERA is the
            // API-34 under-advertise kill window); when disabling, drop it AFTER the camera stops.
            if (enabled) {
                promoteForegroundTypeNow()
                startCameraStream()
            } else {
                stopCameraStream()
                promoteForegroundTypeNow()
            }
            monitorActivity?.runOnUiThread {
                monitorActivity?.updateCameraUi(enabled, cameraActive)
            }
        }
    }

    fun isCameraEnabled(): Boolean = cameraEnabled
    fun isCameraActive(): Boolean = cameraActive

    fun setCaptureCalibrationMultiplier(multiplier: Float) {
        val safe = AudioCalibrationPrefs.clampCapture(multiplier)
        captureCalibrationMultiplier = safe
        AudioCalibrationPrefs.setCaptureGainMultiplier(this, safe)
        LogManager.i(LogCategory.AUDIO, "Capture calibration updated to ${"%.2f".format(safe)}x")
    }

    fun getCaptureCalibrationMultiplier(): Float = captureCalibrationMultiplier

    private fun stopCameraStream() {
        try {
            cameraStreamer?.stop()
            // cameraActive will be set to false by the callback, but set it here too for immediate effect
            cameraActive = false
            LogManager.i(LogCategory.SYSTEM, "Camera snapshot stream stopped")
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Camera stream failed to stop", e)
            cameraActive = false
        }
    }

    /** Check if camera is actually producing frames */
    fun isCameraProducingFrames(): Boolean = cameraStreamer?.latestJpeg() != null

    /**
     * Initialize the talkback AudioTrack for playing audio from parent device.
     * Uses USAGE_MEDIA / STREAM_MUSIC (same path as main monitoring audio) to ensure
     * audio plays through the main speaker at full volume.
     */
    private fun ensureTalkbackTrack() {
        if (talkbackTrack != null) return

        try {
            // Preserve the user's system volume. Only old app versions that actually boosted
            // it leave a recovery marker; recording a new marker without boosting would wrongly
            // lower a later user-selected volume after a service restart.
            val minBufferSize = AudioTrack.getMinBufferSize(
                talkbackSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            // getMinBufferSize can return ERROR (-1) / ERROR_BAD_VALUE (-2) when the audio
            // engine isn't ready. Fall back so AudioTrack.Builder doesn't throw.
            val effectiveMin = if (minBufferSize > 0) minBufferSize else 4000
            val bufferSize = effectiveMin * 2

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(talkbackSampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val newTrack = AudioTrack(
                audioAttributes,
                audioFormat,
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
            try {
                check(newTrack.state == AudioTrack.STATE_INITIALIZED) { "Talkback output unavailable" }
                newTrack.play()
            } catch (e: Exception) {
                runCatching { newTrack.release() }
                throw e
            }

            // [#228] Publish under the lock with a double-check so a concurrent talkback
            // starter that lost the race discards its extra track instead of orphaning
            // the one already in use.
            synchronized(talkbackLock) {
                if (talkbackTrack != null) {
                    newTrack.release()
                } else {
                    talkbackTrack = newTrack
                }
            }
            LogManager.i(LogCategory.AUDIO, "Talkback AudioTrack initialized via STREAM_MUSIC, buffer=${bufferSize}b")
        } catch (e: Exception) {
            LogManager.e(LogCategory.AUDIO, "Failed to initialize talkback AudioTrack", e)
        }
    }

    /**
     * #327: re-promote the foreground service so its active FGS type bitmask reflects what it's
     * actually doing right now. The child plays talkback + soother audio; on API 34+ the platform can
     * throttle/kill an FGS whose work isn't in its active type set, and the MICROPHONE type may not be
     * held (mic revoked / a future audio-only mode). Called on talkback + soother start/stop.
     *
     * Safe to call repeatedly: the recomputed type includes MIC/CAMERA only when their permission is
     * held (so it never throws SecurityException), and MEDIA_PLAYBACK has no runtime-permission gate.
     * Marshaled to the main thread (callers run on the talkback-audio / web-server threads) and guarded
     * by isServiceAlive so it can't race teardown. No-op below API R, where typed FGS isn't requested.
     */
    private fun refreshForegroundServiceType() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        mainHandler.post {
            if (!isServiceAlive) return@post
            promoteForegroundTypeNow()
        }
    }

    /**
     * Recompute and apply the FGS type SYNCHRONOUSLY. MUST be called on the main thread with the
     * service alive. Callers already on main (the camera toggle) use this directly so the type is
     * updated BEFORE starting the camera — running the camera while the active type omits CAMERA is
     * the API-34 under-advertise window that gets background camera access denied/killed.
     *
     * Safe to call repeatedly: MIC/CAMERA bits are included only when their permission is held (so it
     * never throws SecurityException) and MEDIA_PLAYBACK has no runtime gate. Rebuilds the notification
     * with [currentNotificationDetail] so a transition doesn't wipe an active error banner.
     */
    private fun promoteForegroundTypeNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val hasMic = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            val hasCamera = ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val runCamera = cameraEnabled && hasCamera
            val playingAudio = isTalkbackPlaying || sootherPlayer.isPlaying()
            val type = ForegroundServiceTypePolicy.typeFor(
                Build.VERSION.SDK_INT, hasMic, runCamera, playingAudio
            )
            ServiceCompat.startForeground(this, ID, buildNotification(currentNotificationDetail), type)
        } catch (e: Exception) {
            LogManager.e(LogCategory.SYSTEM, "Foreground service type refresh failed", e)
        }
    }

    /**
     * Play received talkback audio (mu-law encoded) on the speaker.
     *
     * Review-pass-3 HIGH: now consults [AudioCalibrationPrefs.getPlaybackGainMultiplier]
     * so the parent has a loudness knob. Without this, removing the STREAM_MUSIC
     * boost in #194-4 left the child near-inaudible at the user's normal music
     * volume with no in-app remedy.
     */
    private fun playTalkbackAudio(source: Any, mulawData: ByteArray) {
        if (!isServiceAlive || mulawData.isEmpty() || mulawData.size > AudioCodecDefines.FREQUENCY) return

        // Decode mu-law to PCM using G711UCodec (same codec as main audio pipeline)
        val pcmData = ShortArray(mulawData.size)
        val decoded = AudioCodecDefines.CODEC.decode(pcmData, mulawData, mulawData.size, 0)

        // Base 2× compensates for G.711 round-trip amplitude loss; calibration
        // multiplier (clamped by AudioCalibrationPrefs to a safe range) lets the
        // user tune up further if the room speaker is quiet, or down if it's
        // hot. Combined gain is clipped to Short range so it can't blow the DAC.
        val userGain = AudioCalibrationPrefs.getPlaybackGainMultiplier(this)
        val totalGain = 2.0f * userGain
        for (i in 0 until decoded) {
            val amplified = (pcmData[i] * totalGain).toInt()
            pcmData[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        // Write to AudioTrack under [talkbackLock] so a concurrent stopTalkback() can't
        // release the native track mid-write (use-after-free -> SIGSEGV). (#228)
        try {
            synchronized(talkbackLock) {
                if (!isServiceAlive) return
                if (!isTalkbackPlaying) {
                    ensureTalkbackTrack()
                    if (talkbackTrack == null) return // never mute the mic if speaker initialization failed
                    isTalkbackPlaying = true
                    talkbackIdleFuture = talkbackIdleExecutor.scheduleWithFixedDelay({
                        synchronized(talkbackLock) {
                            if (!talkbackActivity.hasActiveFrames()) stopTalkbackLocked()
                        }
                    }, 1_500L, 1_500L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    refreshForegroundServiceType()
                }
                talkbackActivity.markFrame(source)
                val written = talkbackTrack?.write(pcmData, 0, decoded) ?: -1
                if (written < 0) {
                    stopTalkbackLocked()
                    return
                }
                talkbackActivity.markFrame(source)
            }
        } catch (e: Exception) {
            LogManager.e(LogCategory.AUDIO, "Talkback write failed", e)
            synchronized(talkbackLock) { stopTalkbackLocked() }
        }
    }

    /**
     * Stop talkback playback and restore volume
     */
    private fun stopTalkback() = synchronized(talkbackLock) { stopTalkbackLocked() }

    /** Caller holds talkbackLock: releasing a previous speaker cannot tear down a new parent's audio. */
    private fun stopTalkbackLocked() {
        talkbackActivity.clear()
        talkbackIdleFuture?.cancel(false)
        talkbackIdleFuture = null
        if (!isTalkbackPlaying) return
        try {
            talkbackTrack?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Talkback stop failed", e)
        } finally {
            try { talkbackTrack?.release() } catch (e: Exception) {
                Log.w(TAG, "Talkback release failed", e)
            }
            talkbackTrack = null
        }
        isTalkbackPlaying = false
        getSharedPreferences(TALKBACK_PREFS, MODE_PRIVATE).edit().remove(PREF_PRE_BOOST_MUSIC_VOLUME).apply()
        LogManager.d(LogCategory.AUDIO, "Talkback stopped - resuming nursery microphone")
        refreshForegroundServiceType()
    }

    /**
     * If a previous service instance was killed in the middle of talkback (between
     * boost-volume and restore-volume), [TALKBACK_PREFS]/[PREF_PRE_BOOST_MUSIC_VOLUME]
     * will still hold the pre-boost music volume. Restore it on the next service
     * onCreate so the user doesn't keep boosted music volume across crashes (#128).
     */
    private fun restoreOrphanedTalkbackVolume() {
        val prefs = getSharedPreferences(TALKBACK_PREFS, MODE_PRIVATE)
        if (!prefs.contains(PREF_PRE_BOOST_MUSIC_VOLUME)) return
        val orphanLevel = prefs.getInt(PREF_PRE_BOOST_MUSIC_VOLUME, -1)
        prefs.edit().remove(PREF_PRE_BOOST_MUSIC_VOLUME).apply()
        if (orphanLevel < 0) return
        try {
            val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
            val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            // Only restore downward — if the user already lowered it themselves, don't bump.
            if (current > orphanLevel) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, orphanLevel, 0)
                LogManager.w(
                    LogCategory.AUDIO,
                    "Restored orphaned talkback boost: music volume $current -> $orphanLevel (service died mid-talkback last run)"
                )
            } else {
                LogManager.i(
                    LogCategory.AUDIO,
                    "Cleared orphaned talkback marker (current=$current ≤ saved=$orphanLevel — user adjusted already)"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore orphaned talkback music volume", e)
        }
    }

    private fun buildStatusJson(): JSONObject {
        val json = JSONObject()
        val uptimeSeconds = (System.currentTimeMillis() - serviceStartTime) / 1000
        val isActuallyRunning = cameraStreamer?.isRunning() ?: false
        val hasFrame = cameraStreamer?.latestJpeg() != null
        json.put("uptimeSeconds", uptimeSeconds)
        json.put("connectedParents", audioListenerCount())
        json.put("listeningPort", currentPort)
        json.put("audioTlsPort", tlsAudioListenerPort ?: JSONObject.NULL)
        json.put("webPort", webServerPort)
        json.put("addresses", localAddresses())
        json.put("recording", isRecording)
        json.put("talkbackPlaying", isTalkbackPlaying)
        json.put("cameraEnabled", cameraEnabled)
        json.put("cameraActive", isActuallyRunning)
        json.put("cameraFrameAvailable", hasFrame)
        json.put("nightModeActive", cameraStreamer?.isInNightMode() ?: false)
        json.put("snapshotCadence", snapshotCadenceJson()) // #287
        json.put("soother", sootherStateJson()) // #311
        json.put("soundEvent", soundEventJson()) // #286
        json.put("motionEvent", motionEventJson()) // #266/#343
        json.put("alertConfig", alertConfigJson()) // #291
        json.put("nightLight", nightLightStateJson()) // #312
        json.put("hooks", hooksStateJson()) // #308
        json.put("battery", batteryStateJson()) // #301 (child side)
        json.put("mqtt", mqttIntegration?.stateJson()
            ?: JSONObject().put("enabled", false).put("connected", false))
        json.put("replayAvailableMs", audioReplay.availableMs(android.os.SystemClock.elapsedRealtime(), REPLAY_STALE_MS)) // #284
        json.put("replaySnapshotCount", snapshotReplay.availableCount(android.os.SystemClock.elapsedRealtime(), REPLAY_STALE_MS)) // #337
        json.put("replaySnapshotAvailableMs", snapshotReplay.availableMs(android.os.SystemClock.elapsedRealtime(), REPLAY_STALE_MS)) // #337: duration span, parity with replayAvailableMs
        json.put("pairedDeviceCount", pairingManager.pairedDeviceCount())
        json.put("pairingChallengeActive", pairingManager.hasActiveChallenge())
        json.put("refreshCredentials", sessionAuthority.refreshCredentialCount()) // #348
        return json
    }

    private fun localAddresses(): List<String> {
        val addresses = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            Collections.list(interfaces).forEach { intf ->
                if (!intf.isUp || intf.isLoopback) return@forEach
                Collections.list(intf.inetAddresses).forEach { addr ->
                    val hostAddr = addr.hostAddress
                    if (!addr.isLoopbackAddress && hostAddr != null && !hostAddr.contains(':')) {
                        addresses.add(hostAddr)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to enumerate interfaces", e)
        }
        return addresses
    }

    private fun ensureMonitorThread() = synchronized(monitorThreadLock) {
        var mt = this.monitorThread
        if (mt != null && mt.isAlive) {
            LogManager.d(LogCategory.CONNECTION, "Monitor thread already running")
            return@synchronized
        }

        LogManager.i(LogCategory.CONNECTION, "Starting monitor thread to accept parent connections")

        val currentToken = Any()
        this.connectionToken = currentToken
        mt = Thread {
            var serverSocket: ServerSocket? = null
            try {
                // Retry binding with backoff — port may be in TIME_WAIT after a recent
                // shutdown. Previously a single bind failure killed the accept loop forever.
                var bindAttempt = 0
                while (this.connectionToken == currentToken && !Thread.currentThread().isInterrupted) {
                    try {
                        serverSocket = ServerSocket(this.currentPort)
                        break
                    } catch (e: IOException) {
                        bindAttempt++
                        val delay = (1000L * (1 shl min(bindAttempt - 1, 4))).coerceAtMost(30_000L)
                        LogManager.w(
                            LogCategory.CONNECTION,
                            "ServerSocket bind to port $currentPort failed (attempt $bindAttempt): ${e.message} — retry in ${delay}ms"
                        )
                        // Surface the failure to the user after 3 attempts — by then the
                        // device has been trying to bind for ~7s and the user is wondering
                        // why no parents can connect (#187, ServerSocket failure hidden).
                        if (bindAttempt == 3) {
                            mainHandler.post {
                                if (isServiceAlive) {
                                    surfaceNotificationStatus(
                                        "Monitor unreachable — port $currentPort in use (still retrying)"
                                    )
                                }
                            }
                        }
                        try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
                    }
                }
                if (serverSocket == null) return@Thread
                // Bind succeeded — if we surfaced a "port in use" error earlier, clear it now.
                if (bindAttempt >= 3) {
                    mainHandler.post {
                        if (isServiceAlive) surfaceNotificationStatus(null)
                    }
                }

                this.currentSocket = serverSocket
                val localPort = serverSocket.localPort

                networkMonitor?.logCurrentNetworkState()
                LogManager.i(
                    LogCategory.CONNECTION,
                    "Server socket opened successfully on port $localPort - ready to accept connections"
                )

                // Register the service so that parent devices can locate the child device
                try {
                    registerService(localPort)
                } catch (e: Exception) {
                    // NSD can be temporarily unavailable during a Wi-Fi handoff. Discovery may be
                    // degraded until the heartbeat retries, but the raw listener must stay alive so
                    // saved-address reconnects continue working in the meantime.
                    LogManager.e(LogCategory.DISCOVERY,
                        "Initial NSD registration failed; keeping monitor socket active", e)
                }

                // Accept connections in a loop to support multiple parents
                while (this.connectionToken == currentToken && !Thread.currentThread().isInterrupted) {
                    try {
                        dispatchAudioConnection(serverSocket.accept(), secureTransport = false)
                    } catch (e: IOException) {
                        if (this.connectionToken == currentToken) {
                            Log.e(TAG, "Error accepting connection", e)
                            LogManager.e(LogCategory.CONNECTION, "Error accepting incoming connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (this.connectionToken == currentToken) {
                    Log.e(TAG, "Unexpected monitor thread error", e)
                    LogManager.e(LogCategory.CONNECTION, "Unexpected monitor thread error", e)
                }
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                if (this.currentSocket === serverSocket) this.currentSocket = null
                LogManager.w(LogCategory.CONNECTION, "Server socket closed - no longer accepting connections")
            }
        }
        this.monitorThread = mt
        mt.start()
    }

    /** Configure and dispatch one accepted socket. Both audio ports share the
     * same global/per-IP limiter so opening the TLS listener cannot double the
     * pre-auth resource budget. */
    private fun dispatchAudioConnection(socket: Socket, secureTransport: Boolean) {
        try {
            socket.keepAlive = true
            socket.tcpNoDelay = true
        } catch (e: Exception) {
            LogManager.e(LogCategory.CONNECTION,
                "Socket flag setup failed for ${socket.inetAddress?.hostAddress}", e)
            try { socket.close() } catch (_: Exception) {}
            return
        }
        val remoteAddr = socket.inetAddress.hostAddress
        val ipKey = remoteAddr ?: "unknown"
        if (!connectionLimiter.tryAcquire(ipKey)) {
            LogManager.w(LogCategory.CONNECTION,
                "Connection cap reached (active=${connectionLimiter.activeTotal()}) — rejecting $ipKey")
            try { socket.close() } catch (_: Exception) {}
            return
        }
        Thread {
            try {
                serviceConnection(socket, secureTransport)
            } finally {
                connectionLimiter.release(ipKey)
            }
        }.apply { name = if (secureTransport) "audio-tls-client" else "audio-client" }.start()
    }

    /** Start the second, certificate-authenticated audio listener. The original
     * port remains available only for legacy tokens; newly advertised QR tokens
     * are rejected there by [serviceConnection]. */
    private fun ensureTlsAudioThread() = synchronized(tlsAudioThreadLock) {
        val existing = tlsAudioThread
        if (existing != null && existing.isAlive) return@synchronized
        val factory = tlsIdentity?.newServerSocketFactory() ?: return@synchronized
        val token = Any()
        tlsAudioToken = token
        val thread = Thread {
            var serverSocket: ServerSocket? = null
            try {
                var bindAttempt = 0
                while (tlsAudioToken == token && !Thread.currentThread().isInterrupted) {
                    try {
                        serverSocket = factory.createServerSocket(AUDIO_TLS_PORT)
                        break
                    } catch (e: IOException) {
                        bindAttempt++
                        val delay = (1000L * (1 shl min(bindAttempt - 1, 4))).coerceAtMost(30_000L)
                        LogManager.w(LogCategory.CONNECTION,
                            "TLS audio bind to port $AUDIO_TLS_PORT failed (attempt $bindAttempt): ${e.message} — retry in ${delay}ms")
                        try { Thread.sleep(delay) } catch (_: InterruptedException) { return@Thread }
                    }
                }
                if (serverSocket == null) return@Thread
                tlsAudioSocket = serverSocket
                setTlsAudioListenerPortInternal(serverSocket.localPort)
                LogManager.i(LogCategory.CONNECTION,
                    "Pinned TLS audio listener ready on port ${serverSocket.localPort}")
                // Re-advertise the raw discovery endpoint with the optional `ap`
                // TXT attribute now that the secure listener is genuinely live.
                try { refreshNsdRegistration() } catch (e: Exception) {
                    LogManager.w(LogCategory.DISCOVERY,
                        "TLS audio ready but NSD capability refresh failed: ${e.message}")
                }
                while (tlsAudioToken == token && !Thread.currentThread().isInterrupted) {
                    try {
                        dispatchAudioConnection(serverSocket.accept(), secureTransport = true)
                    } catch (e: IOException) {
                        if (tlsAudioToken == token) {
                            LogManager.e(LogCategory.CONNECTION, "TLS audio accept failed", e)
                        }
                    }
                }
            } catch (e: Exception) {
                if (tlsAudioToken == token) {
                    LogManager.e(LogCategory.CONNECTION, "TLS audio listener failed", e)
                }
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                if (tlsAudioSocket === serverSocket) tlsAudioSocket = null
                if (tlsAudioToken == token) setTlsAudioListenerPortInternal(null)
            }
        }.apply { name = "audio-tls-accept" }
        tlsAudioThread = thread
        thread.start()
    }

    private fun registerService(port: Int) = synchronized(nsdLock) {
        val deviceId = ChildIdentity.getId(this)
        // #209: only the user's explicit custom name (null on the Build.MODEL
        // default), so the device model is never advertised in the TXT record.
        val customName = ChildIdentity.getCustomName(this)
        // Service name encodes the deviceId in a parsable suffix so even older NSD
        // clients that don't read TXT attributes can still identify the child uniquely:
        //   "ChildMonitor on Pixel 3 [a1b2c3d4]"
        // #190-HIGH (mDNS leaks Build.MODEL): use the opaque public name instead of
        // "ChildMonitor on $friendlyName". #209: the TXT 'name' attribute likewise
        // no longer carries the Build.MODEL default — only a user-set custom name —
        // so passive mDNS observers on the LAN can't identify the device model.
        val serviceName = ChildIdentity.getPublicMdnsName(this)
        LogManager.i(
            LogCategory.DISCOVERY,
            "Attempting to register NSD service: $serviceName on port $port"
        )

        val serviceInfo = NsdServiceInfo()
        serviceInfo.serviceName = serviceName
        serviceInfo.serviceType = "_childmonitor._tcp."
        serviceInfo.port = port
        // TXT records — the modern way to identify the service. Parent looks these up
        // via NsdManager.resolveService when discovering by deviceId.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                for ((key, value) in MdnsTxtRecord.attributes(deviceId, customName, tlsAudioListenerPort)) {
                    serviceInfo.setAttribute(key, value)
                }
            } catch (e: Exception) {
                LogManager.w(LogCategory.DISCOVERY, "Failed to set NSD TXT attributes: ${e.message}")
            }
        }
        val listener = object : RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                // Save the service name.  Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                nsdServiceInfo.serviceName.let { actualServiceName ->
                    Log.i(TAG, "Service name: $actualServiceName")
                    LogManager.i(
                        LogCategory.DISCOVERY,
                        "NSD service registered successfully: $actualServiceName (port $port)"
                    )
                    monitorActivity?.let { ma ->
                        ma.runOnUiThread {
                            val statusText = ma.findViewById<TextView>(R.id.textStatus)
                            statusText.setText(R.string.waitingForParent)
                            val serviceText = ma.findViewById<TextView>(R.id.textService)
                            serviceText.text = actualServiceName
                            val portText = ma.findViewById<TextView>(R.id.port)
                            portText.text = getString(R.string.portNumber, port)
                        }
                    }
                }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                LogManager.e(
                    LogCategory.DISCOVERY,
                    "NSD service registration failed with error code: $errorCode — clearing listener so heartbeat can retry"
                )
                // Clear the listener so the heartbeat thread re-registers on its next pass.
                // Without this, registrationListener stays bound to a dead listener and
                // unregisterService throws IllegalArgumentException on next refresh.
                // [#264] Do it under nsdLock and only if we're still the active listener, so
                // this NSD-thread callback can't clobber a listener a concurrent re-register
                // just installed (which would leak that listener — the residual #235 race).
                val self = this
                synchronized(nsdLock) {
                    if (registrationListener === self) registrationListener = null
                }
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                // Service has been unregistered.  This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
                Log.i(TAG, "Unregistering service")
                LogManager.i(LogCategory.DISCOVERY, "NSD service unregistered")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Unregistration failed.  Put debugging code here to determine why.
                Log.e(TAG, "Unregistration failed: $errorCode")
                LogManager.e(
                    LogCategory.DISCOVERY,
                    "NSD service unregistration failed with error code: $errorCode"
                )
            }
        }
        this.registrationListener = listener
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            // onRegistrationFailed clears asynchronous failures, but registerService can also throw
            // synchronously (e.g. NSD unavailable during a handoff). Never leave a dead listener
            // marker that prevents the heartbeat from retrying forever.
            if (registrationListener === listener) registrationListener = null
            throw e
        }
    }

    private fun unregisterService() = synchronized(nsdLock) {
        this.registrationListener?.let {
            this.registrationListener = null
            Log.i(TAG, "Unregistering monitoring service")
            this.nsdManager.unregisterService(it)
        }
        Unit
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            this.notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    // #327: the most recently surfaced notification detail (error banner), or null for the plain
    // "Child Device" notification. Remembered so an FGS type re-promotion rebuilds the notification
    // WITH the active banner instead of silently clearing it.
    @Volatile private var currentNotificationDetail: String? = null

    private fun buildNotification(detail: String? = null): Notification {
        val text: CharSequence = "Child Device"
        val openIntent = Intent(this, MonitorActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.drawable.listening_notification)
                .setOngoing(true)
                .setTicker(text)
                .setContentTitle(text)
                .setContentIntent(pendingIntent)
        if (detail != null) {
            b.setContentText(detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
        }
        return b.build()
    }

    /**
     * Update the foreground-service notification to surface a problem state to the
     * user. Pass null to revert to the plain "Child Device" notification.
     * Used for: audio recorder initialisation failures, repeated ServerSocket bind
     * failures, camera-can't-start, etc. — all of which used to be logged-only and
     * left the user with a UI that said "monitoring" while everything was broken
     * (#187).
     */
    private fun surfaceNotificationStatus(detail: String?) {
        currentNotificationDetail = detail // #327: remember so an FGS type re-promotion preserves it
        try {
            notificationManager.notify(ID, buildNotification(detail))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update FGS notification", e)
        }
    }

    override fun onDestroy() {
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val uptimeSeconds = (System.currentTimeMillis() - serviceStartTime) / 1000

        LogManager.i(LogCategory.SYSTEM, "========================================")
        LogManager.i(LogCategory.SYSTEM, "onDestroy() called at $timestamp")
        LogManager.i(LogCategory.SYSTEM, "MonitorService shutting down | Service ran for ${uptimeSeconds}s")

        // Mark service dead and cancel all main-handler callbacks so deferred
        // audio-recorder restarts, camera restarts, and the initial 500 ms camera
        // boot don't fire after teardown (#184B).
        isServiceAlive = false
        mainHandler.removeCallbacksAndMessages(null)

        // Release wake & WiFi locks first so they don't outlive the service
        releaseChildLocks()

        // Stop heartbeat
        stopHeartbeat()

        // Stop accepting new connections
        this.connectionToken = null
        this.tlsAudioToken = null
        try { tlsAudioSocket?.close() } catch (e: IOException) {
            LogManager.e(LogCategory.CONNECTION, "Failed to close TLS audio listener", e)
        }
        tlsAudioSocket = null
        setTlsAudioListenerPortInternal(null)
        tlsAudioThread?.interrupt()
        tlsAudioThread = null

        // Stop audio recording
        stopAudioRecording()

        // #194-3 CRITICAL: ensure talkback teardown runs in onDestroy. Otherwise
        // STREAM_MUSIC volume can be left pinned at max from the last talkback
        // session if the WebSocket close-frame never arrived (process kill, VPN
        // crash, etc.). Safe to call when no talkback is active — stopTalkback's
        // null-check makes it a no-op.
        try { stopTalkback() } catch (e: Exception) {
            Log.w(TAG, "stopTalkback in onDestroy failed", e)
        }
        talkbackIdleExecutor.shutdownNow()
        try { sootherPlayer.stop() } catch (e: Exception) { // #311
            Log.w(TAG, "soother stop in onDestroy failed", e)
        }
        try { // #312: dismiss the night-light and stop listening for its close signal.
            nightLightOn = false
            applyNightLight()
            unregisterReceiver(nightLightClosedReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "night light teardown in onDestroy failed", e)
        }
        try { eventHooks?.shutdown() } catch (e: Exception) { // #308
            Log.w(TAG, "event hooks shutdown in onDestroy failed", e)
        }
        audioReplay.clear() // #284: don't let this session's audio outlive the service
        snapshotReplay.clear() // #337: same for buffered camera frames

        // Stop web console and camera
        stopWebServer()
        stopCameraStream()
        // #422: shut down the analyzer executor (stop() leaves it reusable). Wrapped like the other
        // onDestroy teardown steps so a failure here can't abort the rest of teardown.
        try { cameraStreamer?.release() } catch (e: Exception) {
            Log.w(TAG, "camera release in onDestroy failed", e)
        }
        cameraStreamer = null

        // Close all parent connections
        synchronized(connectedParents) {
            if (connectedParents.isNotEmpty()) {
                LogManager.i(
                    LogCategory.CONNECTION,
                    "Closing ${connectedParents.size} active parent connection(s)"
                )
            }
            connectedParents.forEach { it.writer.close() }
            connectedParents.clear()
        }

        // Stop monitor thread
        this.monitorThread?.let {
            this.monitorThread = null
            it.interrupt()
            LogManager.i(LogCategory.SYSTEM, "Monitor thread stopped")
        }

        unregisterService()

        // Close server socket
        this.currentSocket?.let {
            try {
                it.close()
                LogManager.i(LogCategory.CONNECTION, "Server socket closed on port $currentPort")
            } catch (e: IOException) {
                Log.e(TAG, "Failed to close active socket on port $currentPort")
                LogManager.e(LogCategory.CONNECTION, "Failed to close server socket", e)
            }
        }
        this.currentSocket = null

        // Stop monitoring
        batteryMonitor?.stopMonitoring()
        batteryMonitor = null
        networkMonitor?.stopMonitoring()
        networkMonitor = null
        try { mqttIntegration?.stop() } catch (e: Exception) {
            Log.w(TAG, "MQTT teardown in onDestroy failed", e)
        }
        mqttIntegration = null
        if (::eventStore.isInitialized) {
            try { eventStore.close() } catch (e: Exception) {
                Log.w(TAG, "event store close in onDestroy failed", e)
            }
        }

        // Cancel the persistent notification.
        this.notificationManager.cancel(R.string.listening)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        LogManager.i(LogCategory.SYSTEM, "MonitorService destroyed - all resources cleaned up")
        LogManager.i(LogCategory.SYSTEM, "========================================")

        // #142: clear the session-open marker LAST so any error logged above still
        // counts as part of "this" session — matches ListenService's ordering and
        // resolves the review-pass-2 MED #2 inconsistency comment.
        LogManager.shutdownGracefully()

        // Tell the user we stopped.
        AppToast.show(this, R.string.stopped, Toast.LENGTH_SHORT)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the child-side monitor running when the user swipes the launcher task
        // away. A baby monitor must keep recording until explicitly stopped from the
        // "Stop Child Session" UI control (or system-level force-stop). The foreground
        // service + START_REDELIVER_INTENT keep it alive without an activity.
        LogManager.i(
            LogCategory.SYSTEM,
            "Task removed by user (app swiped from recents) — keeping child service alive"
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    inner class MonitorBinder : Binder() {
        val service: MonitorService
            get() = this@MonitorService

        fun setCameraEnabled(enabled: Boolean) = this@MonitorService.setCameraEnabled(enabled)
        fun isCameraEnabled(): Boolean = this@MonitorService.isCameraEnabled()
        fun isCameraActive(): Boolean = this@MonitorService.isCameraActive()
        fun setCaptureCalibrationMultiplier(multiplier: Float) =
            this@MonitorService.setCaptureCalibrationMultiplier(multiplier)
        fun getCaptureCalibrationMultiplier(): Float =
            this@MonitorService.getCaptureCalibrationMultiplier()
    }

    companion object {
        const val TAG = "MonitorService"
        internal const val MAX_PARENT_AUDIO_CONNECTIONS = 8
        const val CHANNEL_ID = TAG
        const val ID = 1338

        /**
         * Review-pass-1 #3: process-wide signal for "is the TLS listener up
         * right now?" so ChildPairingActivity advertises `hp=` in the QR only
         * when the parent can actually connect. Updated whenever
         * ensureWebServer brings up the listener or stopWebServer tears it
         * down. Null when TLS is unavailable (keystore not ready, port bind
         * failure, pre-API-23, etc.).
         */
        @Volatile
        var tlsListenerPort: Int? = null
            private set
        @Volatile
        var tlsAudioListenerPort: Int? = null
            private set
        // Pass-2 LOW-1: @Volatile already makes the write atomic; no extra
        // lock needed for a single reference assignment.
        internal fun setTlsListenerPortInternal(port: Int?) { tlsListenerPort = port }
        internal fun setTlsAudioListenerPortInternal(port: Int?) { tlsAudioListenerPort = port }
        private const val AUDIO_TLS_PORT = 10443
        private const val TALKBACK_PREFS = "TalkbackPreferences"
        // #299: restart audio capture if no PCM is read for this long while streaming (the
        // heartbeat watchdog ticks every 60s, so detection lands within ~one tick after this).
        private const val AUDIO_STALE_MS = 45_000L
        // #323: how long stopAudioRecording() waits for the capture thread to finish read()+release
        // after stop(). stop() unblocks read() within one buffer period (~tens of ms at 8 kHz), so
        // this is ~10x margin yet keeps the worst-case main-thread block (onDestroy) well under the
        // ANR budget if a capture read() is ever wedged (it releases on its own exit regardless).
        private const val AUDIO_STOP_JOIN_MS = 750L
        // #300: persists the audio-only (low-power) camera choice across restarts.
        // #366: internal so SecurityStatusActivity reads the SAME key (was a literal copy that
        // could silently desync the privacy display on a rename).
        internal const val MONITOR_PREFS = "MonitorPreferences"
        private const val PREF_CAMERA_ENABLED = "cameraEnabled"
        // #287: persisted snapshot cadence mode + the cap on a single near-live glance.
        private const val PREF_SNAPSHOT_CADENCE = "snapshotCadenceMode"
        private const val SNAPSHOT_GLANCE_MAX_MS = 120_000L
        // #308: persisted event-hook config. #366: internal so the privacy display reads the same keys.
        internal const val PREF_HOOKS_ENABLED = "hooksEnabled"
        internal const val PREF_HOOKS_URL = "hooksWebhookUrl"
        internal const val PREF_HOOKS_LAN_ONLY = "hooksLanOnly" // #332
        // #291: persisted per-trigger alert toggles + quiet-hours config.
        private const val PREF_CRY_ALERTS = "alertCryEnabled"
        private const val PREF_COUGH_ALERTS = "alertCoughEnabled"
        private const val PREF_MOTION_ALERTS = "alertMotionEnabled"
        private const val PREF_QUIET_ENABLED = "alertQuietEnabled"
        private const val PREF_QUIET_START_H = "alertQuietStartHour"
        private const val PREF_QUIET_START_M = "alertQuietStartMinute"
        private const val PREF_QUIET_END_H = "alertQuietEndHour"
        private const val PREF_QUIET_END_M = "alertQuietEndMinute"
        private const val PREF_QUIET_FLOOR = "alertQuietFloor"
        // #284: replay is served only if audio was captured within this window (else it's stale
        // room audio from a past recording, not "what just happened"). Generous enough to cover a
        // disconnect→reconnect (post-alert) cycle.
        private const val REPLAY_STALE_MS = 300_000L
        // #286: /api/status.soundEvent reports the last detected event only if this fresh.
        private const val SOUND_EVENT_STALE_MS = 300_000L
        private const val MOTION_EVENT_STALE_MS = 300_000L
        // #286: minimum gap between sound-alert hook dispatches (anti-spam for burst crying).
        private const val SOUND_ALERT_COOLDOWN_MS = 30_000L
        private const val PREF_PRE_BOOST_MUSIC_VOLUME = "preBoostMusicVolume"
        // CMHELLO handshake (#188 / review #2): the per-socket.read() timeout bounds
        // individual reads; the total wall-clock budget bounds the whole handshake
        // so a slowloris drip can't keep a thread alive indefinitely.
        private const val HELLO_PER_READ_TIMEOUT_MS = 2000
        private const val HELLO_TOTAL_TIMEOUT_MS = 3000L
        private const val HELLO_MAX_BYTES = 4096
    }
}
