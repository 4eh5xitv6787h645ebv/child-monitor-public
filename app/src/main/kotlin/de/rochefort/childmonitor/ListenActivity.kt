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

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.MotionEvent
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import de.rochefort.childmonitor.audio.ReplayAudioPolicy
import de.rochefort.childmonitor.audio.ReplayOutcome
import de.rochefort.childmonitor.privacy.PrivacyLevel
import de.rochefort.childmonitor.privacy.PrivacyPosture
import de.rochefort.childmonitor.video.PipPolicy
import de.rochefort.childmonitor.video.SnapshotSessionPolicy
import de.rochefort.childmonitor.video.ReplayScrubPolicy
import de.rochefort.childmonitor.media.GallerySaver
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import de.rochefort.childmonitor.ListenService.ListenBinder
import org.json.JSONObject
import fi.iki.elonen.NanoWSD

class ListenActivity : Activity() {
    // Don't attempt to unbind from the service unless the client has received some
    // information about the service's state.
    private var shouldUnbind = false
    private var listenService: ListenService? = null
    // #302: set when we launch our own BlackoutActivity, so the return onResume skips the
    // acknowledge-persistent-alarm step (dismissing the dark screen != acknowledging an alert).
    private var returningFromBlackout = false
    private val connectedIpHistory = mutableListOf<String>() // Most recent first
    @Volatile private var isReconnecting = false
    private var reconnectIndex = 0
    private var reconnectLoopCount = 0  // Track how many times we've looped through all devices
    private var maxReconnectLoops = 0   // 0 = infinite loops (never stop trying saved IPs)
    private var nsdDiscoveryHelper: NsdDiscoveryHelper? = null
    private var isDiscoveryFallbackActive = false
    private var reconnectDelayMs = 5000L  // Per-IP timeout — must exceed connect-timeout (3s) for connect to succeed
    @Volatile private var awaitingConnectionResult = false  // True when waiting for connect/error callback
    @Volatile private var currentReconnectIp: String? = null  // IP currently being tried
    private val reconnectHandler = Handler(Looper.getMainLooper())  // All reconnect callbacks go through this so we can cancel them
    private var prefs: SharedPreferences? = null
    private var autoReconnect = false  // Flag to automatically trigger reconnect on failure
    private var hasTriedAutoReconnect = false  // Prevent multiple auto-reconnect attempts

    private var snapshotUrl: String? = null
    private var hasLoggedSnapshotSuccess = false
    private val snapshotHandler = Handler(Looper.getMainLooper())
    // @Volatile: written on the snapshot executor thread (recomputeSnapshotInterval) and read on the
    // UI thread via isVideoUp() — needs a happens-before edge / atomic 64-bit access.
    @Volatile private var currentSnapshotInterval = 3000L  // Start with medium refresh
    // #325 part 1: rolling auto-glance loop — while watching (foreground or PiP) with base mode AUTO,
    // overlay a near-live glance on the child; lapses on background so it reverts + saves battery.
    // Part 3 derives the child's effective interval from confirmedCadenceMode + this flag (not the wire).
    private var isGlanceLoopRunning = false
    private val glanceInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val minSnapshotInterval = 1500L      // Fast when bright
    private val maxSnapshotInterval = 6000L      // Slow when dark (allows longer exposure)
    private val snapshotIntervalPoorConnectionMs = 10000L  // Slower refresh on poor connection
    private val audioPriorityPauseDelayMs = 320
    private val audioPriorityPauseJitterMs = 130
    private val audioPriorityPauseEntryStreak = 6
    private val audioPriorityPauseExitStreak = 3
    private var isSnapshotLoopRunning = false
    private var videoPausedForAudio = false
    private var audioPriorityPressureStreak = 0
    private var audioPriorityRecoveryStreak = 0
    private var lastImageBrightness = 128
    private var adaptiveVideoEnabled = true
    private val fixedSnapshotIntervalMs = 2500L
    // @Volatile: written on the snapshot executor thread (success/failure paths) and read on the UI
    // thread via isVideoUp() — a live failure streak vetoes "video up" so a dead channel ages out fast.
    @Volatile private var snapshotFailureStreak = 0
    // Reconnect-robustness: timestamp of the last successful snapshot frame. The video channel is an
    // independent proof-of-life for the child IP (see ConnectionStatePolicy); isVideoUp() reads this
    // to decide whether an audio drop should enter the reconnect loop. @Volatile: written on the
    // snapshot fetch thread (HTTP_OK path), read on the UI thread inside postErrorMessage().
    @Volatile private var lastVideoFrameMs = 0L
    private var pendingSnapshotViewportReset = true
    private var lastSnapshotWidth = -1
    private var lastSnapshotHeight = -1
    // #288: the currently-displayed snapshot, held so "Save snapshot" can write it to the gallery.
    @Volatile private var lastSnapshotBitmap: android.graphics.Bitmap? = null
    private var motionDetectionEnabled = false
    private var motionThresholdPercent = 18
    private var lastMotionSignature: IntArray? = null
    private var lastMotionAlertMs = 0L
    private var latestEventType: String? = null
    private var latestEventTimestampMs: Long = -1L

    private lateinit var quickReconnectLabel: TextView
    private lateinit var ipHistoryText: TextView
    private lateinit var reconnectButton: Button
    private lateinit var snapshotImage: ImageView
    // #313: picture-in-picture "glance" — a full-bleed image shown only in PiP, the scroll root we
    // hide behind it, the discoverability chip, and a flag tracking PiP so the poll loop keeps
    // running while the window is visible-but-paused.
    private lateinit var pipPreviewImage: ImageView
    private lateinit var listenScrollRoot: View
    private lateinit var pipButton: TextView
    private var inPipMode = false
    // #313: set when we launch one of our own screens, so the resulting onUserLeaveHint doesn't auto-PiP.
    private var suppressAutoPipOnce = false
    private lateinit var toggleCameraButton: Button
    private lateinit var talkBackButton: Button
    private lateinit var videoPolicyText: TextView
    private lateinit var adaptiveVideoCheckbox: CheckBox
    private lateinit var motionDetectionCheckbox: CheckBox
    private lateinit var motionThresholdSeek: SeekBar
    private lateinit var motionStatusText: TextView

    // Talkback (push-to-talk) state
    // UI owns the current attempt; each worker owns its microphone and audio effects.
    private var talkbackAttempt: de.rochefort.childmonitor.audio.TalkbackConnection? = null
    private var talkbackAudioManager: AudioManager? = null
    private var childAddress: String? = null
    // Stable child deviceId (from QR scan, NSD TXT, or recovered from PairedChildrenStore #278) —
    // used to look up the bearer token and to re-discover the child after a network change without
    // retyping/rescanning an IP. @Volatile for safe publication: written on the main thread but
    // snapshotted into retargetForReconnect()/intent extras consumed by the service thread.
    @Volatile private var childDeviceId: String? = null
        set(value) {
            field = value
            publishLiveEndpoint() // keep the child-controls target in sync with every retarget
        }
    private var childFriendlyName: String? = null
    // #402 Kids: the parent-assigned display label for this session (e.g. "kid1"). DISPLAY-ONLY —
    // shown in the session label + forwarded to the service notification, but NEVER persisted as
    // PairedChild.name (childFriendlyName carries the advertised name for that).
    private var kidDisplayName: String? = null
    // @Volatile: written on toggleRemoteCamera's background thread, read on the UI thread (#325 auto-glance gate, PiP gate).
    @Volatile private var cameraEnabled = true

    /**
     * Locate "\r\n\r\n" inside the first [length] bytes of [buf]. Returns the index of the
     * first byte of the marker, or -1 if not found. Used by the WS upgrade reader so we
     * stop reading as soon as headers are complete, instead of waiting for the buffer to
     * fill / a timeout to fire.
     */
    private fun indexOfCrlfCrlf(buf: ByteArray, length: Int): Int {
        var i = 0
        while (i <= length - 4) {
            if (buf[i] == 0x0D.toByte() && buf[i + 1] == 0x0A.toByte() &&
                buf[i + 2] == 0x0D.toByte() && buf[i + 3] == 0x0A.toByte()) {
                return i
            }
            i++
        }
        return -1
    }

    /**
     * Compute the expected Sec-WebSocket-Accept value per RFC 6455 §4.1:
     *   base64(sha1(clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
     * Used to verify the server completed a real WebSocket handshake and didn't just
     * echo a fake "101 Switching Protocols" status (review-pass-2 #2).
     */
    private fun computeWebSocketAccept(clientKey: String): String {
        val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        val sha1 = java.security.MessageDigest.getInstance("SHA-1")
            .digest((clientKey + magic).toByteArray(Charsets.US_ASCII))
        return android.util.Base64.encodeToString(sha1, android.util.Base64.NO_WRAP)
    }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            // This is called when the connection with the service has been
            // established, giving us the service object we can use to
            // interact with the service.  Because we have bound to a explicit
            // service that we know is running in our own process, we can
            // cast its IBinder to a concrete class and directly access it.
            val bs = (service as ListenBinder).service
            listenService = bs
            Log.i(TAG, "Service connected, setting up callbacks")
            syncRemoteEndpointFromService()

            AppToast.show(this@ListenActivity, R.string.connect,
                    Toast.LENGTH_SHORT)
            // #402 Kids: when this activity was recreated by tapping the notification, the launch
            // intent carries no kid label, but the still-running service does — adopt it so the
            // restored "Connected to" label (and any later cold-start reconnect forward) keeps it.
            if (kidDisplayName.isNullOrBlank()) kidDisplayName = bs.kidDisplayName
            val connectedText = findViewById<TextView>(R.id.connectedTo)
            // #402 Kids: prefer the parent label (e.g. "kid1") over the advertised name.
            connectedText?.text = KidDisplayName.resolve(kidDisplayName, bs.childDeviceName, childDeviceId)
            updateTransportBadge() // #366: show this connection's TLS-vs-cleartext state
            fetchCurrentCadence()  // #325: reflect the child's current snapshot cadence in the selector
            val volumeView = findViewById<VolumeView>(R.id.volume)
            volumeView?.let {
                it.volumeHistory = bs.volumeHistory
            }

            bs.onUpdate = {
                volumeView?.postInvalidate()
                // Update connection quality indicator
                updateConnectionQualityUI()
            }
            bs.onError = {
                Log.i(TAG, "onError callback invoked")
                postErrorMessage()
            }
            bs.onConnected = {
                Log.i(TAG, "onConnected callback invoked")
                syncRemoteEndpointFromService()
                postConnectedMessage()
            }
            bs.onRepairRequired = { postRepairRequiredMessage() }
            if (bs.repairRequired) postRepairRequiredMessage()

            // #359: a fresh bind is never mid-reconnect — drop any stale session mute the previous
            // activity may have left on the surviving foreground service (it's only meant to last for
            // the duration of a manual reconnect). Restores the self-heal the old bind-time pref push
            // used to provide, so transientMute can't get stuck true and silently suppress alerts.
            bs.transientMute = false
            // Reflect the persisted mute / persistent-alarm prefs in the in-context checkboxes. The
            // service reads these fresh from prefs at alert time, so no binder push is needed (they
            // can also be toggled from the advertised SettingsActivity).
            val prefs = getSharedPreferences("ListenPreferences", Context.MODE_PRIVATE)
            findViewById<CheckBox>(R.id.muteAlertsCheckbox)?.isChecked =
                prefs.getBoolean("muteAlerts", false)
            findViewById<CheckBox>(R.id.persistentAlarmCheckbox)?.isChecked =
                prefs.getBoolean("persistentAlarm", false)
        }

        override fun onServiceDisconnected(className: ComponentName) {
            // This is called when the connection with the service has been
            // unexpectedly disconnected -- that is, its process crashed.
            // Because it is running in our same process, we should never
            // see this happen.
            AppToast.show(this@ListenActivity, R.string.disconnected,
                    Toast.LENGTH_SHORT)
        }
    }

    private fun ensureServiceRunningAndBind(bundle: Bundle?) {
        val context: Context = this
        val intent = Intent(context, ListenService::class.java)
        bundle?.let {
            intent.putExtra("name", it.getString("name"))
            // #402 Kids: forward the parent label (held on the activity, so it also survives a
            // cold-start reconnect via this same method) so the FGS notification can show it.
            kidDisplayName?.let { label -> intent.putExtra(EXTRA_KID_DISPLAY_NAME, label) }
            intent.putExtra("address", it.getString("address"))
            intent.putExtra("port", it.getInt("port"))
            it.getString("deviceId")?.let { id ->
                childDeviceId = id
                childFriendlyName = it.getString("name") ?: id
            }
            // #188: forward the deviceId so ListenService can look up the bearer token
            // and use it in the CMHELLO handshake before entering the audio loop.
            childDeviceId?.let { intent.putExtra("deviceId", it) }
            ContextCompat.startForegroundService(context, intent)
        }
        // Attempts to establish a connection with the service.  We use an
        // explicit class name because we want a specific service
        // implementation that we know will be running in our own process
        // (and thus won't be supporting component replacement by other
        // applications).
        if (bindService(intent, connection, BIND_AUTO_CREATE)) {
            this.shouldUnbind = true
            Log.i(TAG, "Bound listen service")
        } else {
            Log.e(TAG, "Error: The requested service doesn't " +
                    "exist, or this client isn't allowed access to it.")
        }
    }

    private fun doUnbindAndStopService(stopService: Boolean = true) {
        if (this.shouldUnbind) {
            // Release information about the service's state.
            unbindService(connection)
            this.shouldUnbind = false
        }
        if (stopService) {
            val context: Context = this
            val intent = Intent(context, ListenService::class.java)
            context.stopService(intent)
        }
    }

    fun postErrorMessage() {
        runOnUiThread {
            // If we're in the middle of reconnection loop, handle it there
            if (isReconnecting && awaitingConnectionResult) {
                Log.i(TAG, "Connection error during reconnect loop - trying next device")
                onReconnectError()
                return@runOnUiThread
            }

            // Reconnect-robustness: AUDIO and VIDEO are independent channels to the child. This error
            // means ONE of them dropped — but if the OTHER is still live, the child IP is known-good,
            // so we must NOT declare ourselves disconnected and enter the saved-IP-sweep / NSD
            // discovery loop (which would also drag the working channel to a churning address). Let
            // the down channel self-heal in place. Only a BOTH-down failure is a real disconnect.
            val audioUp = listenService?.isAudioPlaying == true
            val videoUp = isVideoUp()
            val statusView = findViewById<TextView>(R.id.textStatus)
            when (ConnectionStatePolicy.onChannelDown(audioUp, videoUp)) {
                ConnectionStatePolicy.Action.CONNECTED -> {
                    // Nothing is actually down (both channels live) — ignore the spurious error.
                    Log.i(TAG, "postErrorMessage: both channels up, ignoring")
                    return@runOnUiThread
                }
                ConnectionStatePolicy.Action.RETRY_MISSING_CHANNEL -> {
                    // One channel proves the child is reachable; the down channel retries on its own
                    // (audio: doListen backoff against the same IP; video: snapshot loop keeps polling).
                    statusView?.setText(
                        if (audioUp) R.string.listeningAudioOnly else R.string.listeningVideoOnly
                    )
                    Log.i(TAG, "postErrorMessage: one channel still up (audioUp=$audioUp videoUp=$videoUp)" +
                            " - not reconnecting, letting missing channel self-heal")
                    return@runOnUiThread
                }
                ConnectionStatePolicy.Action.FULL_RECONNECT -> {
                    // Both channels down — fall through to the existing disconnect/reconnect path.
                }
            }

            statusView?.setText(R.string.disconnected)
            Log.i(TAG, "Status updated to: Disconnected")
            pendingSnapshotViewportReset = true

            // If auto-reconnect is enabled and we haven't tried it yet, trigger it
            if (autoReconnect && !hasTriedAutoReconnect && connectedIpHistory.isNotEmpty()) {
                hasTriedAutoReconnect = true
                Log.i(TAG, "Triggering automatic sequential reconnect")
                // Wait a moment before starting reconnect
                statusView?.postDelayed({
                    reconnectToLast()
                }, 2000)
            }
        }
    }

    fun postConnectedMessage() {
        runOnUiThread {
            val status = findViewById<TextView>(R.id.textStatus)
            status?.setText(R.string.listening)
            Log.i(TAG, "Status updated to: Listening")

            // If we're in the middle of reconnection loop, we succeeded!
            if (isReconnecting && awaitingConnectionResult) {
                Log.i(TAG, "Connection successful during reconnect loop")
                onReconnectSuccess()
            } else {
                // #278: the service's own doListen loop can reconnect OUTSIDE the activity's
                // reconnect sweep — e.g. after onConnectionFailure() cleared isReconnecting and
                // kicked off discovery, the saved IP comes back and doListen connects on its own.
                // onReconnectSuccess() would early-return in that state, leaving the one-shot
                // auto-reconnect gate latched (hasTriedAutoReconnect=true while isReconnecting=
                // false), which silently disables auto-reconnect on the NEXT disconnect. Re-arm
                // the gate, clear any stale discovery flag, and restore alerts on every connect.
                hasTriedAutoReconnect = false
                isDiscoveryFallbackActive = false
                restoreAlertPreference()
            }
        }
    }

    private fun postRepairRequiredMessage() {
        runOnUiThread {
            findViewById<TextView>(R.id.textStatus)?.setText(R.string.session_repair_required_short)
            AppToast.show(this, R.string.session_repair_required, Toast.LENGTH_LONG)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // #296: when app-lock is on, keep the live feed out of screenshots and the recents thumbnail
        // (otherwise someone holding the unlocked phone could glimpse the feed via Overview).
        if (de.rochefort.childmonitor.security.AppLock.isEnabled(this)) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        val bundle = this.intent.extras
        // #402 Kids: parent label for the session UI/notification (display-only; absent on
        // QR/Discover/boot/reconnect launches → resolver falls back to the advertised name).
        kidDisplayName = bundle?.getString(EXTRA_KID_DISPLAY_NAME)
        // #278: every reconnect entry point except the QR scan (Quick Reconnect, boot
        // auto-start, NSD discovery relaunch) launches us with only address/port. Without
        // the child's deviceId, ListenService can't look up the bearer token and the child
        // rejects every CMHELLO (reason=format/401), livelocking the reconnect loop with no
        // audio. Recover it from the paired-children store BEFORE binding the service so
        // ensureServiceRunningAndBind forwards it and the token is attached on the first
        // handshake.
        if (childDeviceId == null && bundle?.getString("deviceId").isNullOrBlank()) {
            PairedChildResolver.resolve(
                PairedChildrenStore.list(this),
                bundle?.getString("address")
            )?.let { resolved ->
                childDeviceId = resolved
                Log.i(TAG, "Recovered childDeviceId=$resolved from paired store for tokened reconnect")
            }
        }
        ensureServiceRunningAndBind(bundle)
        this.volumeControlStream = AudioManager.STREAM_MUSIC
        setContentView(R.layout.activity_listen)
        ThemeManager.applyTheme(this)
        val statusText = findViewById<TextView>(R.id.textStatus)
        statusText?.setText(R.string.listening)

        snapshotImage = findViewById(R.id.cameraPreviewImage)
        snapshotImage.scaleType = ImageView.ScaleType.FIT_CENTER
        // #313: PiP glance — bind the overlay/scroll-root/chip and only expose the chip when the
        // device actually supports picture-in-picture.
        pipPreviewImage = findViewById(R.id.pipPreviewImage)
        listenScrollRoot = findViewById(R.id.listenScrollRoot)
        pipButton = findViewById(R.id.pipButton)
        pipButton.visibility = if (isPipSupported()) View.VISIBLE else View.GONE
        pipButton.setOnClickListener { enterPipIfPossible() }
        toggleCameraButton = findViewById(R.id.toggleCameraButton)
        talkBackButton = findViewById(R.id.talkBackButton)
        // #291/#308/#311/#312: visible entry points to the child-control screens (the options menu
        // has no overflow button under the NoActionBar theme, so menu-only was unreachable).
        findViewById<Button>(R.id.childControlsButton)?.setOnClickListener { openChildControls() }
        findViewById<Button>(R.id.childAlertsButton)?.setOnClickListener { openChildAlerts() }
        findViewById<Button>(R.id.sessionToolsButton)?.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                populateSessionMenu(menu)
                setOnMenuItemClickListener { handleSessionMenuItem(it) }
                show()
            }
        }
        videoPolicyText = findViewById(R.id.videoPolicyText)
        adaptiveVideoCheckbox = findViewById(R.id.adaptiveVideoCheckbox)
        motionDetectionCheckbox = findViewById(R.id.motionDetectionCheckbox)
        motionThresholdSeek = findViewById(R.id.motionThresholdSeek)
        motionStatusText = findViewById(R.id.motionStatusText)

        // Initialize SharedPreferences for IP history
        prefs = getSharedPreferences("ListenPreferences", Context.MODE_PRIVATE)
        adaptiveVideoEnabled = prefs?.getBoolean(PREF_ADAPTIVE_VIDEO, DetectionPrefs.DEFAULT_ADAPTIVE_VIDEO) ?: DetectionPrefs.DEFAULT_ADAPTIVE_VIDEO
        motionDetectionEnabled = prefs?.getBoolean(PREF_MOTION_DETECTION, DetectionPrefs.DEFAULT_MOTION_DETECTION) ?: DetectionPrefs.DEFAULT_MOTION_DETECTION
        motionThresholdPercent = prefs?.getInt(PREF_MOTION_THRESHOLD, DetectionPrefs.DEFAULT_MOTION_THRESHOLD) ?: DetectionPrefs.DEFAULT_MOTION_THRESHOLD

        // Initialize reconnect UI elements
        quickReconnectLabel = findViewById(R.id.quickReconnectLabel)
        ipHistoryText = findViewById(R.id.ipHistoryText)
        reconnectButton = findViewById(R.id.reconnectButton)

        // Load IP history
        loadIpHistory()
        updateReconnectUI()

        // Wire up reconnect button
        reconnectButton.setOnClickListener {
            reconnectToLast()
        }

        // #302: enter blackout/monitor mode. Audio + disconnect alerts keep running (they live in
        // the foreground ListenService, independent of this Activity going to the background).
        findViewById<android.widget.Button>(R.id.blackoutButton)?.setOnClickListener {
            // #302: mark this as "we launched blackout ourselves" so the return onResume does NOT
            // auto-acknowledge a disconnect alarm that may fire while the screen is dark.
            returningFromBlackout = true
            startActivity(android.content.Intent(this, BlackoutActivity::class.java))
        }

        // Check if auto-reconnect is enabled
        autoReconnect = bundle?.getBoolean("autoReconnect", false) ?: false
        if (autoReconnect) {
            Log.i(TAG, "Auto-reconnect enabled")
            // #278: bound the saved-IP sweep so the loop escalates to NSD discovery instead
            // of hammering a dead IP forever. With maxReconnectLoops=0 ("infinite") the
            // onConnectionFailure() -> startDiscoveryFallback() branch was unreachable, so a
            // child that moved to a new IP (new DHCP lease / Wi-Fi handoff) could never be
            // re-found. After this many full sweeps we fall back to mDNS (matched by
            // deviceId), then resume the IP loop if discovery turns up nothing — so we still
            // never permanently give up.
            maxReconnectLoops = AUTO_RECONNECT_LOOPS_BEFORE_DISCOVERY
        }

        // Pick up explicit deviceId from intent extras (set by ParentScanActivity or BootService)
        if (childDeviceId == null) {
            intent.getStringExtra("deviceId")?.takeIf { it.isNotBlank() }?.let { childDeviceId = it }
        }

        // Save current connection to history (IP-based legacy + deviceId-based new)
        // #402 Kids: skip when the address is blank (e.g. a paired kid with no last-known IP launched
        // by deviceId) so we never poison ipHistory with a ":port" junk entry — reconnect relies on
        // discovery-by-deviceId in that case.
        bundle?.getString("address")?.takeIf { it.isNotBlank() }?.let { address ->
            val port = bundle.getInt("port", 10000)
            NetworkEndpoint.create(address, port)?.let { saveIpToHistory(it.encoded()) }
            childDeviceId?.let { id ->
                PairedChildrenStore.upsert(
                    this,
                    PairedChild(
                        deviceId = id,
                        name = childFriendlyName ?: bundle.getString("name") ?: id,
                        lastKnownIp = address,
                        lastKnownPort = port,
                        lastSeenMs = System.currentTimeMillis()
                    )
                )
            }
            updateReconnectUI()
            updateRemoteEndpoint(address, source = "onCreateExtras")
        }

        // #402 Kids (Codex P2): a paired kid launched BY deviceId with no stored IP and no saved
        // history has nothing to drive the reconnect loop (the saved-IP sweep needs a non-empty
        // history, and ListenService can't connect to a blank target). Go straight to NSD-by-deviceId
        // so the permanent link still resolves; the strict isPermanentTarget() match keeps it to the
        // right child. The short delay lets the service bind first in the (common) IP-present case.
        if (autoReconnect && !childDeviceId.isNullOrBlank() &&
            bundle?.getString("address").isNullOrBlank() && connectedIpHistory.isEmpty()) {
            Log.i(TAG, "Launched with deviceId=$childDeviceId but no address/history — starting NSD discovery")
            reconnectHandler.postDelayed({
                if (!isFinishing && !isDestroyed) startDiscoveryFallback()
            }, 1500)
        }

        findViewById<Button>(R.id.refreshSnapshotButton)?.setOnClickListener { fetchSnapshotOnce(force = true) }
        findViewById<Button>(R.id.saveSnapshotButton)?.setOnClickListener { saveCurrentSnapshot() }
        findViewById<Button>(R.id.localDisplayButton)?.setOnClickListener {
            val address = childAddress
            if (address.isNullOrBlank() || childDeviceId.isNullOrBlank()) {
                AppToast.show(this, R.string.serviceNotReady, Toast.LENGTH_SHORT)
            } else {
                startActivity(
                    Intent(this, LocalDisplayActivity::class.java)
                        .putExtra(LocalDisplayActivity.EXTRA_ADDRESS, address)
                        .putExtra(LocalDisplayActivity.EXTRA_DEVICE_ID, childDeviceId),
                )
            }
        }
        adaptiveVideoCheckbox.isChecked = adaptiveVideoEnabled
        adaptiveVideoCheckbox.setOnCheckedChangeListener { _, isChecked ->
            adaptiveVideoEnabled = isChecked
            prefs?.edit()?.putBoolean(PREF_ADAPTIVE_VIDEO, isChecked)?.apply()
            recomputeSnapshotInterval()
            updateVideoPolicyUi()
        }
        motionDetectionCheckbox.isChecked = motionDetectionEnabled
        motionDetectionCheckbox.setOnCheckedChangeListener { _, isChecked ->
            motionDetectionEnabled = isChecked
            prefs?.edit()?.putBoolean(PREF_MOTION_DETECTION, isChecked)?.apply()
            if (!isChecked) {
                motionStatusText.text = getString(R.string.motionDetectionOff)
                lastMotionSignature = null
            }
        }
        motionThresholdSeek.max = DetectionPrefs.MOTION_THRESHOLD_MAX
        motionThresholdSeek.progress = motionThresholdPercent
        motionThresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                motionThresholdPercent = DetectionPrefs.clampThreshold(progress)
                prefs?.edit()?.putInt(PREF_MOTION_THRESHOLD, motionThresholdPercent)?.apply()
                if (motionDetectionEnabled) {
                    motionStatusText.text = getString(R.string.settingMotionThreshold, motionThresholdPercent)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        motionStatusText.text = if (motionDetectionEnabled) {
            getString(R.string.settingMotionThreshold, motionThresholdPercent)
        } else {
            getString(R.string.motionDetectionOff)
        }
        recomputeSnapshotInterval()
        updateVideoPolicyUi()
        startSnapshotLoop()

        // Camera preview click for fullscreen
        snapshotImage.setOnClickListener {
            openFullscreenCamera()
        }

        // Camera toggle button
        toggleCameraButton.setOnClickListener {
            toggleRemoteCamera()
        }

        // #325: snapshot cadence selector (sets the child's encode rate via /api/snapshot/cadence).
        findViewById<Button>(R.id.cadenceEcoButton)?.setOnClickListener { setSnapshotCadence("ECO") }
        findViewById<Button>(R.id.cadenceBalancedButton)?.setOnClickListener { setSnapshotCadence("BALANCED") }
        findViewById<Button>(R.id.cadenceNearLiveButton)?.setOnClickListener { setSnapshotCadence("NEAR_LIVE") }
        findViewById<Button>(R.id.cadenceAutoButton)?.setOnClickListener { setSnapshotCadence("AUTO") }

        // Talk-back button (push-to-talk)
        setupTalkBackButton()

        // Setup mute checkbox listener
        val muteCheckbox = findViewById<CheckBox>(R.id.muteAlertsCheckbox)
        if (muteCheckbox != null) {
            Log.i(TAG, "Mute checkbox found, setting up listener")
            muteCheckbox.setOnCheckedChangeListener { _, isChecked ->
                // #359: persist only — the service reads "muteAlerts" fresh from prefs at alert time.
                getSharedPreferences("ListenPreferences", Context.MODE_PRIVATE)
                    .edit().putBoolean("muteAlerts", isChecked).apply()
                Log.i(TAG, "Alert sounds ${if (isChecked) "muted" else "unmuted"}")
            }
        } else {
            Log.e(TAG, "ERROR: Mute checkbox not found in layout!")
        }

        // #290: persistent-alarm checkbox — loud, looping disconnect alarm until acknowledged.
        findViewById<CheckBox>(R.id.persistentAlarmCheckbox)?.setOnCheckedChangeListener { _, isChecked ->
            // #359: persist only — the service reads "persistentAlarm" fresh from prefs at alert time.
            getSharedPreferences("ListenPreferences", Context.MODE_PRIVATE)
                .edit().putBoolean("persistentAlarm", isChecked).apply()
            // Stop an already-looping alarm immediately when the user turns it off here.
            if (!isChecked) listenService?.stopPersistentAlarm()
            Log.i(TAG, "Persistent alarm ${if (isChecked) "enabled" else "disabled"}")
        }

        // #338 (part of #284): replay the last ~30s of audio — useful right after an alert to hear
        // what just happened. GETs the child's /api/replay/audio ring buffer and plays it through
        // the service's audio path (which suppresses live output for the clip's duration).
        findViewById<Button>(R.id.replayAudioButton)?.setOnClickListener { replayRecentAudio() }

        // #338: snapshot replay scrubber controls (revealed by replay when the child has buffered
        // frames). Dragging the SeekBar fetches the frame at that timestamp; "● Live" exits.
        findViewById<SeekBar>(R.id.replayScrubSeek)?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || !scrubActive) return
                ReplayScrubPolicy.timestampAt(progress, scrubTimestamps)?.let { fetchScrubFrameAsync(it) }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        findViewById<Button>(R.id.replayLiveButton)?.setOnClickListener { exitScrubMode() }

        // Check battery optimization settings and prompt if needed
        BatteryOptimizationHelper.checkAndPromptIfNeeded(this)

        handleVideoUxIntent(intent)
    }

    public override fun onDestroy() {
        stopTalkBack()

        // Cancel any in-progress reconnection loop first
        isReconnecting = false
        awaitingConnectionResult = false
        currentReconnectIp = null
        // Remove all pending Handler callbacks (snapshot loop, reconnect timeouts, discovery restarts)
        snapshotHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)

        // Null out service callbacks so the kept-alive service doesn't fire into this dead activity.
        // Without this, lambdas capturing `this` pin the whole view tree until the next bind.
        listenService?.let { svc ->
            svc.onError = null
            svc.onUpdate = null
            svc.onConnected = null
            svc.onRepairRequired = null
            // #338: stop any in-progress replay — the user can no longer control it, and its
            // onFinished lambda captures this activity (same view-tree-pinning hazard as above).
            svc.stopReplay()
        }

        // Keep the foreground listen service running when the activity is destroyed
        // by background/task transitions so parent stays connected on return.
        doUnbindAndStopService(stopService = false)
        stopSnapshotLoop()
        stopDiscoveryFallback()
        // #194-12: cancel any in-flight snapshot work and let the single thread die.
        try { snapshotFetchExecutor.shutdownNow() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // #313: back in the foreground — clear any stale self-navigation suppression.
        suppressAutoPipOnce = false
        // #362: the volume graph is a Canvas view ThemeManager's recolor doesn't reach — re-read the
        // high-contrast setting on return (e.g. after toggling it in Settings) so it recolours live.
        findViewById<VolumeView>(R.id.volume)?.refreshHighContrast()
        // #338: don't resume the live loop underneath an active scrub — it would spin a no-op timer
        // (fetchSnapshotOnce bails on scrubActive) and leave the live/scrub state inconsistent.
        // exitScrubMode() restarts the loop when the user returns to live.
        if (!isSnapshotLoopRunning && !scrubActive) {
            startSnapshotLoop()
        }
        // #366: re-read the transport state after a possible re-pair round-trip (the badge is
        // otherwise only set at bind time), so a re-pair that changes pin state can't leave a stale chip.
        updateTransportBadge()
        // #290: opening the parent screen acknowledges (silences + suppresses) a persistent
        // disconnect alarm until the child reconnects.
        // #302: BUT returning from our own blackout/monitor mode is NOT "I've seen the alert" —
        // dismissing a dark screen must not silence + SUPPRESS a disconnect alarm that fired during
        // blackout (that would defeat the alert for a sleeping parent). Skip the ack on that path so
        // the alarm + disconnect UI are still right there when they come back.
        if (returningFromBlackout) {
            returningFromBlackout = false
        } else {
            listenService?.acknowledgePersistentAlarm()
        }
    }

    override fun onPause() {
        stopTalkBack()
        // #313: a PiP window is visible-but-paused — keep the snapshot loop running so it doesn't
        // freeze. The loop is stopped when the window is actually dismissed (onStop) / destroyed.
        if (!isCurrentlyInPip()) stopSnapshotLoop()
        super.onPause()
    }

    override fun onStop() {
        // #313: reached when the PiP window is dismissed (or the activity otherwise stops). onPause
        // deliberately left the loop running for PiP; stop it now.
        inPipMode = false
        stopSnapshotLoop()
        super.onStop()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // #313: onUserLeaveHint fires for BOTH leaving the app (Home/Recents) AND our own
        // navigations (fullscreen, blackout, Tools menu) — those call startActivity, which sets
        // suppressAutoPipOnce. Only the genuine "left the app" case (no self-launch) auto-enters PiP.
        if (suppressAutoPipOnce) {
            suppressAutoPipOnce = false
            return
        }
        enterPipIfPossible()
    }

    // #313: any activity WE launch is internal navigation, not the user leaving the app — flag it so
    // the onUserLeaveHint it triggers doesn't pop us into PiP over our own next screen. A real
    // Home/Recents press never routes through here, so it still auto-enters PiP.
    override fun startActivity(intent: Intent) {
        suppressAutoPipOnce = true
        super.startActivity(intent)
    }

    override fun startActivity(intent: Intent, options: Bundle?) {
        suppressAutoPipOnce = true
        super.startActivity(intent, options)
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        inPipMode = isInPip
        applyPipSecureFlag(isInPip)
        if (isInPip) {
            // Show only the live frame; hide the rest of the screen behind the PiP surface.
            listenScrollRoot.visibility = View.GONE
            pipPreviewImage.visibility = View.VISIBLE
            lastSnapshotBitmap?.let { pipPreviewImage.setImageBitmap(it) }
            if (!isSnapshotLoopRunning && !scrubActive) startSnapshotLoop()
        } else if (!isFinishing) {
            // Expanded back to fullscreen (not a dismissal, where restoring the UI is pointless work).
            pipPreviewImage.visibility = View.GONE
            pipPreviewImage.setImageDrawable(null)
            listenScrollRoot.visibility = View.VISIBLE
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVideoUxIntent(intent)
    }

    /**
     * #313 (security review): while in PiP, keep the child's frame out of the recents thumbnail and
     * screenshots even when app-lock (#296) is off — PiP makes those captures far more likely, and a
     * persisted frame of a child's room is the sensitive bit. FLAG_SECURE does NOT stop the PiP
     * window itself from rendering. On exit, restore the app-lock policy (clear only if it didn't set it).
     */
    private fun applyPipSecureFlag(inPip: Boolean) {
        val secure = android.view.WindowManager.LayoutParams.FLAG_SECURE
        if (inPip) {
            window.addFlags(secure)
        } else if (!de.rochefort.childmonitor.security.AppLock.isEnabled(this)) {
            window.clearFlags(secure)
        }
    }

    /** #313: device + OS support PiP (API 26+ with the picture-in-picture feature). */
    private fun isPipSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** #313: true while a PiP window is showing (own flag, double-checked against the framework). */
    private fun isCurrentlyInPip(): Boolean =
        inPipMode || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode)

    /**
     * #313: shrink into a picture-in-picture glance window if it's possible right now (PiP supported,
     * camera on, a frame already in hand, not scrubbing). Best-effort: a thrown PiP request is logged
     * and ignored so it can never crash the session.
     */
    private fun enterPipIfPossible() {
        if (isFinishing || isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val canEnter = PipPolicy.canEnter(
            hasPipFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE),
            sdkSupportsPip = true,
            cameraEnabled = cameraEnabled,
            hasFrame = lastSnapshotBitmap != null,
            scrubActive = scrubActive,
        )
        if (!canEnter) return
        try {
            // Set before requesting PiP: onPause can be dispatched before onPictureInPictureModeChanged
            // (notably when entering from the chip while focused), and its loop-keep-alive guard reads
            // this. Reset on failure so a refused request doesn't strand the flag.
            inPipMode = true
            enterPictureInPictureMode(buildPipParams())
        } catch (e: Exception) {
            inPipMode = false
            Log.w(TAG, "Enter PiP failed: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun buildPipParams(): PictureInPictureParams {
        val bmp = lastSnapshotBitmap
        val (num, den) = PipPolicy.clampAspect(bmp?.width ?: 0, bmp?.height ?: 0)
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(num, den))
            .build()
    }

    @Deprecated("Android legacy back callback retained for API 21 compatibility")
    override fun onBackPressed() {
        // Back should return to main screen without stopping active parent session.
        doUnbindAndStopService(stopService = false)
        val intent = Intent(this, StartActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun stopDiscoveryFallback() {
        isDiscoveryFallbackActive = false
        nsdDiscoveryHelper?.stopDiscovery()
        nsdDiscoveryHelper = null
    }

    /**
     * Reconnect-robustness: whether the video channel is currently live, i.e. we received a snapshot
     * frame "recently" — within ~2 poll intervals (floored at 8s for slow/dark cadences). A live
     * video channel proves the child IP is good, so an audio-only drop must NOT enter the reconnect
     * loop (see ConnectionStatePolicy / postErrorMessage).
     */
    private fun isVideoUp(): Boolean = VideoLivenessPolicy.isUp(
        lastFrameMs = lastVideoFrameMs,
        nowMs = System.currentTimeMillis(),
        snapshotIntervalMs = currentSnapshotInterval,
        failureStreak = snapshotFailureStreak,
    )

    private fun startSnapshotLoop() {
        if (isSnapshotLoopRunning) return
        isSnapshotLoopRunning = true
        snapshotHandler.post(snapshotRunnable)
        startGlanceLoop() // #325: keep the auto-glance loop in lockstep with the live loop
    }

    private fun stopSnapshotLoop() {
        isSnapshotLoopRunning = false
        snapshotHandler.removeCallbacks(snapshotRunnable)
        stopGlanceLoop() // #325: stopping the live loop lets the last glance lapse → child reverts
    }

    private val snapshotRunnable = object : Runnable {
        override fun run() {
            fetchSnapshotOnce()

            if (isSnapshotLoopRunning) {
                snapshotHandler.postDelayed(this, currentSnapshotInterval)
            }
        }
    }

    // #325 part 1: auto-glance loop. Mirrors the snapshot loop's start/stop sites (so it runs while
    // foreground AND in PiP, and stops on true background). Each tick, if the base mode is AUTO and
    // we're watching, POSTs a rolling near-live glance; the response's effectiveIntervalMs also feeds
    // the part-3 poll alignment. Stopping the loop lets the last glance lapse so the child reverts.
    private val glanceRunnable = object : Runnable {
        override fun run() {
            maybeSendGlance()
            if (isGlanceLoopRunning) {
                snapshotHandler.postDelayed(this, SnapshotSessionPolicy.GLANCE_PING_INTERVAL_MS)
            }
        }
    }

    private fun startGlanceLoop() {
        if (isGlanceLoopRunning) return
        isGlanceLoopRunning = true
        snapshotHandler.post(glanceRunnable) // fire the first glance immediately
    }

    private fun stopGlanceLoop() {
        isGlanceLoopRunning = false
        snapshotHandler.removeCallbacks(glanceRunnable)
    }

    /** #325: link is clean enough to drive near-live (mirrors the part-3 alignment's linkHealthy). */
    private fun isLinkHealthy(): Boolean {
        val q = listenService?.connectionQuality ?: ListenService.ConnectionQuality.UNKNOWN
        return q == ListenService.ConnectionQuality.EXCELLENT || q == ListenService.ConnectionQuality.GOOD
    }

    private fun maybeSendGlance() {
        if (!SnapshotSessionPolicy.shouldAutoGlance(
                confirmedCadenceMode, cameraEnabled, scrubActive, videoPausedForAudio, isLinkHealthy())) {
            return
        }
        // Switch the poll to near-live promptly (the interval is derived from glance state, not the
        // response), then fire the rolling glance fire-and-forget.
        recomputeSnapshotInterval()
        updateVideoPolicyUi()
        if (!glanceInFlight.compareAndSet(false, true)) return
        Thread {
            try {
                postControlForJson("/api/snapshot/cadence?glanceMs=${SnapshotSessionPolicy.GLANCE_MS}")
            } finally {
                glanceInFlight.set(false)
            }
        }.apply { isDaemon = true; name = "auto-glance" }.start()
    }

    /**
     * #325: pinned + bearer control POST (empty body) that returns the parsed JSON on HTTP 200, else
     * null (draining errorStream so the socket is reusable). Blocking — call off the main thread.
     */
    private fun postControlForJson(path: String): JSONObject? {
        val address = childAddress ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = openPinnedConnection(buildControlUrl(address, path))
            ParentSessionStore.get(this@ListenActivity, childDeviceId)?.let { tok ->
                connection.setRequestProperty("Authorization", "Bearer $tok")
            }
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "POST" // #190 CSRF: state-changing endpoint
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                JSONObject(connection.inputStream.bufferedReader().readText())
            } else {
                try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Control POST $path failed: ${e.message}")
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun fetchSnapshotOnce(force: Boolean = false) {
        // #338: while scrubbing replay frames the live view is frozen — never let a live fetch
        // (periodic loop, an onResume restart, or a manual refresh) overwrite the scrubbed frame.
        if (scrubActive) return
        if (!force) {
            recomputeSnapshotInterval()
            if (videoPausedForAudio) {
                return
            }
        }

        val url = snapshotUrl ?: run {
            syncRemoteEndpointFromService()
            snapshotUrl
        } ?: return
        // #194-12: avoid spawning a new java.lang.Thread for every snapshot
        // (formerly: every 1.5-10s when the screen is on, up to 40 short-lived
        // threads per minute on EXCELLENT links). Submit to a single-threaded
        // pool and drop if a previous fetch is still in flight — duplicate
        // queued fetches just waste bandwidth on a strict polling loop.
        if (!snapshotFetchInFlight.compareAndSet(false, true)) return
        snapshotFetchExecutor.execute {
            try { snapshotFetchBody(url) } finally { snapshotFetchInFlight.set(false) }
        }
    }

    private val snapshotFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val snapshotFetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ListenActivity-snapshot").apply { isDaemon = true }
    }

    private fun snapshotFetchBody(url: String) {
        val requestDeviceId = childDeviceId
        val requestToken = ParentSessionStore.get(this, requestDeviceId)
        var fetchConnection: HttpURLConnection? = null
        try {
            val connection = openPinnedConnection(url).also { fetchConnection = it }
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doInput = true
            // #193: present the paired bearer token if we have one. The child's
            // WebControlServer's /snapshot rejects unauthenticated callers with 401.
            requestToken?.let { tok ->
                connection.setRequestProperty("Authorization", "Bearer $tok")
            }
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                // Recover this request's session without erasing a newer bearer. Drain
                // the error body before retrying so the connection can be released.
                try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                de.rochefort.childmonitor.parent.SessionRenewer.recoverRejectedBearer(
                    applicationContext, requestDeviceId, java.net.URL(url).host, requestToken,
                )
                snapshotFailureStreak++
                recomputeSnapshotInterval()
                return
            }
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val hadRecentFailures = snapshotFailureStreak >= SNAPSHOT_RECOVERY_RESET_THRESHOLD
                snapshotFailureStreak = 0
                // A 200 from the child proves the video channel — and thus the child IP — is live.
                // isVideoUp() reads this so an audio-only drop won't trigger a full reconnect.
                lastVideoFrameMs = System.currentTimeMillis()
                connection.inputStream.use { stream: InputStream ->
                    // RGB_565 halves bitmap memory vs the default ARGB_8888 (2 B/px vs
                    // 4 B/px → ~600 kB instead of ~1.2 MB for a 640×480 snapshot) and
                    // is faster to decode. Visually indistinguishable from ARGB_8888
                    // for natural-photo snapshots; alpha is irrelevant since JPEG has
                    // no transparency anyway. With polling at 1.5-10 s, this cuts the
                    // per-fetch bitmap-allocation churn in half.
                    // #208: bound bytes + dimensions so a hostile host can't OOM the
                    // parent with an oversized image.
                    val bmp = BoundedBitmap.decodeBoundedStream(
                        stream,
                        maxBytes = BoundedBitmap.MAX_SNAPSHOT_BYTES,
                        maxDimension = BoundedBitmap.MAX_SNAPSHOT_DIMENSION,
                        config = android.graphics.Bitmap.Config.RGB_565
                    )
                    if (bmp != null) {
                        val frameSizeChanged = bmp.width != lastSnapshotWidth || bmp.height != lastSnapshotHeight
                        if (frameSizeChanged) {
                            lastSnapshotWidth = bmp.width
                            lastSnapshotHeight = bmp.height
                        }
                        if (!hasLoggedSnapshotSuccess) {
                            hasLoggedSnapshotSuccess = true
                            Log.i(TAG, "Snapshot frame received successfully from $url")
                        }
                        // Calculate brightness and adjust refresh rate
                        val brightness = calculateImageBrightness(bmp)
                        adjustSnapshotRefreshRate(brightness)
                        detectMotionFromSnapshot(bmp)

                        snapshotImage.post {
                            // #338: a live fetch already in flight when scrub mode began must not
                            // overwrite the scrubbed frame.
                            if (scrubActive) return@post
                            if (pendingSnapshotViewportReset || frameSizeChanged || hadRecentFailures) {
                                resetInlineVideoViewport()
                                pendingSnapshotViewportReset = false
                            }
                            snapshotImage.setImageBitmap(bmp)
                            lastSnapshotBitmap = bmp // #288: latest frame, for save-to-gallery
                            if (inPipMode) pipPreviewImage.setImageBitmap(bmp) // #313: keep PiP live
                        }
                    }
                }
            } else {
                snapshotFailureStreak++
                if (snapshotFailureStreak >= SNAPSHOT_RECOVERY_RESET_THRESHOLD) {
                    pendingSnapshotViewportReset = true
                }
                recomputeSnapshotInterval()
            }
        } catch (e: Exception) {
            snapshotFailureStreak++
            if (snapshotFailureStreak >= SNAPSHOT_RECOVERY_RESET_THRESHOLD) {
                pendingSnapshotViewportReset = true
            }
            recomputeSnapshotInterval()
            Log.w(TAG, "Snapshot fetch failed: ${e.message}")
        } finally {
            try { fetchConnection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun resetInlineVideoViewport() {
        snapshotImage.scaleType = ImageView.ScaleType.FIT_CENTER
        snapshotImage.translationX = 0f
        snapshotImage.translationY = 0f
        snapshotImage.rotation = 0f
        snapshotImage.scrollTo(0, 0)
        snapshotImage.requestLayout()
    }

    /**
     * #288: save the currently-displayed snapshot to the device gallery (Pictures/ChildMonitor) —
     * "what you see is what you save", local-only. API 29+ needs no permission; pre-29 needs
     * WRITE_EXTERNAL_STORAGE (request it, then re-save on grant).
     */
    private fun saveCurrentSnapshot() {
        val bmp = lastSnapshotBitmap
        if (bmp == null) {
            AppToast.show(this, R.string.saveSnapshotNone, Toast.LENGTH_SHORT)
            return
        }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_SAVE_SNAPSHOT
            )
            return
        }
        val ts = System.currentTimeMillis()
        Thread {
            val ok = GallerySaver.saveJpeg(applicationContext, bmp, ts)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                AppToast.show(
                    this,
                    if (ok) R.string.saveSnapshotOk else R.string.saveSnapshotFailed,
                    Toast.LENGTH_SHORT
                )
            }
        }.apply { isDaemon = true; name = "save-snapshot" }.start()
    }

    /**
     * Calculate average brightness of a bitmap (0-255)
     * Samples pixels for performance
     */
    private fun calculateImageBrightness(bitmap: android.graphics.Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        var totalBrightness = 0L
        var sampleCount = 0

        // Sample every 10th pixel for performance
        val step = 10
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                // Use luminance formula
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                totalBrightness += luminance
                sampleCount++
            }
        }

        return if (sampleCount > 0) (totalBrightness / sampleCount).toInt() else 128
    }

    private fun detectMotionFromSnapshot(bitmap: android.graphics.Bitmap) {
        if (!motionDetectionEnabled) return
        val signature = buildLumaSignature(bitmap)
        val previous = lastMotionSignature
        lastMotionSignature = signature
        if (previous == null || previous.size != signature.size) return

        var changedBuckets = 0
        for (i in signature.indices) {
            if (kotlin.math.abs(signature[i] - previous[i]) >= 18) {
                changedBuckets++
            }
        }
        val confidencePercent = (changedBuckets * 100) / signature.size
        runOnUiThread {
            motionStatusText.text = getString(
                R.string.motionConfidence,
                confidencePercent,
                motionThresholdPercent,
            )
        }
        // #283: feed the per-snapshot motion level into the night timeline (max per minute).
        listenService?.nightRecorder?.onMotion(confidencePercent)
        if (confidencePercent < motionThresholdPercent) return

        val now = System.currentTimeMillis()
        if (now - lastMotionAlertMs < MOTION_ALERT_COOLDOWN_MS) return
        lastMotionAlertMs = now
        latestEventType = "motion"
        latestEventTimestampMs = now
        runOnUiThread {
            AppToast.show(this, getString(R.string.motionDetectedPercent, confidencePercent), Toast.LENGTH_SHORT)
        }
    }

    private fun buildLumaSignature(bitmap: android.graphics.Bitmap): IntArray {
        val gridX = 12
        val gridY = 8
        val signature = IntArray(gridX * gridY)
        val cellW = (bitmap.width / gridX).coerceAtLeast(1)
        val cellH = (bitmap.height / gridY).coerceAtLeast(1)

        var idx = 0
        for (gy in 0 until gridY) {
            for (gx in 0 until gridX) {
                val px = (gx * cellW).coerceAtMost(bitmap.width - 1)
                val py = (gy * cellH).coerceAtMost(bitmap.height - 1)
                val pixel = bitmap.getPixel(px, py)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                signature[idx++] = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            }
        }
        return signature
    }

    /**
     * Adjust refresh rate based on brightness
     * Dark = slower refresh (allows camera longer exposure)
     * Bright = faster refresh
     */
    private fun adjustSnapshotRefreshRate(brightness: Int) {
        lastImageBrightness = brightness
        recomputeSnapshotInterval()
    }

    private fun recomputeSnapshotInterval() {
        val baseFromBrightness = when {
            lastImageBrightness < 50 -> maxSnapshotInterval
            lastImageBrightness < 100 -> 4000L
            lastImageBrightness < 150 -> 2500L
            else -> minSnapshotInterval
        }
        val service = listenService
        val quality = service?.connectionQuality ?: ListenService.ConnectionQuality.UNKNOWN
        val packetDelayMs = service?.packetDelayMs ?: 0
        val packetJitterMs = service?.packetJitterMs ?: 0

        val qualityPenalty = when (quality) {
            ListenService.ConnectionQuality.EXCELLENT -> 0L
            ListenService.ConnectionQuality.GOOD -> 400L
            ListenService.ConnectionQuality.FAIR -> 1400L
            ListenService.ConnectionQuality.POOR -> snapshotIntervalPoorConnectionMs - baseFromBrightness
            ListenService.ConnectionQuality.UNKNOWN -> 800L
        }
        val latencyPenalty = when {
            packetDelayMs >= 300 -> 2200L
            packetDelayMs >= 200 -> 1400L
            packetDelayMs >= 120 -> 700L
            packetDelayMs >= 80 -> 300L
            else -> 0L
        }
        val jitterPenalty = when {
            packetJitterMs >= 90 -> 900L
            packetJitterMs >= 60 -> 450L
            packetJitterMs >= 35 -> 200L
            else -> 0L
        }
        val failurePenalty = (snapshotFailureStreak.coerceAtMost(6) * 500L)
        val adaptiveInterval = (baseFromBrightness + qualityPenalty + latencyPenalty + jitterPenalty + failurePenalty)
            .coerceIn(minSnapshotInterval, snapshotIntervalPoorConnectionMs)

        val severeConstraint = service?.isAudioPlaying == true &&
            quality == ListenService.ConnectionQuality.POOR &&
            (
                packetDelayMs >= audioPriorityPauseDelayMs ||
                packetJitterMs >= audioPriorityPauseJitterMs ||
                snapshotFailureStreak >= 5
            )

        if (severeConstraint) {
            audioPriorityPressureStreak++
            audioPriorityRecoveryStreak = 0
        } else {
            audioPriorityRecoveryStreak++
            audioPriorityPressureStreak = 0
        }

        val shouldPauseForAudio = if (videoPausedForAudio) {
            severeConstraint || audioPriorityRecoveryStreak < audioPriorityPauseExitStreak
        } else {
            audioPriorityPressureStreak >= audioPriorityPauseEntryStreak
        }
        if (videoPausedForAudio != shouldPauseForAudio) {
            videoPausedForAudio = shouldPauseForAudio
            Log.i(
                TAG,
                if (shouldPauseForAudio) {
                    "Video paused as audio-first last resort (quality=$quality, delay=${packetDelayMs}ms, jitter=${packetJitterMs}ms, failures=$snapshotFailureStreak)"
                } else {
                    "Video resumed after audio-first fallback (quality=$quality, delay=${packetDelayMs}ms, jitter=${packetJitterMs}ms)"
                }
            )
        }

        // #325 parts 1+3: derive the child's effective capture rate from its base mode + whether we're
        // auto-glancing (NOT a transient wire value), then align the poll to it so we don't re-fetch a
        // slow cadence or lag a near-live one. Only when adaptive is on (fixed honours the user's pick).
        val linkHealthy = quality == ListenService.ConnectionQuality.EXCELLENT ||
            quality == ListenService.ConnectionQuality.GOOD
        val autoGlancing = isGlanceLoopRunning && SnapshotSessionPolicy.shouldAutoGlance(
            confirmedCadenceMode, cameraEnabled, scrubActive, videoPausedForAudio, linkHealthy)
        val childEffective = SnapshotSessionPolicy.childEffectiveIntervalMs(confirmedCadenceMode, autoGlancing)
        val aligned = SnapshotSessionPolicy.alignedPollIntervalMs(adaptiveInterval, childEffective, linkHealthy)
        currentSnapshotInterval = if (adaptiveVideoEnabled) aligned else fixedSnapshotIntervalMs
    }

    private fun updateVideoPolicyUi() {
        val service = listenService
        val quality = service?.connectionQuality ?: ListenService.ConnectionQuality.UNKNOWN
        val packetDelayMs = service?.packetDelayMs ?: 0
        val mode = getString(if (adaptiveVideoEnabled) R.string.videoModeAdaptive else R.string.videoModeFixed)
        val qualityHint = when (quality) {
            ListenService.ConnectionQuality.EXCELLENT -> getString(R.string.linkExcellent)
            ListenService.ConnectionQuality.GOOD -> getString(R.string.linkGood)
            ListenService.ConnectionQuality.FAIR -> getString(R.string.linkFair)
            ListenService.ConnectionQuality.POOR -> getString(R.string.linkPoor)
            ListenService.ConnectionQuality.UNKNOWN -> getString(R.string.linkWarmingUp)
        }
        val pauseHint = if (videoPausedForAudio) getString(R.string.videoPolicyAudioFirst) else ""
        val latencyHint = if (adaptiveVideoEnabled && packetDelayMs > 0) {
            getString(R.string.videoPolicyDelay, packetDelayMs)
        } else ""
        // #325 part 1: surface the near-live boost when the aligned poll is running fast.
        val glanceHint = if (currentSnapshotInterval <= 500L) getString(R.string.videoPolicyNearLive) else ""
        videoPolicyText.text = getString(
            R.string.videoPolicySummary,
            mode,
            currentSnapshotInterval,
            qualityHint,
            latencyHint,
            glanceHint,
            pauseHint,
        )
    }

    private fun handleVideoUxIntent(intent: Intent?) {
        val i = intent ?: return
        val intentAddress = i.getStringExtra("address")
        if (!intentAddress.isNullOrBlank()) {
            updateRemoteEndpoint(intentAddress, source = "intent")
        }
        val eventType = i.getStringExtra(EXTRA_EVENT_TYPE)
        val eventTimestamp = i.getLongExtra(EXTRA_EVENT_TIMESTAMP_MS, -1L)
        val hasEventContext = !eventType.isNullOrBlank() && eventTimestamp > 0
        if (hasEventContext) {
            latestEventType = eventType
            latestEventTimestampMs = eventTimestamp
            val whenText = android.text.format.DateFormat.format("HH:mm:ss", eventTimestamp)
            AppToast.show(this, "Jumped from $eventType event at $whenText", Toast.LENGTH_SHORT)
        }
        if (i.getBooleanExtra(EXTRA_OPEN_FULLSCREEN, false)) {
            snapshotImage.post { openFullscreenCamera() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        populateSessionMenu(menu)
        return true
    }

    /** Shared by the legacy platform options menu and the visible #450 popup affordance. */
    private fun populateSessionMenu(menu: Menu) {
        menu.clear()
        // #311/#312: in-app remote controls for the connected child (night light + soother), so these
        // aren't web-console-only. Listed first as a comfort feature parents reach for during a session.
        menu.add(0, MENU_CHILD_CONTROLS, 0, getString(R.string.childControlsOpen)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        // #291/#308: parent view of the child's alert config + webhooks (web-console-only before).
        menu.add(0, MENU_CHILD_ALERTS, 0, getString(R.string.childAlertsOpen)).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_DASHBOARD, 0, "Dashboard").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_SETUP_WIZARD, 0, "Setup Wizard").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_PAIRING, 0, "Pairing").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_AUDIO_CALIBRATION, 0, "Audio Calibration").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_DIAGNOSTICS, 0, "Diagnostics").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_TIMELINE, 0, "Timeline").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_VIEW_LOGS, 0, "View Diagnostic Logs").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        menu.add(0, MENU_BATTERY_SETTINGS, 0, "Battery Settings").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (handleSessionMenuItem(item)) true else super.onOptionsItemSelected(item)
    }

    private fun handleSessionMenuItem(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CHILD_CONTROLS -> { openChildControls(); true }
            MENU_CHILD_ALERTS -> { openChildAlerts(); true }
            MENU_DASHBOARD -> {
                startActivity(Intent(this, DashboardActivity::class.java))
                true
            }
            MENU_SETUP_WIZARD -> {
                startActivity(Intent(this, SetupWizardActivity::class.java))
                true
            }
            MENU_PAIRING -> {
                startActivity(Intent(this, PairingActivity::class.java))
                true
            }
            MENU_AUDIO_CALIBRATION -> {
                startActivity(Intent(this, AudioCalibrationActivity::class.java))
                true
            }
            MENU_DIAGNOSTICS -> {
                startActivity(Intent(this, DiagnosticsActivity::class.java))
                true
            }
            MENU_TIMELINE -> {
                startActivity(Intent(this, TimelineActivity::class.java))
                true
            }
            MENU_VIEW_LOGS -> {
                startActivity(Intent(this, LogViewerActivity::class.java))
                true
            }
            MENU_BATTERY_SETTINGS -> {
                BatteryOptimizationHelper.checkAndPromptIfNeeded(this)
                true
            }
            else -> false
        }
    }

    /** Open the night-light / soother controls for the connected child (#311/#312). Shared by the
     *  on-screen button and the options-menu item; toasts if there's no live connection yet. */
    private fun openChildControls() {
        val addr = childAddress
        if (addr.isNullOrBlank()) {
            AppToast.show(this, getString(R.string.childControlsNeedConnection), Toast.LENGTH_SHORT)
            return
        }
        startActivity(Intent(this, ChildControlsActivity::class.java).apply {
            putExtra(ChildControlsActivity.EXTRA_ADDRESS, addr)
            putExtra(ChildControlsActivity.EXTRA_DEVICE_ID, childDeviceId)
        })
    }

    /** Open the alert-config / webhooks screen for the connected child (#291/#308). */
    private fun openChildAlerts() {
        val addr = childAddress
        if (addr.isNullOrBlank()) {
            AppToast.show(this, getString(R.string.childControlsNeedConnection), Toast.LENGTH_SHORT)
            return
        }
        startActivity(Intent(this, ChildAlertsActivity::class.java).apply {
            putExtra(ChildAlertsActivity.EXTRA_ADDRESS, addr)
            putExtra(ChildAlertsActivity.EXTRA_DEVICE_ID, childDeviceId)
        })
    }

    private fun loadIpHistory() {
        val historyString = prefs?.getString("ipHistory", "") ?: ""
        connectedIpHistory.clear()
        if (historyString.isNotEmpty()) {
            // Validate and canonicalize in one place. This also migrates the old unbracketed IPv6
            // form (`fd00::1:10000`) to `[fd00::1]:10000`, which can be parsed unambiguously later.
            connectedIpHistory.addAll(
                historyString.split(",")
                    .mapNotNull { NetworkEndpoint.parse(it, defaultPort = 10000)?.encoded() }
                    .distinct()
            )
            val normalized = connectedIpHistory.joinToString(",")
            if (normalized != historyString) prefs?.edit()?.putString("ipHistory", normalized)?.apply()
        }
        Log.i(TAG, "Loaded IP history: ${connectedIpHistory.size} entries")
    }

    private fun saveIpToHistory(ipPort: String) {
        val canonical = NetworkEndpoint.parse(ipPort, defaultPort = 10000)?.encoded() ?: return
        // Remove if already exists (avoid duplicates)
        connectedIpHistory.remove(canonical)
        // Add to front (most recent)
        connectedIpHistory.add(0, canonical)
        // Keep max 10
        if (connectedIpHistory.size > 10) {
            connectedIpHistory.removeAt(connectedIpHistory.size - 1)
        }
        // Save to preferences
        val historyString = connectedIpHistory.joinToString(",")
        prefs?.edit()?.putString("ipHistory", historyString)?.apply()
        Log.i(TAG, "Saved IP to history: $canonical")
    }

    private fun updateReconnectUI() {
        if (connectedIpHistory.isEmpty()) {
            quickReconnectLabel.visibility = View.GONE
            ipHistoryText.visibility = View.GONE
            reconnectButton.visibility = View.GONE
        } else {
            quickReconnectLabel.visibility = View.VISIBLE
            ipHistoryText.visibility = View.VISIBLE
            reconnectButton.visibility = View.VISIBLE

            // Show top 3 IPs
            val displayCount = minOf(3, connectedIpHistory.size)
            val displayText = StringBuilder()
            for (i in 0 until displayCount) {
                displayText.append(connectedIpHistory[i])
                if (i < displayCount - 1) {
                    displayText.append("\n")
                }
            }
            if (connectedIpHistory.size > 3) {
                displayText.append("\n+ ${connectedIpHistory.size - 3} more")
            }
            ipHistoryText.text = displayText.toString()
        }
    }

    private fun reconnectToLast() {
        if (connectedIpHistory.isEmpty()) {
            AppToast.show(this, "No connection history available", Toast.LENGTH_SHORT)
            return
        }

        if (isReconnecting) {
            // If already reconnecting, cancel it
            cancelReconnect()
            return
        }

        isReconnecting = true
        reconnectIndex = 0
        reconnectLoopCount = 0
        awaitingConnectionResult = false
        currentReconnectIp = null
        reconnectButton.setText(R.string.cancel)

        // #359: session-only mute during a manual reconnection (not the persisted setting) so the
        // per-attempt disconnect cues stay quiet; cleared in restoreAlertPreference().
        listenService?.transientMute = true

        AppToast.show(this, R.string.searchingForChildDevices, Toast.LENGTH_SHORT)
        LogManager.i(LogManager.LogCategory.CONNECTION, "Starting reconnection loop through ${connectedIpHistory.size} saved devices")

        tryNextIpInHistory()
    }

    private fun cancelReconnect() {
        Log.i(TAG, "Cancelling reconnection loop")
        LogManager.i(LogManager.LogCategory.CONNECTION, "Reconnection cancelled by user")
        isReconnecting = false
        awaitingConnectionResult = false
        currentReconnectIp = null
        // Reset auto-reconnect gate so the next disconnect can auto-trigger again.
        // Without this, the user has to manually reconnect every time after the first one.
        hasTriedAutoReconnect = false
        // Drop any pending per-IP timeouts / discovery restarts
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectButton.text = getString(R.string.reconnect)

        // Restore alert preference
        restoreAlertPreference()

        AppToast.show(this, R.string.reconnectionCancelled, Toast.LENGTH_SHORT)

        val status = findViewById<TextView>(R.id.textStatus)
        status?.setText(R.string.cancelled)
    }

    private fun restoreAlertPreference() {
        // #359: clear the session-only reconnection mute; the persisted "muteAlerts" setting is read
        // fresh from prefs by the service, so there's nothing to restore.
        listenService?.transientMute = false
    }

    // Sentinel runnable for the per-IP timeout — keeping a field reference lets us cancel
    // it on advance/cancel so stale timeouts can't fire and skip a device.
    private var pendingPerIpTimeout: Runnable? = null

    /**
     * #402 Kids: whether the CURRENT reconnect target is a permanent (named) kid. Computed on demand
     * from the live [childDeviceId] (never cached) so it stays correct after a discovery child-swap or
     * a singleTop relaunch — both of which change [childDeviceId] without re-running onCreate.
     */
    private fun isPermanentTarget(): Boolean =
        childDeviceId?.let { PairedChildrenStore.findByDeviceId(this, it)?.isPermanent == true } ?: false

    private fun tryNextIpInHistory() {
        if (!isReconnecting) {
            // Cancelled
            return
        }

        // Drop any prior per-IP timeout before advancing
        pendingPerIpTimeout?.let { reconnectHandler.removeCallbacks(it) }
        pendingPerIpTimeout = null

        // Check if we've looped through all devices
        if (reconnectIndex >= connectedIpHistory.size) {
            reconnectIndex = 0
            reconnectLoopCount++

            // Check if we've exceeded max loops (0 = infinite). #402 Kids: a permanent kid escalates
            // to NSD-by-deviceId after a single saved-IP pass — derived on-demand from the current
            // target so a discovery-swap / relaunch can't leave a stale bound.
            val effectiveMaxLoops = if (maxReconnectLoops > 0)
                KidReconnectPolicy.loopsBeforeDiscovery(isPermanentTarget(), maxReconnectLoops)
            else maxReconnectLoops
            if (effectiveMaxLoops > 0 && reconnectLoopCount >= effectiveMaxLoops) {
                onConnectionFailure()
                return
            }

            Log.i(TAG, "Starting loop ${reconnectLoopCount + 1} through device list")
            LogManager.i(LogManager.LogCategory.CONNECTION, "Reconnect loop ${reconnectLoopCount + 1} - trying all devices again")
        }

        val ipPort = connectedIpHistory[reconnectIndex]
        currentReconnectIp = ipPort
        Log.i(TAG, "Trying to reconnect to: $ipPort (${reconnectIndex + 1}/${connectedIpHistory.size}, loop ${reconnectLoopCount + 1})")

        runOnUiThread {
            val status = findViewById<TextView>(R.id.textStatus)
            status?.text = getString(R.string.tryingEndpoint, ipPort)
            reconnectButton.text = getString(
                R.string.cancelAttempt,
                reconnectIndex + 1,
                connectedIpHistory.size,
            )
        }

        val endpoint = NetworkEndpoint.parse(ipPort, defaultPort = 10000)
        if (endpoint == null) {
            reconnectIndex++
            reconnectHandler.postDelayed({ tryNextIpInHistory() }, 250)
            return
        }

        val address = endpoint.host
        val port = endpoint.port

        awaitingConnectionResult = true

        // #138: prefer in-place target swap over service teardown. The previous
        // stopService → startService pattern fired up to 5 full onCreate/onDestroy
        // cycles in 44s (each one re-acquiring the wake lock, re-requesting audio
        // focus, re-starting network monitors, re-writing 4 session banners).
        // retargetForReconnect closes the current socket so the reconnect loop wakes
        // up and connects to the new address using the same long-lived service.
        val boundService = listenService
        val targetBundle = Bundle().apply {
            putString("address", address)
            putInt("port", port)
            putString("name", address)
        }
        if (boundService != null && shouldUnbind) {
            try {
                boundService.retargetForReconnect(address, port, address, childDeviceId)
                // #366: a live retarget can switch to a different child (new childDeviceId) without
                // rebinding, so refresh the transport badge to reflect the new connection's pin state.
                runOnUiThread { updateTransportBadge() }
            } catch (e: Exception) {
                Log.w(TAG, "retargetForReconnect failed, falling back to stop/start", e)
                doUnbindAndStopService()
                reconnectHandler.postDelayed({
                    if (!isReconnecting) return@postDelayed
                    try { ensureServiceRunningAndBind(targetBundle) } catch (_: Exception) {}
                }, 250)
            }
        } else {
            // Service isn't running yet (first attempt, post-kill, etc.). Cold-start it.
            try {
                ensureServiceRunningAndBind(targetBundle)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start connection to $ipPort", e)
                awaitingConnectionResult = false
                reconnectIndex++
                reconnectHandler.postDelayed({ tryNextIpInHistory() }, 500)
                return
            }
        }

        // Schedule per-IP timeout; capture both ipPort AND the reconnectIndex so
        // we can verify both still match before advancing. This prevents stale
        // timeouts from firing after we've already moved on.
        val expectedIndex = reconnectIndex
        val timeoutRunnable = Runnable {
            if (isReconnecting &&
                awaitingConnectionResult &&
                currentReconnectIp == ipPort &&
                reconnectIndex == expectedIndex) {
                Log.i(TAG, "Connection timeout for $ipPort (index $expectedIndex), trying next")
                awaitingConnectionResult = false
                reconnectIndex++
                tryNextIpInHistory()
            }
        }
        pendingPerIpTimeout = timeoutRunnable
        reconnectHandler.postDelayed(timeoutRunnable, reconnectDelayMs)
    }

    /**
     * Called when a connection attempt succeeds (from onConnected callback)
     */
    fun onReconnectSuccess() {
        if (!isReconnecting || !awaitingConnectionResult) return

        val ipPort = currentReconnectIp ?: return

        Log.i(TAG, "Reconnection successful to: $ipPort")
        LogManager.i(LogManager.LogCategory.CONNECTION, "Reconnected successfully to ${LogRedaction.redactIp(ipPort)} after ${reconnectLoopCount + 1} loop(s)")

        isReconnecting = false
        awaitingConnectionResult = false
        currentReconnectIp = null
        // Reset the auto-reconnect gate so the NEXT disconnect can auto-trigger again.
        hasTriedAutoReconnect = false
        // Cancel any still-pending per-IP timeout from this attempt
        pendingPerIpTimeout?.let { reconnectHandler.removeCallbacks(it) }
        pendingPerIpTimeout = null

        // Restore alert preference after reconnect
        restoreAlertPreference()

        runOnUiThread {
            reconnectButton.text = getString(R.string.reconnect)

            // Move successful IP to front
            saveIpToHistory(ipPort)
            updateReconnectUI()

            // Update snapshot and talkback URLs with new address
            NetworkEndpoint.parse(ipPort, defaultPort = 10000)?.let {
                updateRemoteEndpoint(it.host, source = "reconnectSuccess")
            }

            AppToast.show(this, "Connected to $ipPort", Toast.LENGTH_SHORT)
        }
    }

    /**
     * Called when a connection attempt fails (from onError callback)
     */
    fun onReconnectError() {
        if (!isReconnecting || !awaitingConnectionResult) return

        val ipPort = currentReconnectIp ?: return
        Log.i(TAG, "Connection failed to: $ipPort, trying next device")

        awaitingConnectionResult = false
        reconnectIndex++

        // Small delay before trying next device. [#240] Go through reconnectHandler (not a
        // fresh Handler) so onDestroy()/cancelReconnect() can cancel this pending advance;
        // otherwise the callback fired after teardown and briefly retained the destroyed Activity.
        reconnectHandler.postDelayed({
            tryNextIpInHistory()
        }, 1000)
    }

    private fun onConnectionFailure() {
        isReconnecting = false
        awaitingConnectionResult = false
        currentReconnectIp = null

        // Reconnect-robustness (symmetric guard): a channel may have come back mid-sweep — e.g. the
        // service's doListen reconnected on its own, or a snapshot frame arrived. If EITHER channel
        // is up now, the child IP is good, so don't enter NSD discovery (which would keep retargeting
        // the working channel). The recovered channel proves liveness; the missing one self-heals.
        val audioUp = listenService?.isAudioPlaying == true
        val videoUp = isVideoUp()
        if (ConnectionStatePolicy.onChannelDown(audioUp, videoUp)
            == ConnectionStatePolicy.Action.RETRY_MISSING_CHANNEL) {
            Log.i(TAG, "onConnectionFailure: a channel is live again - skipping discovery fallback")
            // Stop the reconnect loop cleanly (we're connected enough on one channel): clear the
            // session-only mute, re-arm auto-reconnect for a future both-down drop, reset the button +
            // any pending per-IP/discovery callbacks, and show the partial status — otherwise the
            // button stays on "Cancel" and transientMute stays true (alerts silently muted).
            hasTriedAutoReconnect = false
            reconnectHandler.removeCallbacksAndMessages(null)
            reconnectButton.text = getString(R.string.reconnect)
            restoreAlertPreference()
            findViewById<TextView>(R.id.textStatus)?.setText(
                if (audioUp) R.string.listeningAudioOnly else R.string.listeningVideoOnly)
            return
        }

        LogManager.w(LogManager.LogCategory.CONNECTION, "Failed to reconnect after $reconnectLoopCount loop(s) through ${connectedIpHistory.size} devices - trying discovery")

        // Fall back to NSD discovery to find child devices
        startDiscoveryFallback()
    }

    /**
     * Start NSD discovery as a fallback when saved IPs don't work
     */
    private fun startDiscoveryFallback() {
        if (isDiscoveryFallbackActive) {
            Log.w(TAG, "Discovery fallback already active")
            return
        }

        isDiscoveryFallbackActive = true
        LogManager.i(LogManager.LogCategory.DISCOVERY, "Starting NSD discovery fallback")

        runOnUiThread {
            reconnectButton.setText(R.string.searching)
            val status = findViewById<TextView>(R.id.textStatus)
            status?.setText(R.string.discoveringChildDevices)
            AppToast.show(this, R.string.savedAddressesFailed, Toast.LENGTH_SHORT)
        }

        nsdDiscoveryHelper = NsdDiscoveryHelper(this).apply {
            onDeviceFound = deviceFound@{ device ->
                Log.i(TAG, "Discovery found device: ${device.name} id=${device.deviceId} at ${device.address}:${device.port}")
                LogManager.i(LogManager.LogCategory.DISCOVERY, "Found child via discovery: ${device.name} id=${device.deviceId} at ${LogRedaction.redactIp(device.address)}:${device.port}")

                // If we have a target deviceId, ignore any other children on the LAN that
                // don't match — prevents accidentally connecting to a neighbor's monitor.
                // #402 Kids: a permanent kid requires an EXACT id match (never adopt a different or
                // unidentified child for a pinned link); the legacy path stays lenient (skip only on a
                // positive mismatch) so an old child that doesn't advertise an id is still adopted.
                val target = childDeviceId
                val permanent = isPermanentTarget()
                if (!KidReconnectPolicy.acceptsDiscovered(target, device.deviceId, permanent)) {
                    Log.i(TAG, "Discovered child id=${device.deviceId} not accepted for target $target (permanent=$permanent) — skipping")
                } else if (isDiscoveryFallbackActive) {
                    isDiscoveryFallbackActive = false
                    nsdDiscoveryHelper?.stopDiscovery()

                    // Add to legacy IP history
                    val ipPort = NetworkEndpoint.create(device.address, device.port)?.encoded()
                        ?: return@deviceFound
                    connectedIpHistory.remove(ipPort)
                    connectedIpHistory.add(0, ipPort)
                    if (connectedIpHistory.size > 10) {
                        connectedIpHistory.removeAt(connectedIpHistory.size - 1)
                    }
                    val historyString = connectedIpHistory.joinToString(",")
                    prefs?.edit()?.putString("ipHistory", historyString)?.apply()

                    // Update deviceId-based pairing record with the rediscovered IP
                    device.deviceId?.let { id ->
                        childDeviceId = id
                        PairedChildrenStore.upsert(
                            this@ListenActivity,
                            PairedChild(
                                deviceId = id,
                                name = device.name,
                                lastKnownIp = device.address,
                                lastKnownPort = device.port,
                                lastSeenMs = System.currentTimeMillis()
                            )
                        )
                    }

                    // Restore alert preference after discovery
                    restoreAlertPreference()

                    runOnUiThread {
                        reconnectButton.text = getString(R.string.reconnect)
                        val status = findViewById<TextView>(R.id.textStatus)
                        status?.text = getString(R.string.foundChildConnecting, device.name)

                        updateRemoteEndpoint(device.address, source = "discoveryFallback")

                        // #278: the discovered IP was just pushed to the front of
                        // connectedIpHistory above. Drive the reconnect through reconnectToLast()
                        // so it goes via tryNextIpInHistory -> retargetForReconnect(childDeviceId),
                        // which LIVE-SWAPS the already-running ListenService to the new IP (with the
                        // bearer token) and resets the FSM. The previous connectToChild() relaunch
                        // only hit onNewIntent()/setIntent() and never retargeted the audio socket,
                        // so a discovered new IP was found but never actually connected.
                        reconnectToLast()
                    }
                }
            }

            onDiscoveryComplete = { devices ->
                Log.i(TAG, "Discovery complete, found ${devices.size} device(s)")

                // #402 Kids (Codex P2): restart whenever we DIDN'T adopt a target — onDeviceFound
                // clears isDiscoveryFallbackActive on a successful adopt, so if it's still set here we
                // found nothing usable. This covers both an empty scan AND a non-empty scan where the
                // strict permanent-kid filter rejected every result (else reconnect would hang).
                if (isDiscoveryFallbackActive) {
                    isDiscoveryFallbackActive = false
                    LogManager.w(LogManager.LogCategory.DISCOVERY, "Discovery found no matching child (${devices.size} seen) - restarting reconnect loop")
                    // Restart the reconnect loop after a delay. Go through reconnectHandler so
                    // onDestroy can cancel us — isFinishing alone misses config-change destroys.
                    runOnUiThread {
                        val status = findViewById<TextView>(R.id.textStatus)
                        status?.setText(R.string.noMatchingChildRetrying)
                        reconnectHandler.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                reconnectToLast()
                            }
                        }, 5000)
                    }
                }
            }

            onDiscoveryFailed = { error ->
                Log.e(TAG, "Discovery failed: $error")
                isDiscoveryFallbackActive = false
                LogManager.e(LogManager.LogCategory.DISCOVERY, "Discovery fallback failed: $error - restarting reconnect loop")
                runOnUiThread {
                    val status = findViewById<TextView>(R.id.textStatus)
                    status?.setText(R.string.discoveryFailedRetrying)
                    reconnectHandler.postDelayed({
                        if (!isFinishing && !isDestroyed) {
                            reconnectToLast()
                        }
                    }, 5000)
                }
            }
        }

        nsdDiscoveryHelper?.startDiscovery(timeoutMs = 15000L)
    }

    private fun openFullscreenCamera() {
        val url = snapshotUrl ?: run {
            syncRemoteEndpointFromService()
            snapshotUrl
        } ?: return
        Log.i(TAG, "Opening fullscreen camera with snapshot URL: $url")
        val intent = Intent(this, FullscreenCameraActivity::class.java)
        intent.putExtra("snapshotUrl", url)
        // [#231] forward the childDeviceId so fullscreen can pin TLS and attach the bearer
        // token exactly like the inline snapshot path; without it fullscreen fetched over
        // unpinned HTTPS with no auth (MITM + perpetual 401).
        intent.putExtra("deviceId", childDeviceId)
        intent.putExtra(EXTRA_EVENT_TYPE, latestEventType ?: this.intent?.getStringExtra(EXTRA_EVENT_TYPE))
        intent.putExtra(
            EXTRA_EVENT_TIMESTAMP_MS,
            if (latestEventTimestampMs > 0) latestEventTimestampMs else (this.intent?.getLongExtra(EXTRA_EVENT_TIMESTAMP_MS, -1L)
                ?: -1L)
        )
        startActivity(intent)
    }

    private fun toggleRemoteCamera() {
        val address = childAddress ?: run {
            syncRemoteEndpointFromService()
            childAddress
        } ?: return
        Thread {
            try {
                val connection = openPinnedConnection(buildControlUrl(address, "/api/camera/toggle"))
                // #193: camera toggle is also auth-gated on the child side.
                ParentSessionStore.get(this@ListenActivity, childDeviceId)?.let { tok ->
                    connection.setRequestProperty("Authorization", "Bearer $tok")
                }
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                // #190-HIGH CSRF: child WebControlServer now rejects GET on
                // state-changing endpoints. POST keeps cross-site browsers
                // (which would need a CORS preflight we don't grant) out.
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.outputStream.use { it.write(ByteArray(0)) }
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    cameraEnabled = json.optBoolean("cameraEnabled", true)
                    runOnUiThread {
                        updateCameraToggleButton()
                        if (!cameraEnabled) {
                            snapshotImage.setImageResource(android.R.color.darker_gray)
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Camera toggle failed: ${e.message}")
                runOnUiThread {
                    AppToast.show(this, "Failed to toggle camera", Toast.LENGTH_SHORT)
                }
            }
        }.start()
    }

    private fun updateCameraToggleButton() {
        toggleCameraButton.text = if (cameraEnabled) getString(R.string.cameraOn) else getString(R.string.cameraOff)
    }

    // #325: snapshot cadence (child encode rate). Maps wire mode → its selector button.
    private val cadenceButtonIds = linkedMapOf(
        "ECO" to R.id.cadenceEcoButton,
        "BALANCED" to R.id.cadenceBalancedButton,
        "NEAR_LIVE" to R.id.cadenceNearLiveButton,
        "AUTO" to R.id.cadenceAutoButton,
    )

    /** #325: last cadence mode the child confirmed; the failure path reverts the highlight to this. Touched only on the UI thread. */
    private var confirmedCadenceMode: String? = null

    /**
     * #325: set the child's snapshot cadence via POST /api/snapshot/cadence?mode= (mirrors
     * toggleRemoteCamera's pinned + bearer POST). Optimistically highlights the chosen mode, then
     * reconciles to the mode the child confirms in the response.
     */
    private fun setSnapshotCadence(mode: String) {
        val address = childAddress ?: run {
            syncRemoteEndpointFromService()
            childAddress
        } ?: return
        highlightCadence(mode) // optimistic
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = openPinnedConnection(buildControlUrl(address, "/api/snapshot/cadence?mode=$mode"))
                ParentSessionStore.get(this@ListenActivity, childDeviceId)?.let { tok ->
                    connection.setRequestProperty("Authorization", "Bearer $tok")
                }
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.requestMethod = "POST" // #190 CSRF: cadence is a state-changing endpoint
                connection.doOutput = true
                connection.outputStream.use { it.write(ByteArray(0)) }
                connection.connect()
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val confirmed = JSONObject(connection.inputStream.bufferedReader().readText())
                        .optString("mode", mode)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            confirmedCadenceMode = confirmed
                            highlightCadence(confirmed)
                            recomputeSnapshotInterval() // #325 part 3: re-derive the poll for the new base mode
                        }
                    }
                } else {
                    try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                    onCadenceUpdateFailed()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Set snapshot cadence failed: ${e.message}")
                onCadenceUpdateFailed()
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "set-cadence" }.start()
    }

    /**
     * #325: cadence change failed. Immediately revert the optimistic highlight to the last mode the
     * child confirmed (so the selector never shows a rate the child didn't apply), then make a
     * best-effort re-read of authoritative state. If that read also fails the revert stands.
     */
    private fun onCadenceUpdateFailed() {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                highlightCadence(confirmedCadenceMode)
                AppToast.show(this, R.string.cadenceFailed, Toast.LENGTH_SHORT)
            }
        }
        fetchCurrentCadence()
    }

    /** #325: one-shot read of the child's current cadence (GET /api/status) to seed the selector. */
    private fun fetchCurrentCadence() {
        val address = childAddress ?: return
        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = openPinnedConnection(buildControlUrl(address, "/api/status"))
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.doInput = true
                ParentSessionStore.get(this@ListenActivity, childDeviceId)?.let { tok ->
                    connection.setRequestProperty("Authorization", "Bearer $tok")
                }
                connection.connect()
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val mode = JSONObject(connection.inputStream.bufferedReader().readText())
                        .optJSONObject("snapshotCadence")?.optString("mode")
                    if (!mode.isNullOrBlank()) {
                        runOnUiThread {
                            if (!isFinishing && !isDestroyed) {
                                confirmedCadenceMode = mode
                                highlightCadence(mode)
                                recomputeSnapshotInterval() // #325 part 3: re-derive the poll for the base mode
                            }
                        }
                    }
                } else {
                    try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Fetch snapshot cadence failed: ${e.message}")
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; name = "fetch-cadence" }.start()
    }

    /** Show [mode] as the active cadence (full opacity) and dim the others. A null/unknown mode dims all. */
    private fun highlightCadence(mode: String?) {
        val selectedId = mode?.let { cadenceButtonIds[it] }
        for (id in cadenceButtonIds.values) {
            findViewById<Button>(id)?.alpha = if (id == selectedId) 1f else 0.5f
        }
    }

    // #338: guards against overlapping replay requests (double-taps).
    private val replayInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * #338 (part of #284): fetch the child's recent-audio ring buffer (`/api/replay/audio`) on a
     * background thread and play it back through the service. Most useful right after a disconnect
     * alert, to hear what happened just before — there is no live audio competing then.
     */
    private fun replayRecentAudio() {
        val address = childAddress ?: run {
            syncRemoteEndpointFromService()
            childAddress
        }
        if (listenService == null || address == null) {
            AppToast.show(this, getString(R.string.replayUnavailable), Toast.LENGTH_SHORT)
            return
        }
        if (!replayInFlight.compareAndSet(false, true)) return
        setReplayButtonBusy(true)
        Thread { fetchAndPlayReplay(address) }.apply { isDaemon = true }.start()
        // #338: in parallel, pull the child's snapshot ring so the user can scrub the buffered
        // frames alongside the audio. Independent of the audio path; no-op if the ring is empty.
        Thread { fetchReplaySnapshots(address) }.apply { isDaemon = true }.start()
    }

    private fun fetchAndPlayReplay(address: String) {
        val requestDeviceId = childDeviceId
        val requestToken = ParentSessionStore.get(this, requestDeviceId)
        // clampSeconds keeps the wire value in the child's 1..60 range even if the source ever
        // becomes non-constant (boundary validation; harmless on today's fixed DEFAULT_SECONDS).
        val seconds = ReplayAudioPolicy.clampSeconds(ReplayAudioPolicy.DEFAULT_SECONDS)
        var connection: HttpURLConnection? = null
        try {
            connection = openPinnedConnection(buildControlUrl(address, "/api/replay/audio?seconds=$seconds"))
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.doInput = true
            // #193: the replay endpoint is auth-gated on the child, exactly like /snapshot.
            requestToken?.let { tok ->
                connection.setRequestProperty("Authorization", "Bearer $tok")
            }
            connection.connect()
            val status = connection.responseCode
            val bytes: ByteArray = if (status == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { readBoundedBytes(it, ReplayAudioPolicy.MAX_REPLAY_BYTES) }
            } else {
                // Drain the error body so the socket returns to the keep-alive pool (review #9).
                try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                ByteArray(0)
            }
            when (ReplayAudioPolicy.outcome(status, bytes.size)) {
                ReplayOutcome.PLAY -> {
                    val svc = listenService
                    if (svc != null) {
                        // The service resets the button when the clip finishes (or is cancelled).
                        svc.playReplay(bytes) { runOnUiThread { finishReplay() } }
                    } else {
                        toastReplay(R.string.replayUnavailable)
                        runOnUiThread { finishReplay() }
                    }
                    return // keep the button busy until playback completes
                }
                ReplayOutcome.NOTHING_RECENT -> toastReplay(R.string.replayNothing)
                ReplayOutcome.UNAUTHORIZED -> {
                    // Renew without erasing a newer bearer saved by the foreground service.
                    de.rochefort.childmonitor.parent.SessionRenewer.recoverRejectedBearer(
                        applicationContext, requestDeviceId, address, requestToken,
                    )
                    toastReplay(R.string.replayFailed)
                }
                ReplayOutcome.ERROR -> toastReplay(R.string.replayFailed)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Replay fetch failed: ${e.message}")
            toastReplay(R.string.replayFailed)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
        // Every non-PLAY path lands here (PLAY returned early; it finishes via the service callback).
        runOnUiThread { finishReplay() }
    }

    /** Read at most [max] bytes; stop early on an oversized body so a hostile host can't OOM us. */
    private fun readBoundedBytes(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            if (total + r > max) {
                out.write(buf, 0, max - total)
                break
            }
            out.write(buf, 0, r)
            total += r
        }
        return out.toByteArray()
    }

    private fun toastReplay(resId: Int) =
        runOnUiThread { AppToast.show(this, getString(resId), Toast.LENGTH_SHORT) }

    private fun setReplayButtonBusy(busy: Boolean) {
        findViewById<Button>(R.id.replayAudioButton)?.apply {
            isEnabled = !busy
            text = getString(if (busy) R.string.replayPlaying else R.string.replayAudio)
        }
    }

    private fun finishReplay() {
        replayInFlight.set(false)
        setReplayButtonBusy(false)
    }

    // #338: snapshot replay scrubber state. scrubActive freezes the live snapshot loop (guarded in
    // fetchSnapshotOnce) while the user steps through the buffered frames.
    @Volatile private var scrubActive = false
    private var scrubAddress: String? = null
    private var scrubTimestamps: List<Long> = emptyList()
    private val scrubFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * #338: fetch the child's snapshot ring timestamp list (`GET /api/replay/snapshots`) and, if it
     * has frames, enter scrub mode. Runs on a background thread; silently does nothing when the ring
     * is empty/stale (204), the camera is off, or we're unauthorized — replay audio still works.
     */
    private fun fetchReplaySnapshots(address: String) {
        val requestDeviceId = childDeviceId
        val requestToken = ParentSessionStore.get(this, requestDeviceId)
        var connection: HttpURLConnection? = null
        try {
            connection = openPinnedConnection(buildControlUrl(address, "/api/replay/snapshots"))
            connection.connectTimeout = 3000
            connection.readTimeout = 5000
            connection.doInput = true
            requestToken?.let { tok ->
                connection.setRequestProperty("Authorization", "Bearer $tok")
            }
            connection.connect()
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.use {
                        readBoundedBytes(it, MAX_SCRUB_LIST_BYTES).toString(Charsets.UTF_8)
                    }
                    val timestamps = ReplayScrubPolicy.parseTimestamps(body)
                    if (timestamps.isNotEmpty()) runOnUiThread { enterScrubMode(address, timestamps) }
                }
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                    de.rochefort.childmonitor.parent.SessionRenewer.recoverRejectedBearer(
                        applicationContext, requestDeviceId, address, requestToken,
                    )
                }
                else -> {
                    // 204 (no frames / camera off / stale) etc. — just don't offer a scrubber.
                    try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Replay snapshots list fetch failed: ${e.message}")
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun enterScrubMode(address: String, timestamps: List<Long>) {
        // The list fetch is async — it can land during/after teardown; don't enter scrub then (the
        // shut-down snapshotFetchExecutor would also reject fetchScrubFrameAsync). Matches the
        // isFinishing/isDestroyed guard convention used by the other off-thread UI callbacks here.
        if (isFinishing || isDestroyed) return
        scrubAddress = address
        scrubTimestamps = timestamps
        scrubActive = true
        stopSnapshotLoop() // freeze the live view while scrubbing
        findViewById<SeekBar>(R.id.replayScrubSeek)?.apply {
            max = (timestamps.size - 1).coerceAtLeast(0)
            progress = timestamps.size - 1 // start at the most recent frame
        }
        findViewById<View>(R.id.replayScrubBar)?.visibility = View.VISIBLE
        AppToast.show(this, getString(R.string.replayScrubReady), Toast.LENGTH_SHORT)
        fetchScrubFrameAsync(timestamps.last()) // show the newest frame immediately
    }

    private fun fetchScrubFrameAsync(timestampMs: Long) {
        val address = scrubAddress ?: return
        // Drop if a fetch is already running (rapid drags) — the next progress change re-requests.
        if (!scrubFetchInFlight.compareAndSet(false, true)) return
        try {
            snapshotFetchExecutor.execute {
                try { fetchScrubFrame(address, timestampMs) } finally { scrubFetchInFlight.set(false) }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Executor shut down (onDestroy) between the guard and submit — release the guard.
            scrubFetchInFlight.set(false)
        }
    }

    private fun fetchScrubFrame(address: String, timestampMs: Long) {
        var connection: HttpURLConnection? = null
        try {
            connection = openPinnedConnection(buildControlUrl(address, "/api/replay/snapshots?at=$timestampMs"))
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doInput = true
            ParentSessionStore.get(this@ListenActivity, childDeviceId)?.let { tok ->
                connection.setRequestProperty("Authorization", "Bearer $tok")
            }
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val bmp = connection.inputStream.use { stream ->
                    BoundedBitmap.decodeBoundedStream(
                        stream,
                        maxBytes = BoundedBitmap.MAX_SNAPSHOT_BYTES,
                        maxDimension = BoundedBitmap.MAX_SNAPSHOT_DIMENSION,
                        config = android.graphics.Bitmap.Config.RGB_565
                    )
                }
                if (bmp != null) {
                    snapshotImage.post {
                        if (scrubActive) { // user may have exited while we fetched
                            resetInlineVideoViewport()
                            snapshotImage.setImageBitmap(bmp)
                            lastSnapshotBitmap = bmp // #288: keep save-to-gallery in sync
                        }
                    }
                    Log.i(TAG, "Replay scrub frame shown @ $timestampMs (${bmp.width}x${bmp.height})")
                }
            } else {
                try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "Replay scrub frame fetch failed: ${e.message}")
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun exitScrubMode() {
        if (!scrubActive) return
        scrubActive = false
        scrubTimestamps = emptyList()
        scrubAddress = null
        findViewById<View>(R.id.replayScrubBar)?.visibility = View.GONE
        pendingSnapshotViewportReset = true
        startSnapshotLoop() // resume the live view
    }

    private var lastQualityUpdate = 0L
    private fun updateConnectionQualityUI() {
        // Throttle UI updates to once per second
        val now = System.currentTimeMillis()
        if (now - lastQualityUpdate < 1000) return
        lastQualityUpdate = now

        runOnUiThread {
            val quality = listenService?.connectionQuality ?: return@runOnUiThread
            val statusText = findViewById<TextView>(R.id.textStatus) ?: return@runOnUiThread

            val qualityStr = when (quality) {
                ListenService.ConnectionQuality.EXCELLENT -> getString(R.string.listeningExcellent)
                ListenService.ConnectionQuality.GOOD -> getString(R.string.listeningGood)
                ListenService.ConnectionQuality.FAIR -> if (videoPausedForAudio) {
                    getString(R.string.listeningFairAudioPaused)
                } else {
                    getString(R.string.listeningFairVideoSlowed)
                }
                ListenService.ConnectionQuality.POOR -> getString(R.string.listeningPoorAudioPriority)
                ListenService.ConnectionQuality.UNKNOWN -> getString(R.string.listening)
            }

            // Only update if listening
            if (listenService?.isAudioPlaying == true) {
                // #301: surface the child (camera) device's battery alongside the status.
                val batPct = listenService?.childBatteryPct ?: -1
                val batSuffix = if (batPct in 0..100) {
                    val chg = if (listenService?.childCharging == true) {
                        getString(R.string.chargingGlyph)
                    } else ""
                    getString(R.string.listeningWithChildBattery, qualityStr, chg, batPct)
                } else ""
                statusText.text = if (batSuffix.isEmpty()) qualityStr else batSuffix

                // Change color based on quality
                val color = when (quality) {
                    ListenService.ConnectionQuality.EXCELLENT -> ContextCompat.getColor(this, R.color.status_connected)
                    ListenService.ConnectionQuality.GOOD -> ContextCompat.getColor(this, R.color.secondary_light)
                    ListenService.ConnectionQuality.FAIR -> ContextCompat.getColor(this, R.color.status_connecting)
                    ListenService.ConnectionQuality.POOR -> ContextCompat.getColor(this, R.color.status_disconnected)
                    ListenService.ConnectionQuality.UNKNOWN -> ContextCompat.getColor(this, R.color.status_idle)
                }
                statusText.setTextColor(color)
                recomputeSnapshotInterval()
                updateVideoPolicyUi()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTalkBackButton() {
        talkBackButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startTalkBack()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopTalkBack()
                    true
                }
                else -> false
            }
        }
    }

    private fun startTalkBack() {
        if (talkbackAttempt != null) return

        // Check microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.RECORD_AUDIO), PERMISSION_REQUEST_RECORD_AUDIO)
            return
        }

        val address = childAddress ?: run {
            syncRemoteEndpointFromService()
            childAddress
        } ?: return

        // #310/#423 sink-side guard: `address` is interpolated verbatim into the raw talkback WS
        // upgrade ("Host: $address:$wsPort\r\n", no CRLF stripping). Whatever its source — a restored
        // ipHistory/pairing (read in several places: ListenActivity, StartActivity, BootService), NSD,
        // or manual entry — refuse a host that isn't SAFE_HOST so CRLF can never reach the header. This
        // is the single chokepoint covering every address source (the per-source filters are defence
        // in depth).
        if (!PairingValidation.isSafeHost(address)) {
            Log.w(TAG, "Talkback refused: address failed host validation")
            return
        }

        val requestDeviceId = childDeviceId
        val attempt = de.rochefort.childmonitor.audio.TalkbackConnection()
        talkbackAttempt = attempt
        talkBackButton.text = getString(R.string.talking)
        talkBackButton.setBackgroundResource(R.drawable.bg_button_talk_active)

        // Mute parent playback to prevent feedback loop
        // (parent speaker → parent mic → child → child mic → parent speaker)
        listenService?.playbackMuted = true
        Log.i(TAG, "Talkback started - parent playback muted")

        Thread {
            var recorder: AudioRecord? = null
            var echoCanceler: AcousticEchoCanceler? = null
            var noiseSuppressor: NoiseSuppressor? = null
            try {
                // #129: when the parent has a TLS pin recorded, open a TLS
                // connection on the HTTPS port and pin-verify the cert before
                // sending the WS upgrade. Pin mismatch throws synchronously on
                // startHandshake() inside upgrade() so the user sees a single
                // "talkback unavailable" toast instead of a stuck UI.
                // #203: apply the no-downgrade policy to talkback too — the bearer
                // token rides this channel, so a previously-pinned device must not
                // fall back to a plaintext socket. requireTls without a current pin
                // means "was pinned, pin now missing" → refuse rather than leak.
                val pin = ParentSessionStore.fingerprintFor(this@ListenActivity, requestDeviceId)
                val hasEverPinned = ParentSessionStore.hasEverPinned(this@ListenActivity, requestDeviceId)
                if (TransportPolicy.requireTls(pin != null, hasEverPinned) && pin == null) {
                    // Tell the user why talkback won't start — unlike other talkback
                    // failures this one is a deliberate refusal, and the remedy
                    // (re-pair) must be surfaced, not silently logged.
                    runOnUiThread {
                        AppToast.show(
                            this@ListenActivity,
                            getString(R.string.talkback_refused_repair),
                            Toast.LENGTH_LONG
                        )
                    }
                    throw java.io.IOException(
                        "Talkback refused: device was paired over TLS but its pin is missing — re-pair (rescan QR) to restore an encrypted channel"
                    )
                }
                if (TransportPolicy.isInsecureCleartext(pin != null, hasEverPinned)) {
                    warnInsecureTransportOnce()
                }
                val wsPort = if (pin != null) {
                    ParentSessionStore.httpsPortFor(this@ListenActivity, requestDeviceId) ?: 11443
                } else 11000
                val wsAuthority = NetworkEndpoint.authority(address, wsPort)
                    ?: throw java.io.IOException("Talkback refused: invalid endpoint")
                val plainSocket = java.net.Socket()
                attempt.attach(plainSocket)
                plainSocket.connect(java.net.InetSocketAddress(address, wsPort), 5_000)
                plainSocket.soTimeout = 5_000
                val socket: java.net.Socket = if (pin != null) {
                    de.rochefort.childmonitor.parent.PinnedTls.upgrade(plainSocket, address, wsPort, pin)
                } else plainSocket
                attempt.attach(socket)

                // Send WebSocket upgrade request
                val outputStream = socket.getOutputStream()
                val inputStream = socket.getInputStream()

                val key = android.util.Base64.encodeToString(
                    // #190-MED: SecureRandom — predictable WebSocket keys would
                    // let an on-path attacker pre-compute valid Sec-WebSocket-Accept
                    // values. java.util.Random is seeded from System.nanoTime in
                    // older Android versions, which is observable on a busy LAN.
                    java.security.SecureRandom().let { rng ->
                        ByteArray(16).also { rng.nextBytes(it) }
                    },
                    android.util.Base64.NO_WRAP
                )
                // #193: present the paired bearer token via the standard Authorization header on the
                // WebSocket upgrade. PairingPayload validates the token at the QR boundary, but a token
                // could also arrive via a restored backup (#310) which bypasses that — so re-validate
                // at this sink against SAFE_TOKEN and drop an unsafe one rather than CRLF-inject the
                // raw upgrade (a dropped token just 401s; no injection).
                val token = ParentSessionStore.get(this@ListenActivity, requestDeviceId)
                    ?.takeIf { PairingValidation.isSafeToken(it) }
                val authHeader = if (token != null) "Authorization: Bearer $token\r\n" else ""
                val request = "GET /ws/talkback HTTP/1.1\r\n" +
                        "Host: $wsAuthority\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Key: $key\r\n" +
                        "Sec-WebSocket-Version: 13\r\n" +
                        authHeader +
                        "\r\n"
                outputStream.write(request.toByteArray())
                outputStream.flush()

                // Validate the upgrade response — review finding #4. Now also handles
                // partial-read responses (review-pass-2 #1) and validates the
                // Sec-WebSocket-Accept header per RFC 6455 §4.1 (review-pass-2 #2).
                // Read until we see the CRLFCRLF header terminator, OR fill the
                // buffer, OR hit the deadline (5s). TCP can deliver as few as 1
                // byte per read; the previous single-read assumption would
                // false-reject when status arrived fragmented across packets.
                val buffer = ByteArray(2048)
                var read = 0
                val headerReadDeadlineMs = System.currentTimeMillis() + 5_000L
                socket.soTimeout = 1_500   // bound individual reads so deadline can fire
                try {
                    while (read < buffer.size) {
                        if (System.currentTimeMillis() > headerReadDeadlineMs) break
                        val n = inputStream.read(buffer, read, buffer.size - read)
                        if (n <= 0) break
                        read += n
                        // Look for end-of-headers marker so we don't keep waiting after a
                        // small response (e.g., a 401 with no body).
                        if (read >= 4 && indexOfCrlfCrlf(buffer, read) >= 0) break
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // Whatever we have, we'll validate below. If the response was simply
                    // slow we may still have the status line.
                }
                socket.soTimeout = 0
                if (read <= 0) {
                    Log.w(TAG, "Talkback WS upgrade got empty response — aborting")
                    runOnUiThread {
                        AppToast.show(this@ListenActivity, "Talkback unavailable", Toast.LENGTH_SHORT)
                    }
                    try { socket.close() } catch (_: Exception) {}
                    return@Thread
                }
                val responseText = String(buffer, 0, read, Charsets.UTF_8)
                val statusLine = responseText.substringBefore("\r\n", "")
                val isUpgrade = statusLine.contains(" 101 ", ignoreCase = true) ||
                    statusLine.endsWith(" 101", ignoreCase = true)
                val isUnauthorized = statusLine.contains(" 401 ", ignoreCase = true) ||
                    statusLine.endsWith(" 401", ignoreCase = true)
                if (!isUpgrade) {
                    if (isUnauthorized) {
                        Log.w(TAG, "Talkback WS upgrade returned 401 — renewing session")
                        try { socket.close() } catch (_: Exception) {}
                        de.rochefort.childmonitor.parent.SessionRenewer.recoverRejectedBearer(
                            applicationContext, requestDeviceId, address, token,
                        )
                    }
                    LogManager.w(LogManager.LogCategory.AUDIO,
                        "Talkback WebSocket upgrade rejected: $statusLine")
                    runOnUiThread {
                        AppToast.show(
                            this@ListenActivity,
                            if (isUnauthorized) getString(R.string.talkbackSessionRetry) else "Talkback unavailable",
                            Toast.LENGTH_SHORT
                        )
                    }
                    try { socket.close() } catch (_: Exception) {}
                    return@Thread
                }
                // Verify Sec-WebSocket-Accept (RFC 6455 §4.1) — a server that returns
                // 101 without the correct accept-hash is either misconfigured, a
                // non-WebSocket service that happens to echo "101", or a MITM. Reject
                // either way; without this check, any 101 status line is taken at face
                // value and we'd push masked mu-law frames at a plain-HTTP endpoint.
                val expectedAccept = computeWebSocketAccept(key)
                val acceptMatches = responseText.lineSequence().any { line ->
                    line.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) &&
                        line.substringAfter(":").trim() == expectedAccept
                }
                if (!acceptMatches) {
                    Log.w(TAG, "Talkback WS upgrade missing/wrong Sec-WebSocket-Accept — rejecting")
                    LogManager.w(LogManager.LogCategory.AUDIO,
                        "Talkback WS server returned 101 without a valid Sec-WebSocket-Accept — possible misconfiguration or MITM")
                    runOnUiThread {
                        AppToast.show(this@ListenActivity,
                            "Talkback handshake failed (bad server response)",
                            Toast.LENGTH_SHORT)
                    }
                    try { socket.close() } catch (_: Exception) {}
                    return@Thread
                }

                // Start audio recording
                val sampleRate = 8000
                val bufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                    return@Thread
                }

                if (attempt.isCancelled) return@Thread
                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                // Attach acoustic echo canceler and noise suppressor
                recorder.audioSessionId.let { sessionId ->
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)
                        echoCanceler?.enabled = true
                        Log.i(TAG, "AcousticEchoCanceler enabled for talkback (session $sessionId)")
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)
                        noiseSuppressor?.enabled = true
                        Log.i(TAG, "NoiseSuppressor enabled for talkback (session $sessionId)")
                    }
                }

                if (attempt.isCancelled) return@Thread
                recorder.startRecording()

                val frameSamples = minOf(bufferSize / 2, sampleRate / 10).coerceAtLeast(1)
                val pcmBuffer = ShortArray(frameSamples)
                val mulawBuffer = ByteArray(frameSamples)

                Log.i(TAG, "Talkback started")

                while (!attempt.isCancelled && !Thread.currentThread().isInterrupted) {
                    val readCount = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount < 0) throw java.io.IOException("Talkback microphone read failed: $readCount")
                    if (readCount > 0) {
                        // Encode to mu-law using G711UCodec (same codec as main audio pipeline)
                        val encoded = AudioCodecDefines.CODEC.encode(pcmBuffer, readCount, mulawBuffer, 0)
                        // Send as WebSocket binary frame
                        de.rochefort.childmonitor.audio.MaskedWebSocketWriter.write(outputStream, mulawBuffer, encoded)
                    }
                }
            } catch (e: Exception) {
                if (!attempt.isCancelled) Log.e(TAG, "Talkback error", e)
            } finally {
                attempt.cancel()
                try { echoCanceler?.release() } catch (_: Exception) {}
                try { noiseSuppressor?.release() } catch (_: Exception) {}
                try { recorder?.stop() } catch (_: Exception) {}
                try { recorder?.release() } catch (_: Exception) {}
                runOnUiThread {
                    if (talkbackAttempt === attempt) stopTalkBack()
                }
            }
        }.apply { name = "parent-talkback"; isDaemon = true }.start()
    }

    private fun stopTalkBack() {
        val attempt = talkbackAttempt
        talkbackAttempt = null
        attempt?.cancel()
        listenService?.playbackMuted = false
        talkBackButton.text = getString(R.string.holdToTalk)
        talkBackButton.setBackgroundResource(R.drawable.bg_button_talk_idle)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                AppToast.show(this, "Microphone permission granted. Try again.", Toast.LENGTH_SHORT)
            } else {
                AppToast.show(this, "Microphone permission required for talk-back", Toast.LENGTH_SHORT)
            }
        } else if (requestCode == PERMISSION_REQUEST_SAVE_SNAPSHOT) {
            // #288: pre-29 storage permission flow — save now if it was granted.
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveCurrentSnapshot()
            } else {
                AppToast.show(this, R.string.saveSnapshotPermission, Toast.LENGTH_LONG)
            }
        }
    }

    companion object {
        private const val TAG = "ListenActivity"
        /** #402 Kids: optional intent extra carrying the parent-assigned kid label (display-only). */
        const val EXTRA_KID_DISPLAY_NAME = "kidDisplayName"

        /** Immutable (address, deviceId) pair so [ChildControlsActivity] reads both as one atomic
         *  snapshot — never a torn `oldAddress + newDeviceId` mid-swap (Codex). */
        data class LiveEndpoint(val address: String?, val deviceId: String?)

        // #311/#312: the live session's CURRENT child endpoint, published as one object so a
        // foregrounded ChildControlsActivity always targets the active child even after a mid-session
        // discovery child-swap (#366). Not cleared on destroy — it simply reflects the most recent
        // live child; the controls client still fails closed via the per-deviceId token/pin lookup.
        @Volatile var liveEndpoint: LiveEndpoint? = null
        private const val MENU_VIEW_LOGS = 1
        private const val MENU_BATTERY_SETTINGS = 2
        private const val MENU_DASHBOARD = 3
        private const val MENU_SETUP_WIZARD = 4
        private const val MENU_PAIRING = 5
        private const val MENU_AUDIO_CALIBRATION = 6
        private const val MENU_DIAGNOSTICS = 7
        private const val MENU_TIMELINE = 8
        private const val MENU_CHILD_CONTROLS = 9 // #311/#312 night light & soother
        private const val MENU_CHILD_ALERTS = 10 // #291/#308 alert config & webhooks
        private const val PERMISSION_REQUEST_RECORD_AUDIO = 12345
        private const val PERMISSION_REQUEST_SAVE_SNAPSHOT = 12346
        // #278: full sweeps of the saved-IP list on auto-reconnect before escalating to
        // mDNS discovery (which can find the child at a changed IP, matched by deviceId).
        private const val AUTO_RECONNECT_LOOPS_BEFORE_DISCOVERY = 3
        // Shared with SettingsActivity via DetectionPrefs so the in-session controls + the Settings
        // home can't drift on keys.
        private const val PREF_ADAPTIVE_VIDEO = DetectionPrefs.KEY_ADAPTIVE_VIDEO
        private const val PREF_MOTION_DETECTION = DetectionPrefs.KEY_MOTION_DETECTION
        private const val PREF_MOTION_THRESHOLD = DetectionPrefs.KEY_MOTION_THRESHOLD
        private const val SNAPSHOT_RECOVERY_RESET_THRESHOLD = 2
        // #338: cap the snapshot-replay timestamp-list body we'll buffer (a JSON array of longs;
        // ~150 frames ≈ a few KB — 64 KB is generous headroom against a misbehaving child).
        private const val MAX_SCRUB_LIST_BYTES = 64 * 1024
        private const val MOTION_ALERT_COOLDOWN_MS = 10_000L
        const val EXTRA_OPEN_FULLSCREEN = "extra_open_fullscreen"
        const val EXTRA_EVENT_TYPE = "extra_event_type"
        const val EXTRA_EVENT_TIMESTAMP_MS = "extra_event_timestamp_ms"
    }

    /**
     * Publish the current (childAddress, childDeviceId) as one atomic [LiveEndpoint] snapshot for a
     * foregrounded [ChildControlsActivity]. Called from every retarget (address change AND deviceId
     * change) so the two never tear; constructing a fresh immutable object means a reader sees a
     * consistent pair even mid-swap (Codex).
     */
    private fun publishLiveEndpoint() {
        liveEndpoint = LiveEndpoint(childAddress, childDeviceId)
    }

    private fun syncRemoteEndpointFromService() {
        val service = listenService ?: return
        updateRemoteEndpoint(service.currentTargetAddress, source = "service")
    }

    private fun updateRemoteEndpoint(address: String?, source: String) {
        if (address.isNullOrBlank()) return
        val newUrl = buildSnapshotUrl(address)
        if (childAddress == address && snapshotUrl == newUrl) return
        childAddress = address
        publishLiveEndpoint() // keep the child-controls target in sync with every retarget
        snapshotUrl = newUrl
        pendingSnapshotViewportReset = true
        lastSnapshotWidth = -1
        lastSnapshotHeight = -1
        hasLoggedSnapshotSuccess = false
        Log.i(TAG, "Remote endpoint updated from $source -> $newUrl")
    }

    /**
     * Compute the snapshot URL for [address] respecting any pinned TLS identity:
     * if the parent recorded a fingerprint at QR-pairing time we use HTTPS on the
     * recorded port; otherwise plaintext on the default 11000.
     */
    private fun buildSnapshotUrl(address: String): String = buildEndpointUrl(address, "/snapshot")

    /** Endpoint for arbitrary control-plane calls (camera toggle, etc.). */
    private fun buildControlUrl(address: String, path: String): String = buildEndpointUrl(address, path)

    /**
     * Resolve an HTTP(S) endpoint for [path] under the #203 no-downgrade policy.
     * If the device has a pin (now or ever), we always use HTTPS — when the pin
     * is currently missing this fails closed in [openPinnedConnection] ("re-pair
     * required") rather than silently leaking over cleartext. Only a device that
     * was never pinned uses plaintext, and that surfaces an insecure warning.
     */
    private fun buildEndpointUrl(address: String, path: String): String {
        val hasPinNow = ParentSessionStore.fingerprintFor(this, childDeviceId) != null
        val hasEverPinned = ParentSessionStore.hasEverPinned(this, childDeviceId)
        return if (TransportPolicy.requireTls(hasPinNow, hasEverPinned)) {
            val port = ParentSessionStore.httpsPortFor(this, childDeviceId) ?: 11443
            "https://${NetworkEndpoint.hostForAuthority(address)}:$port$path"
        } else {
            warnInsecureTransportOnce()
            "http://${NetworkEndpoint.hostForAuthority(address)}:11000$path"
        }
    }

    /** Guards the one-shot insecure-transport warning (#203). */
    @Volatile private var insecureTransportWarned = false

    /**
     * Surface a single user-visible warning that this child is connected over an
     * unencrypted channel (#203). Only reached for a device that has never been
     * pinned; a previously-pinned device fails closed instead of warning. Shown
     * once per activity instance to avoid spamming the reconnect loop.
     */
    private fun warnInsecureTransportOnce() {
        if (insecureTransportWarned) return
        insecureTransportWarned = true
        Log.w(TAG, "Insecure transport: child '$childDeviceId' has no TLS pin — snapshots and talkback token are unencrypted. Re-pair by QR to encrypt.")
        runOnUiThread {
            AppToast.show(
                this,
                getString(R.string.insecure_connection_warning),
                Toast.LENGTH_LONG
            )
        }
    }

    /**
     * #366: positive complement to [warnInsecureTransportOnce] — a chip showing THIS connection's
     * control-channel transport (🔒 TLS-pinned vs ⚠ cleartext/re-pair), derived from the exact same
     * [TransportPolicy] decision the request path uses, mapped through the honesty-tested
     * [PrivacyPosture.forConnection]. Hidden until the connection target ([childDeviceId]) is known.
     */
    private fun updateTransportBadge() {
        val badge = findViewById<TextView>(R.id.transportBadge) ?: return
        val deviceId = childDeviceId
        if (deviceId == null) {
            badge.visibility = View.GONE
            return
        }
        val decision = TransportPolicy.decide(
            hasPinNow = ParentSessionStore.fingerprintFor(this, deviceId) != null,
            hasEverPinned = ParentSessionStore.hasEverPinned(this, deviceId),
        )
        val fact = PrivacyPosture.forConnection(decision)
        val icon = if (fact.level == PrivacyLevel.GOOD) "🔒" else "⚠" // 🔒 / ⚠
        badge.text = getString(R.string.privacyFactHeading, icon, fact.title)
        badge.contentDescription = fact.title // strip the emoji for TalkBack
        badge.visibility = View.VISIBLE
    }

    /** Open a URL connection that auto-pins when the URL is HTTPS and the parent
     *  has a fingerprint recorded for [childDeviceId]. Returns null and forgets
     *  the pin if the pin is set but missing (defensive). */
    private fun openPinnedConnection(rawUrl: String): java.net.HttpURLConnection =
        PinnedHttp.openPinned(this, rawUrl, childDeviceId)
}
