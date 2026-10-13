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
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.rochefort.childmonitor.audio.ReplayAudioPolicy
import java.io.IOException
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

class ListenService : Service() {
    private val frequency: Int = AudioCodecDefines.FREQUENCY
    private val channelConfiguration: Int = AudioCodecDefines.CHANNEL_CONFIGURATION_OUT
    private val audioEncoding: Int = AudioCodecDefines.ENCODING

    /**
     * Resolved audio buffer size. `AudioTrack.getMinBufferSize` can transiently return
     * `ERROR` (-1) or `ERROR_BAD_VALUE` (-2) right at process start (observed on a Pixel
     * 6 Pro running v3.2 — AudioFlinger wasn't ready when the class was being constructed),
     * which used to crash AudioTrack.Builder with "Invalid buffer size -1" on every
     * connect and prevent audio from ever playing. Resolve lazily and fall back to a sane
     * default for 8 kHz mono 16-bit PCM (~250 ms of audio).
     */
    private val bufferSize: Int
        get() {
            val reported = AudioTrack.getMinBufferSize(frequency, channelConfiguration, audioEncoding)
            return if (reported > 0) reported else FALLBACK_BUFFER_BYTES
        }
    private val byteBufferSize: Int get() = bufferSize * 2
    private val binder: IBinder = ListenBinder()
    private lateinit var notificationManager: NotificationManager
    private var listenThread: Thread? = null
    val volumeHistory = VolumeHistory(16384)

    // #283: per-minute noise (+ motion, fed by ListenActivity) recorder for the night
    // timeline. Lives on this foreground service so noise sampling continues with the
    // parent screen off. Created eagerly in onCreate() (single instance, no init race).
    @Volatile var nightRecorder: de.rochefort.childmonitor.nighttimeline.NightRecorder? = null
        private set
    var childDeviceName: String? = null
        private set
    // #402 Kids: parent-assigned display label (e.g. "kid1"), preferred over the advertised name in
    // the foreground notification. Re-read from the intent on every start. Absent on non-Kids
    // launches and on watchdog restarts (which re-supply only "name"), so the resolver falls back to
    // the advertised childDeviceName, then deviceId — i.e. the label gracefully degrades, never breaks.
    var kidDisplayName: String? = null
        private set

    // Auto-reconnection and health check fields
    private var address: String? = null
    private var port: Int = 0
    /** Set from the Intent extras; used to look up the bearer token via ParentSessionStore
     *  for the CMHELLO handshake the child requires before serving audio (#188). */
    private var childDeviceId: String? = null

    /** #133: crash-safe FSM persisting each state transition. WatchdogReceiver
     *  (added separately) is the primary recovery mechanism, but the FSM
     *  checkpoints give DebugBundle + audit log a structured record of where
     *  the session was when it died. Lazily-initialised on first onCreate. */
    private lateinit var sessionFsm: de.rochefort.childmonitor.reliability.SessionStateMachine
    @Volatile private var shouldReconnect = true
    @Volatile private var reconnectAttempts = 0
    private val maxReconnectDelay = 60000L // 1 minute max
    private val initialReconnectDelay = 2000L // 2 seconds initial
    private val connectTimeoutMs = 3000 // 3s — fast fail on dead routes (was OS default ~75-130s)
    private val audioReadTimeoutMs = 8_000 // 8s — recover quickly when stream goes silent
    // Health check fires from a main-looper Handler. Doubling the interval from 30s → 60s
    // halves the wake-ups while still being well under noDataThresholdMs (60s) — the worst
    // case detection latency is ~120s instead of ~90s, which is fine for a continuously
    // streaming baby monitor (the audio stalls would be audible immediately anyway).
    private val healthCheckInterval = 60 * 1000L
    private val noDataThresholdMs = 60 * 1000L // 60s — reconnect if no audio received in this window
    // Socket reference for emergency close when interrupted
    @Volatile private var activeSocket: Socket? = null
    // Signal to backoff sleep that network came back — break out early
    private val networkRestoredSignal = AtomicBoolean(false)
    // #1 (Kids reconnect): one-shot NSD discovery the background reconnect loop runs when the saved
    // IP keeps failing and the child's IP changed while the parent was backgrounded. Held so
    // onDestroy can tear it down (unregister the NsdManager listener + release the multicast lock).
    @Volatile private var reconnectDiscovery: NsdDiscoveryHelper? = null
    // Throttle background NSD scans without making them one-shot. A one-shot scan can miss a child
    // that is still offline and then leave this service retrying the stale IP forever after the child
    // returns on a new address. Monotonic time avoids wall-clock jumps changing the cooldown.
    @Volatile private var lastDiscoveryAttemptElapsedMs = 0L
    // #353: last wall-clock time we renewed the bearer after a fast (CMHELLO-rejection) failure,
    // for ReconnectRenewalPolicy's throttle. 0 = never.
    @Volatile private var lastReconnectRenewAtMs = 0L
    private val playbackTargetRms = 6500.0
    private val playbackMinGain = 1.2f
    private val playbackMaxGain = 3.2f
    private val playbackLimiterPeak = 30_500.0f
    @Volatile private var playbackCalibrationMultiplier = AudioCalibrationPrefs.DEFAULT_PLAYBACK_GAIN_MULTIPLIER
    @Volatile private var lastAudioDataTime = 0L
    private val healthCheckHandler = Handler(Looper.getMainLooper())
    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            checkConnectionHealth()
            if (shouldReconnect) {
                healthCheckHandler.postDelayed(this, healthCheckInterval)
            }
        }
    }
    // #341: /api/status carries durable event IDs. Poll independently of the 60-second connection
    // watchdog so a cry reaches the parent notification tray within a few seconds.
    private val statusPollHandler = Handler(Looper.getMainLooper())
    private val statusPollRunnable = object : Runnable {
        override fun run() {
            pollChildStatusAsync()
            if (shouldReconnect) statusPollHandler.postDelayed(this, STATUS_POLL_INTERVAL_MS)
        }
    }

    // #359: the persistent "mute alerts" and "persistent alarm" settings now live ONLY in
    // ListenPreferences and are read fresh at alert time (like AlertEffects), so they can be toggled
    // from either the in-context ListenActivity checkboxes OR the advertised SettingsActivity and
    // take effect without a binder push. [transientMute] is the separate, session-only mute the
    // manual-reconnect loop sets to hush per-attempt alerts; it is NOT persisted.
    @Volatile var transientMute: Boolean = false

    /** Effective mute = the persistent user setting (fresh from prefs) OR the transient reconnect mute. */
    private fun mutedNow(): Boolean =
        transientMute || getSharedPreferences("ListenPreferences", MODE_PRIVATE).getBoolean("muteAlerts", false)

    /** #290: persistent (loud, looping until acknowledged) disconnect alarm — fresh from prefs. */
    private fun persistentAlarmNow(): Boolean =
        getSharedPreferences("ListenPreferences", MODE_PRIVATE).getBoolean("persistentAlarm", false)

    private val alarm by lazy { de.rochefort.childmonitor.alarm.Alarm(this) }
    // #289: high-priority, DnD-piercing "child disconnected" notification.
    private val connectionAlert by lazy { de.rochefort.childmonitor.alarm.ConnectionAlertNotifier(this) }
    // #303: deaf/HoH non-audio alert — sustained vibration + torch strobe (+ FSI screen flash).
    private val highVisibilityAlerter by lazy { de.rochefort.childmonitor.alarm.HighVisibilityAlerter(this) }
    // #292/#187: one-shot vibration waveform (ms on/off, no repeat) for the vibration-only cue
    // and the MediaPlayer-failure fallback.
    private val ALERT_VIBRATION_PATTERN = longArrayOf(0, 400, 200, 400, 200, 400)
    // #301: surface the child (camera) device's battery and warn (normal priority) when it's low,
    // polled from /api/status on the health-check tick.
    private val batteryAlert = de.rochefort.childmonitor.alarm.ChildBatteryAlertState()
    private val batteryNotifier by lazy { de.rochefort.childmonitor.alarm.ChildBatteryNotifier(this) }
    private val eventNotifier by lazy { de.rochefort.childmonitor.events.EventNotifier(this) }
    @Volatile var childBatteryPct: Int = -1
    @Volatile var childCharging: Boolean = false
    @Volatile var repairRequired: Boolean = false
        private set
    @Volatile private var lastStreamAuthRejected = false
    private val statusPollInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val statusPollExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ListenService-status").apply { isDaemon = true }
    }

    // Network monitoring for diagnostics
    private var networkMonitor: NetworkStateMonitor? = null
    private var batteryMonitor: BatteryStateMonitor? = null

    // Audio streaming metrics
    private var totalBytesReceived: Long = 0
    private var audioPacketsReceived: Long = 0
    private var connectionStartTime: Long = 0
    private var lastMetricsLogTime: Long = 0

    // Connection stability metrics
    private var totalConnections: Int = 0
    private var totalDisconnections: Int = 0
    private var totalConnectionDuration: Long = 0  // seconds
    private var longestConnectionDuration: Long = 0  // seconds
    private val recentDisconnectReasons = mutableListOf<String>()

    // Service lifecycle and heartbeat tracking
    private var serviceStartTime: Long = 0
    private var lastHeartbeatTime: Long = 0
    private var heartbeatThread: Thread? = null
    private var isHeartbeatActive = false

    // Audio focus handling
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var audioFocusLostTime: Long = 0

    // Track if audio is currently playing (for recovery)
    @Volatile
    var isAudioPlaying = false
        private set

    // Mute playback during talkback to prevent audio feedback loop
    @Volatile
    var playbackMuted = false

    // #338: a replay clip ("Replay last 30s") is currently playing. While true the live stream's
    // AudioTrack writes are suppressed (same mechanism as [playbackMuted]) so the replay and the
    // live audio don't overlap; the socket keeps draining so the connection stays alive.
    @Volatile
    var replayActive = false
        private set
    // Mutated from the main thread (onDestroy→stopReplay), the caller's worker (playReplay), and the
    // replay thread itself (finally) — must be @Volatile, and stopReplay() joins to make cancel-then-
    // restart deterministic so two replay AudioTracks can never play at once.
    @Volatile
    private var replayThread: Thread? = null

    // Connection quality indicator for parent activity to check
    @Volatile
    var connectionQuality = ConnectionQuality.UNKNOWN
        private set

    // Rolling packet timing metrics used by adaptive video policy on parent side.
    @Volatile
    var packetDelayMs = 0
        private set

    @Volatile
    var packetJitterMs = 0
        private set

    enum class ConnectionQuality {
        UNKNOWN, EXCELLENT, GOOD, FAIR, POOR
    }

    // Audio focus listener - baby monitor keeps playing regardless of focus state
    // We never pause or stop audio; focus is only used to mix nicely with other apps
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                LogManager.i(LogCategory.AUDIO, "Audio focus GAINED")
                hasAudioFocus = true
                if (audioFocusLostTime > 0) {
                    val lostDuration = System.currentTimeMillis() - audioFocusLostTime
                    LogManager.i(LogCategory.AUDIO, "Recovered audio focus after ${lostDuration}ms")
                    audioFocusLostTime = 0
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took exclusive focus - we keep playing anyway
                // Baby monitor audio is safety-critical and must not stop
                LogManager.i(LogCategory.AUDIO, "Audio focus LOST permanently - continuing playback (safety critical)")
                hasAudioFocus = false
                audioFocusLostTime = System.currentTimeMillis()
                // Do NOT re-request focus - let other apps play alongside us
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                LogManager.i(LogCategory.AUDIO, "Audio focus LOST transiently - continuing playback")
                hasAudioFocus = false
                audioFocusLostTime = System.currentTimeMillis()
                // Keep playing - baby monitor doesn't pause
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Keep playing at full volume - baby monitor audio is critical
                LogManager.i(LogCategory.AUDIO, "Audio focus: can duck - continuing at full volume for safety")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        // #283: create the night-timeline recorder once, eagerly, on the main thread so the
        // audio thread and onDestroy().flush() can never race a half-built instance.
        nightRecorder = de.rochefort.childmonitor.nighttimeline.NightRecorder(applicationContext)

        serviceStartTime = System.currentTimeMillis()
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(serviceStartTime)

        // Initialize diagnostic logging
        LogManager.initialize(applicationContext, LogManager.DeviceType.PARENT)
        LogManager.i(LogCategory.SYSTEM, "========================================")
        LogManager.i(LogCategory.SYSTEM, "ListenService onCreate() called at $timestamp")
        LogManager.i(LogCategory.SYSTEM, "Service instance created - initializing parent device")

        // Start network monitoring + wire restore/IP-change signals into the reconnect loop.
        // NetworkStateMonitor suppresses only networks that existed at registration time, so if
        // this service starts offline its first onAvailable is correctly treated as restoration.
        networkMonitor = NetworkStateMonitor(applicationContext)
        networkMonitor?.onNetworkAvailable = { onNetworkRestored() }
        // DHCP renewal / AP roam can replace this parent's address without creating a new Network.
        // Wake the stale-IP reconnect loop in that case too; onAvailable alone does not fire.
        networkMonitor?.onIpAddressChanged = { onNetworkRestored() }
        networkMonitor?.startMonitoring()

        // Start battery/power state monitoring to detect Doze mode
        batteryMonitor = BatteryStateMonitor(applicationContext)
        batteryMonitor?.startMonitoring()
        playbackCalibrationMultiplier = AudioCalibrationPrefs.getPlaybackGainMultiplier(this)

        this.notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Initialize audio manager for focus handling
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // No manual PARTIAL_WAKE_LOCK / WIFI_MODE_FULL_HIGH_PERF here on purpose.
        //  - AudioFlinger acquires the "AudioMix" wake lock automatically whenever AudioTrack
        //    is playing, which keeps the CPU awake for the duration of audio output. Our
        //    previous explicit ChildMonitor:AudioWakeLock was duplicating that work — the
        //    bugreport showed AudioMix at 25m32.809s and the manual lock at 25m32.994s
        //    (185 ms apart) over the same session, so it was pure double-attribution.
        //  - WIFI_MODE_FULL_HIGH_PERF has been effectively a no-op since API 25 (2016) for
        //    "keep WiFi awake" purposes; modern Android keeps Wi-Fi associated for the life
        //    of an active TCP socket on a battery-whitelisted app. The previous lock added
        //    radio "high perf" mode (constant beacon listening) without buying any
        //    reliability — the bugreport showed only 2m05s of "Full Wifi Lock" time across
        //    a 54-minute streaming session.
        // Net effect: ~25 min/session of partial-wake-lock attribution removed, no functional
        // change to audio reliability.

        sessionFsm = de.rochefort.childmonitor.reliability.SessionStateMachine(
            applicationContext, name = "parent-session"
        )
        // Read last-session checkpoint for diagnostics. We don't auto-resume here
        // — WatchdogReceiver already does that via ParentSession/sessionActive
        // (added in #7f9faad). FSM is purely observability + future-proofing.
        val priorCheckpoint = de.rochefort.childmonitor.reliability.SessionStateMachine.lastCheckpoint(
            applicationContext, "parent-session"
        )
        if (priorCheckpoint != null) {
            LogManager.i(LogCategory.SYSTEM,
                "Prior session checkpoint: $priorCheckpoint")
        }
        sessionFsm.transition(
            de.rochefort.childmonitor.reliability.SessionState.STARTING,
            mapOf("source" to "onCreate")
        )

        LogManager.i(LogCategory.SYSTEM, "Service initialization complete — audio focus + audio-system wake lock will be acquired on playback start")
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setWillPauseWhenDucked(false)  // Don't pause - baby monitor is critical
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            val result = am.requestAudioFocus(audioFocusRequest!!)
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (hasAudioFocus) {
                LogManager.i(LogCategory.AUDIO, "Audio focus requested and granted")
            } else {
                LogManager.w(LogCategory.AUDIO, "Audio focus request failed or delayed")
            }
            hasAudioFocus
        } else {
            @Suppress("DEPRECATION")
            val result = am.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
            hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            hasAudioFocus
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
        LogManager.i(LogCategory.AUDIO, "Audio focus abandoned")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // #194-15: Android can redeliver a null intent for sticky/redeliver services
        // after a process kill — the non-nullable param was a latent NPE. We can
        // still keep running (the field-cached address/port/childDeviceId let the
        // reconnect loop continue), but we obviously can't re-arm the watchdog
        // with new params or call startForeground with a new name.
        if (intent == null) {
            LogManager.w(LogCategory.SYSTEM,
                "onStartCommand: intent=null (system redelivery after process kill) — recovering from field-cached target ${this.address}:${this.port}")
            // Review-pass-6: previously this branch returned without invoking
            // the connect loop or rearming the watchdog, leaving a zombie
            // service holding the mediaPlayback foreground type with no audio
            // and no recovery. Rebuild the FGS notification, restart the
            // connect loop with field-cached params, and rearm the watchdog.
            val addr = this.address
            val p = this.port
            if (!addr.isNullOrBlank() && p > 0) {
                createNotificationChannel()
                val n = buildNotification(KidDisplayName.resolve(kidDisplayName, childDeviceName, this.childDeviceId))
                val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
                ServiceCompat.startForeground(this, ID, n, fgType)
                shouldReconnect = true
                requestAudioFocus()
                // Review-pass-7: the normal-start path also kicks off the
                // health-check timer + heartbeat thread; without them the
                // recovered service would run with no stalled-stream detection
                // and no diagnostic heartbeat — a "partial zombie" mirroring
                // the failure mode the redelivery branch was meant to fix.
                armHealthCheck()
                startHeartbeat()
                WatchdogReceiver.startSession(applicationContext, addr, p, childDeviceName, childDeviceId)
                doListen(addr, p)
            } else {
                LogManager.w(LogCategory.SYSTEM, "onStartCommand redelivery with no cached target — self-stopping")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            return START_REDELIVER_INTENT
        }
        Log.i(TAG, "Received start id $startId: flags=$flags intent=$intent")
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(System.currentTimeMillis())
        // Decode flags for the diagnostic log so we can tell a redelivery from a real start (#141).
        val flagDesc = buildString {
            if (flags and START_FLAG_REDELIVERY != 0) append(" REDELIVERY")
            if (flags and START_FLAG_RETRY != 0) append(" RETRY")
            if (isBlank()) append(" NORMAL")
        }
        val hasExtras = intent.extras != null
        LogManager.i(
            LogCategory.SYSTEM,
            "onStartCommand() called at $timestamp | startId: $startId | flags:${flagDesc.trim()} | hasExtras: $hasExtras"
        )

        // Detect the unhealthy "onCreate happened ages ago but onStartCommand only just fired"
        // case we observed in the phone log (97-minute gap, #141). A gap > 15 min means
        // something very wrong: the system kept the service object alive but didn't deliver
        // the original intent until much later. Refuse to continue the stale session.
        val timeSinceCreation = System.currentTimeMillis() - serviceStartTime
        if (timeSinceCreation > STARTCMD_HARD_GAP_MS) {
            LogManager.e(
                LogCategory.SYSTEM,
                "REFUSING stale onStartCommand: ${timeSinceCreation}ms since onCreate() exceeds " +
                    "${STARTCMD_HARD_GAP_MS}ms hard cap — likely a delayed redelivery. Self-stopping " +
                    "so a fresh service can be started with current state."
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (timeSinceCreation > STARTCMD_SOFT_GAP_MS) {
            LogManager.w(
                LogCategory.SYSTEM,
                "Gap detected: ${timeSinceCreation}ms between onCreate() and onStartCommand()"
            )
        }

        // Display a notification about us starting.  We put an icon in the status bar.
        createNotificationChannel()
        intent.extras?.let {
            val name = it.getString("name")
            childDeviceName = name
            kidDisplayName = it.getString(ListenActivity.EXTRA_KID_DISPLAY_NAME)
            val n = buildNotification(KidDisplayName.resolve(kidDisplayName, name, it.getString("deviceId")))
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0 // Keep the linter happy
            ServiceCompat.startForeground(this, ID, n, foregroundServiceType)

            // Store connection parameters for reconnection
            this.address = it.getString("address")
            this.port = it.getInt("port")
            this.childDeviceId = it.getString("deviceId")
            // A fresh QR pairing stores a new bearer before starting us. Clear a prior in-process
            // repair latch so the normal foreground notification and UI can recover immediately.
            if (ParentSessionStore.get(applicationContext, this.childDeviceId) != null) {
                this.repairRequired = false
            }
            this.shouldReconnect = true
            this.reconnectAttempts = 0
            this.lastDiscoveryAttemptElapsedMs = 0L

            // Persist session params + arm the watchdog. If the OS later kills this
            // process (REMOVE_TASK, SIGKILL under memory pressure, etc.), the
            // WatchdogReceiver alarm will resurrect ListenService within ~90s instead
            // of leaving the parent silently un-monitored until the user notices.
            val addr = this.address
            if (!addr.isNullOrBlank() && this.port > 0) {
                WatchdogReceiver.startSession(applicationContext, addr, this.port, name, childDeviceId)
            }

            LogManager.i(
                LogCategory.SYSTEM,
                "Starting parent service to connect to child device: $name"
            )
            LogManager.logConnection(
                "Configured connection target",
                ip = this.address,
                port = this.port,
                details = "Child device: $name, Health check interval: ${healthCheckInterval/1000}s"
            )
            networkMonitor?.logCurrentNetworkState()

            // Request audio focus for playback. (No manual wake lock — AudioFlinger's
            // AudioMix wake lock is acquired automatically when AudioTrack.play() runs
            // and held for the lifetime of the playback session.)
            requestAudioFocus()

            // Start health check
            armHealthCheck()
            armStatusPoll()

            // Start heartbeat
            startHeartbeat()

            doListen(this.address, this.port)
        }
        return START_REDELIVER_INTENT
    }

    private fun startHeartbeat() {
        if (isHeartbeatActive) return

        isHeartbeatActive = true
        lastHeartbeatTime = System.currentTimeMillis()

        heartbeatThread = Thread {
            LogManager.i(LogCategory.HEALTH, "Heartbeat thread started - will log every 10 minutes")

            while (isHeartbeatActive && !Thread.currentThread().isInterrupted) {
                try {
                    // 10 min — purely a diagnostic log tick, not a functional heartbeat.
                    // Doubled from 5 min to halve wake-ups during overnight streaming.
                    Thread.sleep(10 * 60 * 1000)

                    val now = System.currentTimeMillis()
                    val timeSinceLastHeartbeat = now - lastHeartbeatTime
                    val uptimeSeconds = (now - serviceStartTime) / 1000

                    // Log heartbeat with service status
                    val details = buildString {
                        append("Service uptime: ${uptimeSeconds}s")
                        append(" | Connected: ${listenThread?.isAlive == true}")
                        append(" | Child: ${childDeviceName ?: "Unknown"}")
                        append(" | Total connections: $totalConnections")

                        // Warn if there's a gap (should be ~5 min, but allow some tolerance)
                        if (timeSinceLastHeartbeat > 6 * 60 * 1000) {
                            append(" | WARNING: Gap of ${timeSinceLastHeartbeat / 1000}s detected")
                        }
                    }

                    LogManager.i(LogCategory.HEALTH, "Heartbeat | $details")

                    // Also log current battery/power state periodically
                    batteryMonitor?.logCurrentPowerState()

                    lastHeartbeatTime = now
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

    private fun stopHeartbeat() {
        isHeartbeatActive = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    override fun onDestroy() {
        // #338: stop any in-progress replay clip so its playback thread + AudioTrack don't outlive us.
        try { stopReplay() } catch (_: Exception) {}
        // #283: persist the in-progress night-timeline minute + stop its IO thread.
        try { nightRecorder?.shutdown() } catch (_: Exception) {}
        // #290: silence the persistent alarm on teardown.
        try { alarm.stop() } catch (_: Exception) {}
        // #289: clear the disconnect notification on teardown.
        try { connectionAlert.reset() } catch (_: Exception) {}
        // #303: stop the deaf/HoH sustained vibration + torch + flash on teardown.
        try { highVisibilityAlerter.stop() } catch (_: Exception) {}
        // #301/#341: clear status-derived notifications + stop the status-poll executor.
        try { batteryNotifier.clear() } catch (_: Exception) {}
        try { statusPollExecutor.shutdownNow() } catch (_: Exception) {}
        // #133: mark the session DRAINING → CLOSED on graceful teardown. If the
        // process is killed without onDestroy running, the checkpoint remains
        // at ACTIVE/CONNECTED and the next startup sees "session died in flight"
        // — already used by Watchdog for recovery (#7f9faad).
        if (::sessionFsm.isInitialized) {
            sessionFsm.transition(
                de.rochefort.childmonitor.reliability.SessionState.DRAINING,
                mapOf("source" to "onDestroy")
            )
        }
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
            .format(System.currentTimeMillis())
        val uptimeSeconds = (System.currentTimeMillis() - serviceStartTime) / 1000

        LogManager.i(LogCategory.SYSTEM, "========================================")
        LogManager.i(LogCategory.SYSTEM, "onDestroy() called at $timestamp")
        LogManager.i(LogCategory.SYSTEM, "ListenService shutting down | Service ran for ${uptimeSeconds}s")

        // Log final connection stability statistics
        if (totalConnections > 0) {
            val avgDuration = totalConnectionDuration / totalConnections
            LogManager.i(
                LogCategory.SYSTEM,
                "Connection Stability Report | Total connections: $totalConnections, Disconnects: $totalDisconnections, Avg duration: ${avgDuration}s, Longest: ${longestConnectionDuration}s"
            )
            if (recentDisconnectReasons.isNotEmpty()) {
                LogManager.i(
                    LogCategory.SYSTEM,
                    "Recent disconnect reasons: ${recentDisconnectReasons.joinToString(" | ")}"
                )
            }
        }

        // Stop heartbeat
        stopHeartbeat()

        // Release audio focus (the AudioMix wake lock is released automatically by
        // AudioFlinger when AudioTrack is released in streamAudio's finally block).
        abandonAudioFocus()

        // Stop reconnection attempts and health checks
        this.shouldReconnect = false
        healthCheckHandler.removeCallbacks(healthCheckRunnable)
        statusPollHandler.removeCallbacks(statusPollRunnable)
        LogManager.i(LogCategory.HEALTH, "Health checks and auto-reconnect disabled")

        // Close any active socket BEFORE interrupting — interrupt alone cannot unblock
        // socket.connect() or inputStream.read(); only close() can. Without this,
        // the listen thread can linger up to 8s after onDestroy, holding audio focus
        // and the audio-system wake lock that we just released.
        try { activeSocket?.close() } catch (_: Exception) {}
        activeSocket = null
        this.listenThread?.interrupt()
        this.listenThread = null

        // #1 (Kids reconnect): tear down any in-flight reconnect discovery (unregisters the
        // NsdManager listener + releases the multicast lock). Idempotent with the method's finally.
        try { reconnectDiscovery?.stopDiscovery() } catch (_: Exception) {}
        reconnectDiscovery = null

        // Stop monitoring
        batteryMonitor?.stopMonitoring()
        batteryMonitor = null
        networkMonitor?.stopMonitoring()
        networkMonitor = null

        // Cancel the persistent notification.
        notificationManager.cancel(R.string.listening)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        LogManager.i(LogCategory.SYSTEM, "ListenService destroyed - all resources cleaned up")
        LogManager.i(LogCategory.SYSTEM, "========================================")
        // #133: transition DRAINING → CLOSED. Next service onCreate will see
        // CLOSED as the prior checkpoint and know this was a graceful exit.
        if (::sessionFsm.isInitialized) {
            sessionFsm.transition(
                de.rochefort.childmonitor.reliability.SessionState.CLOSED,
                mapOf("source" to "onDestroy")
            )
        }
        // #142: clear the session-open marker so the next session start doesn't
        // false-positive as a crash recovery. Done last so any error logged above
        // still counts as part of "this" session.
        LogManager.shutdownGracefully()

        // Tell the user we stopped.
        AppToast.show(this, R.string.stopped, Toast.LENGTH_SHORT)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally do NOT stop the service when the user swipes the app away from
        // recents. A parent baby-monitor session must keep listening for audio until the
        // user explicitly taps "Stop Parent Session" (or force-stops the app from system
        // settings). Foreground service + START_REDELIVER_INTENT keep the service alive
        // even after the launcher task is removed.
        LogManager.i(
            LogCategory.SYSTEM,
            "Task removed by user (app swiped from recents) — keeping foreground service alive"
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    private fun buildNotification(name: String?): Notification {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        val text = getText(R.string.listening)

        // The PendingIntent to launch our activity if the user selects this notification
        val contentIntent = PendingIntent.getActivity(this, 0,
                Intent(this, ListenActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        // Set the info for the views that show in the notification panel.
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
        b.setSmallIcon(R.drawable.listening_notification) // the status icon
                .setOngoing(true)
                .setTicker(text) // the status text
                .setContentTitle(text) // the label of the entry
                .setContentText(name) // the contents of the entry
                .setContentIntent(contentIntent)
        return b.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(serviceChannel)
        }
    }

    inner class ListenBinder : Binder() {
        val service: ListenService
            get() = this@ListenService
    }

    val currentTargetAddress: String?
        get() = address

    val currentTargetPort: Int
        get() = port

    /**
     * Live target-swap during reconnection — used by ListenActivity.reconnectToLast
     * to point an already-running service at a different child IP without the
     * stopService → startService churn that previously fired 5 full lifecycles in
     * 44 s (#138). The reconnect loop closes the current socket so the next
     * iteration of doListen connects to the new address using its existing
     * wake-lock + audio-focus + heartbeat state.
     */
    @Synchronized
    fun retargetForReconnect(newAddress: String, newPort: Int, newName: String?, newDeviceId: String?) {
        if (newAddress.isBlank() || newPort <= 0) return
        LogManager.i(
            LogCategory.CONNECTION,
            "Retargeting parent session: ${this.address}:${this.port} → $newAddress:$newPort " +
                "(name=${newName ?: "?"}, deviceId=${newDeviceId ?: "?"})"
        )
        this.address = newAddress
        this.port = newPort
        if (!newName.isNullOrBlank()) this.childDeviceName = newName
        if (!newDeviceId.isNullOrBlank()) this.childDeviceId = newDeviceId
        // #402 Kids: deliberately DON'T touch kidDisplayName or rebuild the FGS notification here.
        // The reconnect caller passes newName=<IP>, so childDeviceName now holds the raw address;
        // rebuilding would regress a non-Kids session's notification to that IP. Leaving the
        // start-time notification in place keeps showing the original label — the kid name for a
        // Kids session, the advertised name otherwise — across the IP swap. (kidDisplayName is an
        // untouched field, so it also survives for any future rebuild.)
        // Persist updated target so the watchdog can also follow if the process is killed.
        WatchdogReceiver.startSession(
            applicationContext, newAddress, newPort, newName ?: this.childDeviceName, childDeviceId
        )
        // Close the current socket so the reconnect loop wakes up immediately and
        // connects to the new target on its next iteration. Deliberately do NOT
        // reset reconnectAttempts here: if a misbehaving discovery loop retargets
        // every few seconds, we want the exponential backoff to keep growing, not
        // be zeroed on every swap.
        try { activeSocket?.close() } catch (_: Exception) {}
    }

    /** Keep the durable kid record + legacy quick-reconnect list aligned with a background NSD swap. */
    private fun persistRediscoveredTarget(newAddress: String, newPort: Int, newName: String?) {
        val endpoint = NetworkEndpoint.create(newAddress, newPort) ?: return
        val prefs = getSharedPreferences("ListenPreferences", MODE_PRIVATE)
        val existingHistory = prefs.getString("ipHistory", "").orEmpty().split(",")
            .mapNotNull { NetworkEndpoint.parse(it, defaultPort = 10000)?.encoded() }
        val updatedHistory = (listOf(endpoint.encoded()) + existingHistory)
            .distinct()
            .take(10)
        prefs.edit().putString("ipHistory", updatedHistory.joinToString(",")).apply()

        val id = childDeviceId?.takeIf { it.isNotBlank() } ?: return
        val existing = PairedChildrenStore.findByDeviceId(this, id)
        PairedChildrenStore.upsert(
            this,
            PairedChild(
                deviceId = id,
                name = newName?.takeIf { it.isNotBlank() } ?: existing?.name ?: id,
                lastKnownIp = newAddress,
                lastKnownPort = newPort,
                lastSeenMs = System.currentTimeMillis(),
            )
        )
    }

    /**
     * #1 (Kids reconnect): run ONE bounded NSD discovery pass on the current background reconnect
     * thread and, if a service whose deviceId matches [childDeviceId] resolves, live-retarget the
     * session to its (possibly new) address. This is what lets a BACKGROUNDED parent reconnect after
     * the child's IP changes — the doListen loop alone only ever retries the same (now-dead) IP, and
     * the activity-side discovery is gone once the UI is destroyed.
     *
     * NsdDiscoveryHelper is single-threaded (its callbacks + self-scheduled timeout run on the MAIN
     * looper), so we drive its start/stop ON THE MAIN LOOPER (healthCheckHandler) and only WAIT here
     * on the background thread, reading the result the main-thread callback publishes into an
     * AtomicReference. The wait is polled in slices so a network-restore or Stop aborts it instead of
     * blocking ~17s. Discovery is always stopped (here in finally, and again in onDestroy).
     *
     * @return true iff a DIFFERENT address resolved and [retargetForReconnect] was invoked.
     */
    private fun discoverByDeviceIdAndRetarget(timeoutMs: Long = 15_000L): Boolean {
        val target = childDeviceId
        if (target.isNullOrBlank()) return false
        if (networkRestoredSignal.get()) return false   // network already back — let the normal early-wake retry the saved IP
        val failingAddress = this.address                // skip a pointless retarget to the same (stale) IP
        val failingPort = this.port
        // Permanent kids require an EXACT deviceId match; mirrors ListenActivity.isPermanentTarget().
        val permanent = runCatching {
            PairedChildrenStore.findByDeviceId(this, target)?.isPermanent == true
        }.getOrDefault(false)

        val latch = java.util.concurrent.CountDownLatch(1)
        val match = java.util.concurrent.atomic.AtomicReference<NsdDiscoveryHelper.DiscoveredDevice?>(null)
        val helper = NsdDiscoveryHelper(this).apply {
            onDeviceFound = { device ->            // dispatched on the main thread
                if (KidReconnectPolicy.acceptsDiscovered(target, device.deviceId, permanent)) {
                    if (match.compareAndSet(null, device)) latch.countDown()
                }
            }
            onDiscoveryComplete = { _ -> latch.countDown() }
            onDiscoveryFailed = { err ->
                LogManager.w(LogCategory.DISCOVERY, "Reconnect discovery failed: $err")
                latch.countDown()
            }
        }
        reconnectDiscovery = helper
        // NsdDiscoveryHelper must only be touched from one thread; drive its lifecycle on the main
        // looper while this background thread just waits. (onDestroy also stops it from main.)
        healthCheckHandler.post { helper.startDiscovery(timeoutMs = timeoutMs) }
        return try {
            LogManager.i(LogCategory.DISCOVERY, "Saved IP failing — discovering child by deviceId=$target")
            val deadline = System.currentTimeMillis() + timeoutMs + 2_000L
            var resolved = false
            while (!resolved && System.currentTimeMillis() < deadline) {
                if (!shouldReconnect || Thread.currentThread().isInterrupted) return false
                if (networkRestoredSignal.get()) return false   // network came back mid-scan — bail to the saved-IP retry
                resolved = latch.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            val device = match.get()
            if (device != null && !device.address.isNullOrBlank() && device.port > 0 &&
                (device.address != failingAddress || device.port != failingPort)) {
                LogManager.i(
                    LogCategory.DISCOVERY,
                    "Discovery matched child id=${device.deviceId} at " +
                        "${LogRedaction.redactIp(device.address)}:${device.port} — retargeting"
                )
                // retargetForReconnect is @Synchronized and closes activeSocket, so the next loop
                // iteration (which re-reads address/port) connects to the rediscovered IP.
                retargetForReconnect(device.address, device.port, device.name, device.deviceId)
                // This result was identity-verified by deviceId, so it is safe to make durable.
                // Generic activity-driven retarget attempts are NOT persisted until they connect.
                persistRediscoveredTarget(device.address, device.port, device.name)
                true
            } else {
                false
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            // Stop on the main looper too (idempotent with onDestroy's stop), then drop the ref.
            healthCheckHandler.post { try { helper.stopDiscovery() } catch (_: Exception) {} }
            reconnectDiscovery = null
        }
    }

    fun setPlaybackCalibrationMultiplier(multiplier: Float) {
        val safe = AudioCalibrationPrefs.clampPlayback(multiplier)
        playbackCalibrationMultiplier = safe
        AudioCalibrationPrefs.setPlaybackGainMultiplier(this, safe)
        LogManager.i(LogCategory.AUDIO, "Playback calibration updated to ${"%.2f".format(safe)}x")
    }

    fun getPlaybackCalibrationMultiplier(): Float = playbackCalibrationMultiplier

    var onError: (() -> Unit)? = null
    var onUpdate: (() -> Unit)? = null
    var onConnected: (() -> Unit)? = null
    var onRepairRequired: (() -> Unit)? = null

    private fun surfaceRepairRequired() {
        if (repairRequired) return
        repairRequired = true
        LogManager.w(LogCategory.CONNECTION,
            "Child session credentials were revoked — user must re-pair by QR")
        healthCheckHandler.post {
            if (!shouldReconnect) return@post
            notificationManager.notify(
                ID,
                buildNotification(getString(R.string.session_repair_required_short))
            )
            onRepairRequired?.invoke()
        }
    }

    private fun doListen(initialAddress: String?, initialPort: Int) {
        // #133 (review-pass-7): the FSM hop into CONNECTING is illegal from
        // ACTIVE/CONNECTED (only DRAINING/FAILED/CLOSED allowed). doListen IS
        // reachable from those states — a second normal startService after an
        // already-ACTIVE session does NOT re-run onCreate, so sessionFsm could
        // still be at ACTIVE. Force-walk back through FAILED → STARTING so the
        // CONNECTING transition below is legal. If we're already at IDLE/STARTING
        // (fresh service) the FAILED hop is itself illegal and silently dropped
        // — that's OK because STARTING → CONNECTING is then directly legal.
        if (::sessionFsm.isInitialized) {
            val cur = sessionFsm.current()
            if (cur == de.rochefort.childmonitor.reliability.SessionState.ACTIVE ||
                cur == de.rochefort.childmonitor.reliability.SessionState.CONNECTED ||
                cur == de.rochefort.childmonitor.reliability.SessionState.CONNECTING) {
                sessionFsm.transition(
                    de.rochefort.childmonitor.reliability.SessionState.FAILED,
                    mapOf("reason" to "restart_in_place")
                )
            }
            // FAILED → STARTING → CONNECTING is the legal recovery walk.
            sessionFsm.transition(
                de.rochefort.childmonitor.reliability.SessionState.STARTING,
                mapOf("source" to "doListen")
            )
            sessionFsm.transition(
                de.rochefort.childmonitor.reliability.SessionState.CONNECTING,
                mapOf("address" to (initialAddress ?: ""), "port" to initialPort)
            )
        }
        // #194-11: stop any existing listen thread AND wait briefly for it to
        // exit. Previously we only called interrupt() and immediately started
        // the new thread — on rapid reconnect storms (e.g., the activity's
        // tryNextIpInHistory loop pre-retargetForReconnect), two listen threads
        // could briefly own an AudioTrack each, and the user heard double-voice
        // overlap. Wait up to 500 ms; in practice the read() unblocks in <10 ms
        // once we close the socket below.
        this.listenThread?.let { old ->
            try { activeSocket?.close() } catch (_: Exception) {}
            old.interrupt()
            // Bounded to 100 ms because doListen is called from the main thread
            // (Service.onStartCommand). The typical path exits in <10 ms once the
            // socket is closed; if the old thread is stuck on a half-open TCP
            // socket waiting for FIN we accept the brief overlap rather than
            // ANR-jank the main thread. The CMHELLO handshake also handles the
            // duplicate-connection case gracefully (the child only feeds the
            // first authenticated parent).
            try { old.join(100) } catch (_: InterruptedException) {}
            if (old.isAlive) {
                LogManager.w(LogCategory.CONNECTION,
                    "Previous listen thread did not exit within 100ms — continuing anyway, may briefly overlap")
            }
        }

        LogManager.i(LogCategory.CONNECTION, "Starting connection loop for ${initialAddress?.let { LogRedaction.redactIp(it) } ?: initialAddress}:$initialPort")

        val lt = Thread {
            connectionLoop@ while (shouldReconnect && !Thread.currentThread().isInterrupted) {
                // Re-read the target every iteration. The activity's
                // retargetForReconnect can change [address]/[port] live to point us
                // at a different child — previously this lambda captured them as
                // immutable params and we'd silently reconnect to the OLD address
                // forever after a swap (review finding #1).
                val address = this.address ?: initialAddress
                val port = if (this.port > 0) this.port else initialPort
                if (address.isNullOrBlank() || port <= 0) {
                    LogManager.w(LogCategory.CONNECTION, "doListen: no valid target — exiting reconnect loop")
                    break
                }
                try {
                    val fingerprint = ParentSessionStore.fingerprintFor(applicationContext, childDeviceId)
                    val audioTlsPort = ParentSessionStore.audioTlsPortFor(applicationContext, childDeviceId)
                    val transport = AudioTransportPolicy.decide(
                        hasPin = fingerprint != null,
                        audioTlsPort = audioTlsPort,
                        hasEverAudioTls = ParentSessionStore.hasEverAudioTls(applicationContext, childDeviceId)
                    )
                    if (transport == AudioTransportPolicy.Decision.REFUSE_DOWNGRADE) {
                        surfaceRepairRequired()
                        throw IOException("Secure audio pairing data is incomplete; re-pair by QR")
                    }
                    val useTls = transport == AudioTransportPolicy.Decision.PINNED_TLS
                    val connectionPort = if (useTls) audioTlsPort!! else port
                    Log.i(TAG, "Attempting to connect to $address:$connectionPort (attempt ${reconnectAttempts + 1}, tls=$useTls)")
                    LogManager.logConnection(
                        "Connection attempt ${reconnectAttempts + 1}",
                        ip = address,
                        port = connectionPort,
                        details = "TLS: $useTls, Read timeout: ${audioReadTimeoutMs/1000}s, Health check interval: ${healthCheckInterval/1000}s"
                    )
                    networkMonitor?.logCurrentNetworkState()

                    val connectStartTime = System.currentTimeMillis()
                    // Use explicit connect-with-timeout — the 2-arg Socket(host, port) constructor
                    // would block for the OS default (~75-130s) before failing. We want fast retry.
                    val plainSocket = Socket()
                    // Publish before both TCP connect and TLS negotiation so Stop/retarget can
                    // abort either operation, including when the new route is unreachable.
                    activeSocket = plainSocket
                    try {
                        plainSocket.connect(InetSocketAddress(address, connectionPort), connectTimeoutMs)
                        plainSocket.soTimeout = connectTimeoutMs
                    } catch (failure: Exception) {
                        try { plainSocket.close() } catch (_: Exception) {}
                        if (activeSocket === plainSocket) activeSocket = null
                        throw failure
                    }
                    val socket = if (useTls) {
                        try {
                            de.rochefort.childmonitor.parent.PinnedTls.upgrade(
                                plainSocket, address, connectionPort, fingerprint!!
                            )
                        } catch (e: Exception) {
                            try { plainSocket.close() } catch (_: Exception) {}
                            if (activeSocket === plainSocket) activeSocket = null
                            throw IOException("Pinned TLS audio handshake failed", e)
                        }
                    } else plainSocket
                    socket.soTimeout = audioReadTimeoutMs
                    socket.keepAlive = true
                    socket.tcpNoDelay = true
                    // Replace the published raw socket with its TLS wrapper after a successful
                    // handshake; a retarget racing this assignment is caught by the stale check.
                    activeSocket = socket
                    if (!shouldReconnect || Thread.currentThread().isInterrupted) {
                        try { socket.close() } catch (_: Exception) {}
                        if (activeSocket === socket) activeSocket = null
                        break@connectionLoop
                    }
                    if (address != this.address || port != this.port) {
                        // The target changed while connect() was in flight. Never authenticate this
                        // stale socket; immediately try the newly selected target.
                        LogManager.i(LogCategory.CONNECTION,
                            "Discarding connected socket for superseded target $address:$port")
                        try { socket.close() } catch (_: Exception) {}
                        if (activeSocket === socket) activeSocket = null
                        reconnectAttempts = 0
                        continue@connectionLoop
                    }

                    // #188: send the CMHELLO handshake so the child's accept loop will
                    // promote us from "anonymous LAN peer" to "authenticated parent" and
                    // actually stream audio. The child closes the socket with CMERROR 401
                    // if the token is missing/expired/forged; we treat that as a normal
                    // disconnect and let the reconnect loop retry (the user may need to
                    // rescan the QR if the token expired).
                    // #310/#423: the token is interpolated into the newline-terminated CMHELLO line
                    // below ("CMHELLO 1 $token\n"); a restored token (which bypasses PairingPayload)
                    // with a '\n' would inject a line. Re-validate against SAFE_TOKEN and drop an
                    // unsafe one (→ unauthenticated CMHELLO → child rejects with 401; no injection).
                    val token = ParentSessionStore.get(applicationContext, childDeviceId)
                        ?.takeIf { PairingValidation.isSafeToken(it) }
                    if (token.isNullOrBlank()) {
                        Log.w(TAG, "No usable bearer token for child $childDeviceId — sending unauthenticated CMHELLO (will likely be rejected)")
                    }
                    try {
                        val hello = "CMHELLO 1 ${token ?: ""}\n"
                        socket.getOutputStream().write(hello.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                    } catch (e: IOException) {
                        LogManager.e(LogCategory.CONNECTION, "CMHELLO send failed — closing socket", e)
                        try { socket.close() } catch (_: Exception) {}
                        throw e
                    }

                    val connectDuration = System.currentTimeMillis() - connectStartTime

                    // DO NOT reset reconnectAttempts here yet — wait until we've actually
                    // streamed audio for a sustained period. Otherwise a "connect succeeds
                    // but stream dies in <1s" pattern (port forward to dead service, TCP
                    // half-open after wifi flap, etc.) cycles fast without any backoff growth
                    // and never gives the connection time to stabilize.
                    connectionStartTime = System.currentTimeMillis()
                    totalBytesReceived = 0
                    audioPacketsReceived = 0
                    lastMetricsLogTime = connectionStartTime

                    // Track connection metrics
                    totalConnections++

                    Log.i(TAG, "Successfully connected to child device")
                    LogManager.logConnection(
                        "Successfully connected to child device",
                        ip = address,
                        port = connectionPort,
                        details = "TLS: $useTls, Connect time: ${connectDuration}ms, Read timeout: ${socket.soTimeout/1000}s, Keepalive: enabled, TCP_NODELAY: enabled, Total connections: $totalConnections"
                    )

                    lastStreamAuthRejected = false
                    val success = streamAudio(socket)
                    val authRejected = lastStreamAuthRejected
                    if (address != this.address || port != this.port) {
                        // retargetForReconnect intentionally closes the old stream. That close is
                        // not evidence that the new target failed, so suppress its error callback and
                        // skip backoff to give the replacement address a full attempt immediately.
                        LogManager.i(LogCategory.CONNECTION,
                            "Ignoring stream result from superseded target $address:$port")
                        reconnectAttempts = 0
                        continue@connectionLoop
                    }
                    val connectionDurationMs = System.currentTimeMillis() - connectionStartTime
                    val connectionDuration = connectionDurationMs / 1000

                    // Track connection stability metrics
                    totalConnectionDuration += connectionDuration
                    if (connectionDuration > longestConnectionDuration) {
                        longestConnectionDuration = connectionDuration
                    }

                    // Reset reconnect attempts only if the connection lasted long enough to be
                    // considered a real success — at least 2 seconds of streaming. Otherwise
                    // treat as failure for backoff purposes (prevents fast-cycle thrash).
                    if (connectionDurationMs >= 2000) {
                        reconnectAttempts = 0
                        lastDiscoveryAttemptElapsedMs = 0L // real success — next outage may discover promptly
                    }

                    if (!success && shouldReconnect) {
                        totalDisconnections++
                        val reason = "Socket timeout or I/O error"
                        addDisconnectReason(reason, connectionDuration)

                        Log.w(TAG, "Streaming ended unsuccessfully, will retry")
                        val avgDuration = if (totalConnections > 0) totalConnectionDuration / totalConnections else 0
                        LogManager.w(
                            LogCategory.CONNECTION,
                            "Connection lost after ${connectionDuration}s - will attempt to reconnect | Stability: $totalDisconnections disconnects, Avg duration: ${avgDuration}s, Longest: ${longestConnectionDuration}s"
                        )
                        // #353: a fast post-connect failure is the signature of a CMHELLO rejection
                        // (the child closes with CMERROR 401 when the bearer is expired). Try renewing
                        // the bearer inline and retry NOW with the fresh token — BEFORE notifying the
                        // activity, so its delayed auto-reconnect sweep isn't posted (it would otherwise
                        // fire ~2s later and tear down the freshly-renewed connection). #354 makes a
                        // transient rate-limit 429 and a definitive revoke 401, so the latter can stop
                        // futile retries and visibly request QR re-pairing.
                        val renewNowMs = System.currentTimeMillis()
                        val hasRefreshCred = ParentSessionStore.getRefreshToken(applicationContext, childDeviceId) != null
                        if (authRejected && !hasRefreshCred) {
                            surfaceRepairRequired()
                        }
                        if (de.rochefort.childmonitor.parent.ReconnectRenewalPolicy.shouldRenewOnFailure(
                                connectionDurationMs, hasRefreshCred, lastReconnectRenewAtMs, renewNowMs)) {
                            lastReconnectRenewAtMs = renewNowMs
                            val renewResult = de.rochefort.childmonitor.parent.SessionRenewer
                                .renewWithResult(applicationContext, childDeviceId, address)
                            if (renewResult == de.rochefort.childmonitor.parent.SessionRenewer.RenewResult.RENEWED) {
                                LogManager.i(LogCategory.CONNECTION,
                                    "Bearer renewed after a fast reconnect failure — retrying immediately")
                                reconnectAttempts = 0
                                continue // skip the disconnect alert + the activity reconnect sweep
                            } else if (renewResult == de.rochefort.childmonitor.parent.SessionRenewer.RenewResult.REVOKED ||
                                (authRejected && renewResult ==
                                    de.rochefort.childmonitor.parent.SessionRenewer.RenewResult.UNAVAILABLE)) {
                                surfaceRepairRequired()
                            }
                        }

                        onDisconnectAlert()
                        onError?.invoke()
                    } else if (success) {
                        val avgDuration = if (totalConnections > 0) totalConnectionDuration / totalConnections else 0
                        LogManager.i(
                            LogCategory.CONNECTION,
                            "Connection closed gracefully after ${connectionDuration}s | Stability: $totalConnections connections, Avg: ${avgDuration}s, Longest: ${longestConnectionDuration}s"
                        )
                    }
                } catch (e: IOException) {
                    if (address != this.address || port != this.port) {
                        LogManager.i(LogCategory.CONNECTION,
                            "Ignoring connection failure from superseded target $address:$port")
                        reconnectAttempts = 0
                        continue@connectionLoop
                    }
                    totalDisconnections++
                    val reason = "${e.javaClass.simpleName}: ${e.message}"
                    addDisconnectReason(reason, 0)

                    Log.e(TAG, "Error connecting to $address on port $port", e)
                    LogManager.e(
                        LogCategory.CONNECTION,
                        "Failed to connect to $address:$port - $reason | Total disconnects: $totalDisconnections",
                        e
                    )
                    // #133 review-pass-7: checkpoint the failure so DebugBundle's
                    // 'died-in-flight' detection has a FAILED marker to find.
                    // Without this, the FSM is stuck at CONNECTING through every
                    // retry, indistinguishable from a slow-but-successful connect.
                    // Review-pass-8: gate BOTH transitions on shouldReconnect.
                    // Otherwise a user-pressed Stop concurrent with this catch
                    // races the onDestroy DRAINING transition: we'd emit
                    // DRAINING → FAILED, leaving a phantom 'crash' checkpoint
                    // that the died-in-flight detector misinterprets.
                    if (::sessionFsm.isInitialized && shouldReconnect) {
                        sessionFsm.transition(
                            de.rochefort.childmonitor.reliability.SessionState.FAILED,
                            mapOf("reason" to (e.message ?: e.javaClass.simpleName))
                        )
                        // Walk back to STARTING/CONNECTING so the next loop
                        // iteration's connect attempt can re-record the state.
                        sessionFsm.transition(
                            de.rochefort.childmonitor.reliability.SessionState.STARTING,
                            mapOf("source" to "retry_after_failure")
                        )
                    }
                    if (shouldReconnect) {
                        onDisconnectAlert()
                        onError?.invoke()
                    }
                } catch (e: Exception) {
                    if (address != this.address || port != this.port) {
                        LogManager.i(LogCategory.CONNECTION,
                            "Ignoring unexpected failure from superseded target $address:$port")
                        reconnectAttempts = 0
                        continue@connectionLoop
                    }
                    totalDisconnections++
                    val reason = "Unexpected: ${e.javaClass.simpleName}"
                    addDisconnectReason(reason, 0)

                    Log.e(TAG, "Unexpected error during connection", e)
                    LogManager.e(
                        LogCategory.CONNECTION,
                        "Unexpected error during connection - $reason | Total disconnects: $totalDisconnections",
                        e
                    )
                }

                // Wait before reconnecting (exponential backoff with early wake on network restore)
                if (shouldReconnect && !Thread.currentThread().isInterrupted) {
                    // A restore can arrive while connect()/stream teardown or discovery is still in
                    // progress. Consume it BEFORE calculating/sleeping; the former unconditional
                    // signal.clear() below lost that event and could still sleep for up to 60s.
                    if (networkRestoredSignal.compareAndSet(true, false)) {
                        reconnectAttempts = 0
                        lastDiscoveryAttemptElapsedMs = 0L
                        LogManager.i(LogCategory.CONNECTION,
                            "Network restored before backoff — retrying immediately")
                        continue@connectionLoop
                    }
                    // #1 (Kids reconnect): once plain-IP retries have clearly failed and we have a
                    // deviceId, escalate to NSD discovery-by-deviceId so a BACKGROUNDED parent still
                    // finds the child after its IP changed. retargetForReconnect (inside) swaps the
                    // target + closes the socket, so the next loop iteration connects to the new IP.
                    val discoveryNow = android.os.SystemClock.elapsedRealtime()
                    if (childDeviceId != null && KidReconnectPolicy.shouldDiscoverInBackground(
                            reconnectAttempts, lastDiscoveryAttemptElapsedMs, discoveryNow)) {
                        lastDiscoveryAttemptElapsedMs = discoveryNow
                        if (discoverByDeviceIdAndRetarget()) {
                            reconnectAttempts = 0
                            lastDiscoveryAttemptElapsedMs = 0L
                            continue
                        }
                    }
                    val shift = min(reconnectAttempts, 5) // Cap shift to prevent overflow (max multiplier: 32x)
                    val delay = min(initialReconnectDelay * (1L shl shift), maxReconnectDelay)
                    reconnectAttempts++
                    Log.i(TAG, "Waiting ${delay}ms before reconnection attempt...")
                    LogManager.i(
                        LogCategory.CONNECTION,
                        "Waiting ${delay/1000}s before reconnection attempt ${reconnectAttempts + 1} (exponential backoff)"
                    )
                    val waitStart = System.currentTimeMillis()
                    var interrupted = false
                    // Poll for early wake-up signal in short sleep slices so network restore can interrupt backoff
                    while (System.currentTimeMillis() - waitStart < delay) {
                        if (networkRestoredSignal.compareAndSet(true, false)) {
                            LogManager.i(LogCategory.CONNECTION, "Backoff interrupted by network-restored signal — retrying immediately")
                            reconnectAttempts = 0 // Network came back; reset backoff state
                            lastDiscoveryAttemptElapsedMs = 0L
                            break
                        }
                        try {
                            Thread.sleep(200)
                        } catch (e: InterruptedException) {
                            interrupted = true
                            break
                        }
                    }
                    if (interrupted) {
                        Log.i(TAG, "Reconnection wait interrupted")
                        LogManager.i(LogCategory.CONNECTION, "Reconnection wait interrupted - stopping")
                        break
                    }
                }
            }
            Log.i(TAG, "Listen thread exiting")
            LogManager.i(LogCategory.CONNECTION, "Connection loop terminated")
        }
        this.listenThread = lt
        lt.start()
    }

    /**
     * [#272] (Re)arm exactly one health-check chain: remove any queued runnable first so
     * repeated onStartCommand deliveries (re-bind, START redelivery) don't stack duplicate
     * self-perpetuating watchdogs.
     */
    private fun armHealthCheck() {
        healthCheckHandler.removeCallbacks(healthCheckRunnable)
        healthCheckHandler.postDelayed(healthCheckRunnable, healthCheckInterval)
    }

    private fun checkConnectionHealth() {
        val timeSinceLastData = System.currentTimeMillis() - lastAudioDataTime
        if (lastAudioDataTime > 0 && timeSinceLastData > noDataThresholdMs) {
            Log.w(TAG, "No audio data received for ${timeSinceLastData}ms, reconnecting...")
            LogManager.w(
                LogCategory.HEALTH,
                "Health check FAILED - no audio data for ${timeSinceLastData/1000}s (threshold: ${noDataThresholdMs/1000}s) - forcing reconnect"
            )
            networkMonitor?.logCurrentNetworkState()
            // [#270] Force reconnection WITHOUT terminating the loop. Closing the active socket
            // unblocks a stuck blocking read so streamAudio() returns and the loop drops into
            // backoff; resetting reconnectAttempts + signalling network-restored wakes that
            // backoff immediately (the same kick onNetworkRestored uses). The previous
            // listenThread.interrupt() set an interrupt flag that the loop guards on
            // (!Thread.currentThread().isInterrupted) and the backoff sleep treats as "stop",
            // so it PERMANENTLY killed the reconnect loop — leaving the parent silently
            // un-monitored after a >60s child outage. interrupt() stays reserved for real
            // teardown (onDestroy, which sets shouldReconnect=false first).
            try { activeSocket?.close() } catch (_: Exception) {}
            reconnectAttempts = 0
            networkRestoredSignal.set(true)
        } else if (lastAudioDataTime > 0) {
            LogManager.d(
                LogCategory.HEALTH,
                "Health check passed - received audio ${timeSinceLastData/1000}s ago"
            )
        }
    }

    private fun armStatusPoll() {
        statusPollHandler.removeCallbacks(statusPollRunnable)
        statusPollHandler.post(statusPollRunnable)
    }

    /**
     * #301: fetch the child's battery from /api/status off the main thread, cache it for the UI,
     * and warn (once, edge-triggered) when it's low. Best-effort — failures are silent.
     */
    private fun pollChildStatusAsync() {
        val addr = address ?: return
        val requestDeviceId = childDeviceId
        // Single daemon-executor + compareAndSet guard (mirrors the snapshot-fetch pattern; avoids
        // the per-tick raw Thread + check-then-set race a prior review removed elsewhere).
        if (!statusPollInFlight.compareAndSet(false, true)) return
        statusPollExecutor.execute {
            var conn: java.net.HttpURLConnection? = null
            try {
                val requestToken = ParentSessionStore.get(applicationContext, requestDeviceId)
                conn = PinnedHttp.openPinned(applicationContext, buildStatusUrl(addr), requestDeviceId)
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                requestToken?.let {
                    conn.setRequestProperty("Authorization", "Bearer $it")
                }
                conn.connect()
                when (conn.responseCode) {
                    java.net.HttpURLConnection.HTTP_OK -> {
                        val body = conn.inputStream.use { it.bufferedReader().readText() }
                        val status = org.json.JSONObject(body)
                        val bat = status.optJSONObject("battery")
                        if (bat != null) {
                            val level = bat.optInt("level", -1)
                            val charging = bat.optBoolean("charging", false)
                            childBatteryPct = level
                            childCharging = charging
                            val fire = batteryAlert.onReading(level, charging)
                            val isLow = de.rochefort.childmonitor.util.BatteryTelemetryPolicy.isLow(level, charging)
                            if (isLow && !mutedNow()) {
                                if (fire) batteryNotifier.postLow(level)
                            } else {
                                batteryNotifier.clear() // recovered / charging / muted → drop the warning
                            }
                        }
                        processRemoteEvent(status.optJSONObject("soundEvent"))
                        processRemoteEvent(status.optJSONObject("motionEvent"))
                        processEnvironmentAlert(status.optJSONObject("mqtt"))
                        // #348: the bearer is valid right now and we're on the pinned TLS channel —
                        // mint/re-mint the durable refresh credential when due so the parent can renew
                        // for months without re-pairing the hard-to-reach child.
                        de.rochefort.childmonitor.parent.SessionRenewer.bootstrapIfNeeded(
                            applicationContext, requestDeviceId, addr, System.currentTimeMillis()
                        )
                    }
                    java.net.HttpURLConnection.HTTP_UNAUTHORIZED -> {
                        try { conn.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                        // #348: bearer expired/rejected — redeem the durable refresh credential for a
                        // fresh bearer so the next poll AND the audio reconnect (which reads the stored
                        // token) recover without a re-pair.
                        val result = de.rochefort.childmonitor.parent.SessionRenewer
                            .recoverRejectedBearer(applicationContext, requestDeviceId, addr, requestToken)
                        if (requestDeviceId == childDeviceId &&
                            result == de.rochefort.childmonitor.parent.SessionRenewer.RenewResult.REVOKED) {
                            surfaceRepairRequired()
                        }
                    }
                    else -> {
                        // Drain the error body so the socket returns to the keep-alive pool (review #9).
                        try { conn.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "child status poll failed: ${e.message}")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                statusPollInFlight.set(false)
            }
        }
    }

    /** #341: de-duplicate durable child events and surface fresh, child-gated alerts locally. */
    private fun processRemoteEvent(event: org.json.JSONObject?) {
        if (event == null || event.isNull("type")) return
        val type = event.optString("type", "")
        if (type.isBlank()) return
        val id = event.optLong("id", -1L)
        val occurredAt = event.optLong("occurredAtMs", -1L)
        val ageMs = event.optLong("ageMs", Long.MAX_VALUE)
        val signature = "$id:$occurredAt:$type"
        val deviceKey = childDeviceId?.takeIf { it.isNotBlank() } ?: address ?: "unknown"
        val family = if (type == "motion") "motion" else "sound"
        val prefKey = "handled.$deviceKey.$family"
        val prefs = getSharedPreferences(EVENT_ALERT_PREFS, MODE_PRIVATE)
        val alreadyHandled = prefs.getString(prefKey, null) == signature
        val shouldNotify = de.rochefort.childmonitor.events.RemoteEventAlertPolicy.shouldNotify(
            type = type,
            wouldAlert = event.optBoolean("wouldAlert", false),
            ageMs = ageMs,
            alreadyHandled = alreadyHandled,
            muted = mutedNow(),
        )
        if (alreadyHandled) return

        var handled = !shouldNotify
        if (shouldNotify) {
            val child = KidDisplayName.resolve(kidDisplayName, childDeviceName, childDeviceId)
                ?: getString(R.string.remote_event_child_fallback)
            val isMotion = type == "motion"
            handled = eventNotifier.notify(
                if (isMotion) de.rochefort.childmonitor.events.EventNotifier.Kind.MOTION
                else de.rochefort.childmonitor.events.EventNotifier.Kind.CRY,
                getString(if (isMotion) R.string.remote_motion_alert_title else R.string.remote_sound_alert_title),
                getString(
                    if (isMotion) R.string.remote_motion_alert_body else R.string.remote_sound_alert_body,
                    child,
                ),
                Intent(this, ListenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            )
        }
        // commit(): survive a process kill immediately after notifying, avoiding duplicate alarms.
        if (handled) prefs.edit().putString(prefKey, signature).commit()
    }

    /** #315: surface fresh out-of-range room readings once, using the critical alert channel. */
    private fun processEnvironmentAlert(mqtt: org.json.JSONObject?) {
        if (mqtt == null || mqtt.isNull("environmentAlert")) return
        val alert = mqtt.optString("environmentAlert", "")
        val updatedAt = mqtt.optLong("environmentUpdatedAtMs", -1L)
        val signature = "$alert:$updatedAt"
        val deviceKey = childDeviceId?.takeIf { it.isNotBlank() } ?: address ?: "unknown"
        val prefKey = "handled.$deviceKey.environment"
        val prefs = getSharedPreferences(EVENT_ALERT_PREFS, MODE_PRIVATE)
        val alreadyHandled = prefs.getString(prefKey, null) == signature
        val shouldNotify = de.rochefort.childmonitor.events.RemoteEnvironmentAlertPolicy.shouldNotify(
            alert, updatedAt, System.currentTimeMillis(), alreadyHandled, mutedNow(),
        )
        if (alreadyHandled) return
        var handled = !shouldNotify
        if (shouldNotify) {
            val child = KidDisplayName.resolve(kidDisplayName, childDeviceName, childDeviceId)
                ?: getString(R.string.remote_event_child_fallback)
            handled = eventNotifier.notify(
                de.rochefort.childmonitor.events.EventNotifier.Kind.CRITICAL,
                getString(R.string.remote_environment_alert_title),
                getString(R.string.remote_environment_alert_body, child, environmentAlertLabel(alert)),
                Intent(this, ListenActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                ),
            )
        }
        if (handled) prefs.edit().putString(prefKey, signature).commit()
    }

    private fun environmentAlertLabel(alert: String): String = getString(when (alert) {
        "temperature_low" -> R.string.remote_environment_temperature_low
        "temperature_high" -> R.string.remote_environment_temperature_high
        "humidity_low" -> R.string.remote_environment_humidity_low
        "humidity_high" -> R.string.remote_environment_humidity_high
        else -> R.string.remote_environment_out_of_range
    })

    /** Child web endpoint for status, honouring TLS pinning like ListenActivity.buildEndpointUrl. */
    private fun buildStatusUrl(addr: String): String {
        val hasPinNow = ParentSessionStore.fingerprintFor(this, childDeviceId) != null
        val hasEverPinned = ParentSessionStore.hasEverPinned(this, childDeviceId)
        return if (TransportPolicy.requireTls(hasPinNow, hasEverPinned)) {
            val port = ParentSessionStore.httpsPortFor(this, childDeviceId) ?: 11443
            "https://${NetworkEndpoint.hostForAuthority(addr)}:$port/api/status"
        } else {
            "http://${NetworkEndpoint.hostForAuthority(addr)}:11000/api/status"
        }
    }

    /**
     * Called by NetworkStateMonitor when network becomes available again (wifi back on,
     * airplane mode off, etc). Reset backoff and break out of any sleep immediately so
     * we reconnect as fast as possible instead of waiting up to 60s.
     *
     * IMPORTANT: do NOT close `activeSocket` unconditionally. The callback also fires on
     * cellular→WiFi handoff while we're happily streaming; closing a healthy socket then
     * would cause a needless reconnect. Only kick a stuck/zombie socket if we are NOT
     * currently receiving audio (i.e., still in connect or backoff phase).
     */
    fun onNetworkRestored() {
        LogManager.i(LogCategory.NETWORK, "Network restored signal received — kicking reconnect loop awake")
        reconnectAttempts = 0
        lastDiscoveryAttemptElapsedMs = 0L
        networkRestoredSignal.set(true)
        // Only close the socket if we're NOT actively streaming audio. A live stream
        // doesn't need to be reset just because the OS reported a network change.
        if (!isAudioPlaying) {
            try { activeSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun streamAudio(socket: Socket): Boolean {
        Log.i(TAG, "Setting up stream")
        LogManager.logAudio(
            "Initializing audio playback",
            details = "Frequency: ${frequency}Hz, Buffer: $bufferSize bytes, Codec: G.711 μ-law"
        )

        // Ensure we have audio focus before starting playback
        if (!hasAudioFocus) {
            requestAudioFocus()
        }

        // Build AudioTrack with proper attributes for media playback
        val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val audioFormat = android.media.AudioFormat.Builder()
                .setSampleRate(frequency)
                .setChannelMask(channelConfiguration)
                .setEncoding(audioEncoding)
                .build()
            val builder = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                // POWER_SAVING tells AudioFlinger to favour larger HW buffers and, where
                // the hardware supports it, route playback through the audio offload DSP
                // instead of waking the application processor every ~20 ms. The trade-off
                // is up to ~100 ms of extra output latency, which is irrelevant for a
                // continuously-streaming baby monitor (we already keep ~250 ms of jitter
                // buffer ahead of playback). Saves measurable power on long overnight
                // sessions when the screen is off.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            }
            builder.build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC,
                frequency,
                channelConfiguration,
                audioEncoding,
                bufferSize,
                AudioTrack.MODE_STREAM)
        }
        val readBuffer = ByteArray(byteBufferSize)
        val decodedBuffer = ShortArray(byteBufferSize * 2)
        val maxBufferedMs = 2000 // cap queued audio to ~2s to avoid long-term drift
        val playbackQueue = de.rochefort.childmonitor.audio.PlaybackQueueTracker()
        // Variables for connection quality tracking
        var packetsInLastSecond = 0
        var bytesInLastSecond = 0L
        var lastQualityCheckTime = System.currentTimeMillis()
        var lastPacketArrivalTime = 0L
        var smoothedGapMs = 0.0
        var smoothedJitterMs = 0.0
        var smoothedPlaybackGain = 1.8f
        var streamEstablished = false

        try {
            val inputStream = PushbackInputStream(socket.getInputStream(), 128)
            when (AudioStreamPreamble.probe(inputStream)) {
                AudioStreamPreamble.Result.UNAUTHORIZED -> {
                    lastStreamAuthRejected = true
                    LogManager.w(LogCategory.CONNECTION,
                        "Child explicitly rejected CMHELLO bearer (401)")
                    return false
                }
                AudioStreamPreamble.Result.SERVER_ERROR -> {
                    LogManager.w(LogCategory.CONNECTION,
                        "Child rejected CMHELLO with a server error")
                    return false
                }
                AudioStreamPreamble.Result.CLOSED -> return false
                AudioStreamPreamble.Result.AUDIO -> Unit
            }

            try {
                audioTrack.play()
                LogManager.i(LogCategory.AUDIO, "Audio playback started successfully")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to start streamed audio playback", e)
                LogManager.e(LogCategory.AUDIO, "Failed to start audio playback", e)
                return false
            }

            LogManager.i(LogCategory.AUDIO, "Audio streaming started - listening for data")

            while (!Thread.currentThread().isInterrupted) {
                val len = inputStream.read(readBuffer)
                if (len < 0) {
                    // If the current thread was not interrupted this means the remote stopped streaming
                    LogManager.w(
                        LogCategory.AUDIO,
                        "Remote stopped streaming (read returned -1) - connection closed by child device"
                    )
                    return Thread.currentThread().isInterrupted
                }
                val decoded: Int = AudioCodecDefines.CODEC.decode(decodedBuffer, readBuffer, len, 0)
                if (decoded > 0) {
                    val packetNow = System.currentTimeMillis()
                    if (!streamEstablished) {
                        streamEstablished = true
                        isAudioPlaying = true
                        connectionQuality = ConnectionQuality.GOOD
                        // A TCP connect is not a successful session: CMHELLO may still be rejected.
                        // Publish success only after the first real audio packet arrives.
                        if (::sessionFsm.isInitialized) {
                            sessionFsm.transition(
                                de.rochefort.childmonitor.reliability.SessionState.CONNECTED,
                                mapOf("address" to (this.address ?: ""))
                            )
                            sessionFsm.transition(
                                de.rochefort.childmonitor.reliability.SessionState.ACTIVE,
                                mapOf("address" to (this.address ?: ""))
                            )
                        }
                        alarm.reset()
                        connectionAlert.reset()
                        highVisibilityAlerter.reset()
                        batteryAlert.reset()
                        onConnected?.invoke()
                    }
                    if (lastPacketArrivalTime > 0L) {
                        val packetGapMs = (packetNow - lastPacketArrivalTime).coerceAtLeast(0L)
                        smoothedGapMs = if (smoothedGapMs == 0.0) {
                            packetGapMs.toDouble()
                        } else {
                            (smoothedGapMs * 0.85) + (packetGapMs * 0.15)
                        }
                        val instantJitterMs = kotlin.math.abs(packetGapMs - smoothedGapMs)
                        smoothedJitterMs = if (smoothedJitterMs == 0.0) {
                            instantJitterMs
                        } else {
                            (smoothedJitterMs * 0.8) + (instantJitterMs * 0.2)
                        }
                        packetDelayMs = smoothedGapMs.toInt()
                        packetJitterMs = smoothedJitterMs.toInt()
                    }
                    lastPacketArrivalTime = packetNow

                    // Update timestamp when audio data is received
                    lastAudioDataTime = packetNow

                    totalBytesReceived += len
                    audioPacketsReceived++
                    bytesInLastSecond += len

                    // Skip AudioTrack write when muted (talkback active) or while a replay clip is
                    // playing (#338) to prevent a feedback loop / overlapping audio. We still read
                    // from the socket to keep the connection alive.
                    if (!playbackMuted && !replayActive) {
                        smoothedPlaybackGain = applyPlaybackLoudnessGain(decodedBuffer, decoded, smoothedPlaybackGain)
                        var offset = 0
                        while (offset < decoded && !Thread.currentThread().isInterrupted) {
                            val written = audioTrack.write(decodedBuffer, offset, decoded - offset)
                            if (written <= 0) throw java.io.IOException("Audio playback write failed: $written")
                            playbackQueue.recordWrite(written)
                            offset += written
                        }
                    }

                    // If playback is falling behind (ex: network burst or paused CPU),
                    // drop queued audio so parents stay in sync with the child.
                    val bufferedFrames = playbackQueue.queuedFrames(audioTrack.playbackHeadPosition)
                    val bufferedMs = (bufferedFrames * 1000L) / frequency
                    if (bufferedMs > maxBufferedMs) {
                        Log.w(TAG, "Playback lag detected: ${bufferedMs}ms queued, flushing to resync")
                        LogManager.w(
                            LogCategory.AUDIO,
                            "Playback lag ${bufferedMs}ms (> ${maxBufferedMs}ms) - dropping backlog to stay live"
                        )
                        // Android flush is a no-op while playing. Pause first, then reset
                        // the unsigned playback counter with the discarded queue.
                        // https://developer.android.com/reference/android/media/AudioTrack#flush()
                        audioTrack.pause()
                        audioTrack.flush()
                        playbackQueue.reset()
                        audioTrack.play()
                    }

                    val decodedBytes = ShortArray(decoded)
                    System.arraycopy(decodedBuffer, 0, decodedBytes, 0, decoded)
                    volumeHistory.onAudioData(decodedBytes)
                    // #283: feed the night-timeline recorder (per-minute noise levels).
                    nightRecorder?.onAudioSamples(decodedBytes, decoded)
                    onUpdate?.invoke()

                    // Track packets for connection quality
                    packetsInLastSecond++

                    // Track packet rate/throughput and derive coarse link quality for video pacing.
                    val now = packetNow
                    if (now - lastQualityCheckTime >= 1000) {
                        connectionQuality = when {
                            bytesInLastSecond >= 6000L -> ConnectionQuality.EXCELLENT
                            bytesInLastSecond >= 4500L -> ConnectionQuality.GOOD
                            bytesInLastSecond >= 2500L -> ConnectionQuality.FAIR
                            else -> ConnectionQuality.POOR
                        }
                        packetsInLastSecond = 0
                        bytesInLastSecond = 0L
                        lastQualityCheckTime = now
                    }

                    // Log metrics every 60 seconds
                    if (now - lastMetricsLogTime >= 60000) {
                        val rateKBps = totalBytesReceived / 60.0 / 1024.0
                        val connectionDuration = (now - connectionStartTime) / 1000
                        LogManager.logAudio(
                            "Audio streaming metrics",
                            bytesTransferred = totalBytesReceived,
                            details = "Rate: ${"%.2f".format(rateKBps)} KB/s, Packets: $audioPacketsReceived, Connection duration: ${connectionDuration}s, Quality: $connectionQuality, Gain: ${"%.2f".format(smoothedPlaybackGain)}x"
                        )
                        totalBytesReceived = 0
                        audioPacketsReceived = 0
                        lastMetricsLogTime = now
                    }
                }
            }
            LogManager.i(LogCategory.AUDIO, "Audio streaming thread interrupted - stopping")
            return true
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "No audio packets received for ${socket.soTimeout}ms, forcing reconnect", e)
            LogManager.w(
                LogCategory.AUDIO,
                "Audio stream timed out after ${socket.soTimeout/1000}s without packets - reconnecting"
            )
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            LogManager.e(LogCategory.AUDIO, "Audio streaming failed", e)
            return false
        } finally {
            isAudioPlaying = false
            connectionQuality = ConnectionQuality.UNKNOWN
            packetDelayMs = 0
            packetJitterMs = 0
            try { audioTrack.stop() } catch (_: Exception) {}
            try { audioTrack.release() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}
            if (activeSocket === socket) activeSocket = null
            LogManager.i(LogCategory.AUDIO, "Audio playback stopped and socket closed")
        }
    }

    /** #290: on a lost/failed connection, dispatch the configured alert behaviour.
     *  #289: when the user wants alerts (not muted), also post a high-priority,
     *  DnD-piercing, full-screen "disconnected" notification so it can't be missed. */
    private fun onDisconnectAlert() {
        // #357: fire each enabled alert effect independently (sound / vibration / screen-flash /
        // torch). Nothing enabled → nothing happens.
        val effects = de.rochefort.childmonitor.alarm.AlertEffectsPolicy.load(
            getSharedPreferences("ListenPreferences", MODE_PRIVATE)
        )
        if (!effects.any()) return
        // #347: onDisconnectAlert() runs once per reconnect RETRY (the reconnect loop calls it on
        // every failed attempt). Drive the #289 episode debounce and only fire the one-shot cues on
        // its FIRST post — so a sustained outage produces exactly ONE beep / vibration / flash and a
        // sub-threshold blip produces none, instead of a cue per backoff tick. This also posts the
        // disconnect notification (its full-screen intent carries the screen-flash). The PERSISTENT
        // looping alarm is intentionally exempt (it's meant to repeat until acknowledged).
        val firstPostThisEpisode = connectionAlert.onDisconnect(System.currentTimeMillis())

        // SOUND: only if the sound effect is on. Then the mute / persistent toggles decide one-shot
        // vs looping vs silent (#290: a deliberately-set persistent alarm overrides the transient
        // mute). The sound-effect toggle itself is absolute — off means no sound at all.
        if (effects.sound) {
            when (de.rochefort.childmonitor.alarm.AlarmPolicy.mode(persistentAlarmNow(), mutedNow())) {
                de.rochefort.childmonitor.alarm.AlertMode.ONE_SHOT -> if (firstPostThisEpisode) playAlert()
                de.rochefort.childmonitor.alarm.AlertMode.PERSISTENT -> alarm.start()
                de.rochefort.childmonitor.alarm.AlertMode.SILENT -> {}
            }
        }
        // VIBRATION + SCREEN-FLASH + TORCH: non-audible, so they fire even when sound is muted
        // (accessibility #303). Gated on the same first-post so they fire once per outage and the
        // alerter (which tracks the episode to dismiss the FSI-launched screen-flash overlay on
        // reconnect/acknowledge) starts in step with the notification that launches that overlay.
        if (effects.anyNonAudible() && firstPostThisEpisode) {
            highVisibilityAlerter.start(
                vibration = effects.vibration,
                screenFlash = effects.screenFlash,
                torch = effects.torch
            )
        }
    }

    /** #290: hard-stop the persistent alarm (e.g. the user turned the setting off). */
    fun stopPersistentAlarm() {
        alarm.stop()
    }

    /** #290/#289: user acknowledged (opened the parent app) — silence the alarm + clear the
     *  disconnect notification + suppress re-arming until reconnect. */
    fun acknowledgePersistentAlarm() {
        alarm.acknowledge()
        connectionAlert.acknowledge()
        highVisibilityAlerter.acknowledge() // #303
    }

    /**
     * #338 (part of #284): play a captured replay clip — [ulaw] is raw G.711 µ-law, 8 kHz mono,
     * exactly what the child's `GET /api/replay/audio` returns. Decodes to PCM16 and plays it on a
     * dedicated background thread through a one-shot AudioTrack, suppressing the live stream's
     * output for the duration (see [replayActive]) so they don't overlap. Works whether or not a
     * live stream is up — replay is most useful right after a disconnect, when there is no live
     * audio. A new call cancels any in-progress replay. [onFinished] is posted to the main thread
     * when playback ends (completed, cancelled, or failed) so the caller can restore its UI.
     */
    fun playReplay(ulaw: ByteArray, onFinished: (() -> Unit)? = null) {
        if (ulaw.isEmpty()) {
            onFinished?.let { healthCheckHandler.post(it) }
            return
        }
        stopReplay() // cancel any prior replay before starting a new one
        val clip = ulaw.copyOf() // don't depend on the caller's buffer staying intact
        val t = Thread({ runReplay(clip, onFinished) }, "ListenService-replay").apply { isDaemon = true }
        replayThread = t
        t.start()
    }

    /**
     * #338: stop any in-progress replay clip. Interrupts the playback thread AND briefly joins it so
     * a following [playReplay] can't start a second AudioTrack before this one has released — the
     * interrupt unblocks the write/drain loop within ~30 ms, so the join returns quickly. Bounded so
     * a wedged AudioTrack call can't hang the caller (e.g. the main thread in onDestroy).
     */
    fun stopReplay() {
        val t = replayThread ?: return
        replayThread = null
        t.interrupt()
        try {
            t.join(1500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun runReplay(ulaw: ByteArray, onFinished: (() -> Unit)?) {
        replayActive = true
        var track: AudioTrack? = null
        try {
            val pcm = ShortArray(ulaw.size)
            val frames = AudioCodecDefines.CODEC.decode(pcm, ulaw, ulaw.size, 0)
            if (frames <= 0) return
            if (!hasAudioFocus) requestAudioFocus()
            track = buildReplayTrack()
            track.play()
            Log.i(TAG, "Replay playing $frames samples (~${ReplayAudioPolicy.durationMs(ulaw.size)}ms)")
            var off = 0
            while (off < frames && !Thread.currentThread().isInterrupted) {
                val w = track.write(pcm, off, frames - off) // WRITE_BLOCKING by default
                if (w <= 0) break
                off += w
            }
            // All samples are queued but not necessarily played out yet; wait for the hardware to
            // drain (or for an interrupt / a generous timeout) before tearing the track down.
            val deadline = System.currentTimeMillis() +
                ReplayAudioPolicy.durationMs(ulaw.size) + 1000L
            while (!Thread.currentThread().isInterrupted &&
                track.playbackHeadPosition < frames &&
                System.currentTimeMillis() < deadline) {
                Thread.sleep(30)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "Replay playback failed", e)
        } finally {
            try { track?.pause() } catch (_: Exception) {}
            try { track?.flush() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            replayActive = false
            // Only clear the slot if it still points at us — a successor started by a bounded-out
            // stopReplay() join must not be clobbered.
            synchronized(this) { if (replayThread === Thread.currentThread()) replayThread = null }
            onFinished?.let { healthCheckHandler.post(it) }
        }
    }

    private fun buildReplayTrack(): AudioTrack {
        val bufBytes = bufferSize
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val fmt = android.media.AudioFormat.Builder()
                .setSampleRate(frequency)
                .setChannelMask(channelConfiguration)
                .setEncoding(audioEncoding)
                .build()
            AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC, frequency, channelConfiguration, audioEncoding,
                bufBytes, AudioTrack.MODE_STREAM
            )
        }
    }

    private fun playAlert() {
        if (mutedNow()) {
            Log.i(TAG, "Alert muted")
            return
        }
        val mp = MediaPlayer.create(this, R.raw.upward_beep_chromatic_fifths)
        if (mp != null) {
            Log.i(TAG, "Playing alert")
            mp.setOnCompletionListener { obj: MediaPlayer -> obj.release() }
            mp.start()
            return
        }
        Log.e(TAG, "Failed to play alert via MediaPlayer — falling back to vibration")
        LogManager.e(LogCategory.AUDIO, "Alert MediaPlayer.create returned null — fallback to vibration")
        // Fallback: vibrate so the parent isn't kept asleep by a silent disconnect alert (#187).
        // A baby-monitor missing its disconnect alert is the worst case for a sleep-deprived parent.
        vibrateAlert()
    }

    /**
     * #292/#187: fire a one-shot vibration waveform. Used both as the #187 MediaPlayer-failure
     * fallback and as the primary tactile cue for vibration-only mode — so vibration never depends
     * on the notification path (which on pre-O cannot pierce Do-Not-Disturb). Needs VIBRATE.
     */
    private fun vibrateAlert() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                vm.defaultVibrator.vibrate(
                    android.os.VibrationEffect.createWaveform(ALERT_VIBRATION_PATTERN, -1)
                )
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(android.os.VibrationEffect.createWaveform(ALERT_VIBRATION_PATTERN, -1))
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(ALERT_VIBRATION_PATTERN, -1)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    /**
     * Applies adaptive loudness gain on decoded PCM before AudioTrack playback.
     */
    private fun applyPlaybackLoudnessGain(
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
            rms <= 0.0 -> playbackMaxGain
            else -> (playbackTargetRms / rms).toFloat().coerceIn(playbackMinGain, playbackMaxGain)
        }

        val smoothed = if (desiredGain > previousGain) {
            previousGain + ((desiredGain - previousGain) * 0.16f)
        } else {
            previousGain + ((desiredGain - previousGain) * 0.10f)
        }

        val userMultiplier = playbackCalibrationMultiplier
        val gainCap = playbackMaxGain * userMultiplier
        val limiterGain = if (peak > 0) {
            min(gainCap, playbackLimiterPeak / peak.toFloat())
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

    /**
     * Track recent disconnect reasons for diagnostics
     * Keeps last 5 disconnect events
     */
    private fun addDisconnectReason(reason: String, duration: Long) {
        synchronized(recentDisconnectReasons) {
            val entry = "After ${duration}s: $reason"
            recentDisconnectReasons.add(0, entry)
            // Keep only last 5 disconnect reasons
            if (recentDisconnectReasons.size > 5) {
                recentDisconnectReasons.removeAt(recentDisconnectReasons.size - 1)
            }
        }
    }

    companion object {
        private const val TAG = "ListenService"
        private const val STATUS_POLL_INTERVAL_MS = 5_000L
        private const val EVENT_ALERT_PREFS = "RemoteEventAlerts"
        const val CHANNEL_ID = TAG
        const val ID = 902938409
        // ~250 ms of 8 kHz mono 16-bit PCM = 4000 bytes. Used when AudioTrack.getMinBufferSize
        // reports an error code (-1/-2) — happens on some devices when AudioFlinger isn't
        // ready yet at first call.
        private const val FALLBACK_BUFFER_BYTES = 4000

        // #141: onCreate → onStartCommand gap thresholds. Soft = warn-but-continue;
        // hard = refuse and stopSelf so the system spins up a clean service with
        // a fresh onCreate. A 97-minute gap was observed in the wild — long enough
        // that any cached connection state, audio focus, etc. is meaningless.
        private const val STARTCMD_SOFT_GAP_MS = 5_000L
        private const val STARTCMD_HARD_GAP_MS = 15L * 60L * 1000L
    }
}
