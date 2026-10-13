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

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class StartActivity : Activity() {
    private var prefs: SharedPreferences? = null
    private val connectedIpHistory = mutableListOf<String>()
    private var isReconnecting = false
    private var reconnectIndex = 0
    private lateinit var currentModeText: TextView
    private lateinit var currentModeSubtext: TextView
    private lateinit var openCurrentModeButton: Button
    private lateinit var stopCurrentModeButton: Button
    private lateinit var quickReconnectButton: Button
    private lateinit var quickReconnectDescription: TextView
    private lateinit var autoStartCheckbox: CheckBox
    private lateinit var autoStartParentCheckbox: CheckBox
    // [#265] Tracks whether the in-flight overlay-permission request was started by the parent
    // auto-start toggle (vs the child toggle) so onActivityResult persists the right pref —
    // both flows share REQUEST_OVERLAY_PERMISSION.
    private var overlayRequestForParent = false
    // #359/#379: guard so re-syncing the auto-start checkboxes (after a change in Settings) doesn't
    // re-fire the gating listeners below.
    private var syncingAutoStart = false
    private lateinit var themeFabButton: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "ChildMonitor launched")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_start)

        // #210: once-a-day, notify-only check for a newer GitHub release.
        UpdateChecker.maybeCheck(this)

        prefs = getSharedPreferences("ListenPreferences", Context.MODE_PRIVATE)

        currentModeText = findViewById(R.id.currentModeText)
        currentModeSubtext = findViewById(R.id.currentModeSubtext)
        openCurrentModeButton = findViewById(R.id.openCurrentModeButton)
        stopCurrentModeButton = findViewById(R.id.stopCurrentModeButton)
        quickReconnectButton = findViewById(R.id.quickParentReconnect)
        quickReconnectDescription = findViewById(R.id.quickReconnectDescription)
        themeFabButton = findViewById(R.id.themeFabButton)
        themeFabButton.setOnClickListener { showThemePicker() }

        // #297/#366: tap the privacy badge to open the friendly, card-styled privacy screen
        // (PrivacyActivity); the technical SecurityStatusActivity is one tap deeper from there.
        findViewById<android.widget.TextView>(R.id.privacyStatusChip)?.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        autoStartCheckbox = findViewById(R.id.autoStartChildCheckbox)
        autoStartCheckbox.isChecked = prefs?.getBoolean(AutoStartGate.KEY_CHILD, false) ?: false
        // #379: gate via the shared AutoStartGate (same decision as SettingsActivity/SetupWizard).
        autoStartCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (syncingAutoStart) return@setOnCheckedChangeListener
            if (isChecked) applyAutoStartDecision(AutoStartGate.Role.CHILD)
            else prefs?.edit()?.putBoolean(AutoStartGate.KEY_CHILD, false)?.apply()
        }

        // Parent auto-start: same gate, but only needs RECORD_AUDIO (not camera), and requires a
        // saved child (IP history OR a paired kid #406) — otherwise enabling does nothing useful.
        autoStartParentCheckbox = findViewById(R.id.autoStartParentCheckbox)
        autoStartParentCheckbox.isChecked = prefs?.getBoolean(AutoStartGate.KEY_PARENT, false) ?: false
        autoStartParentCheckbox.setOnCheckedChangeListener { _, isChecked ->
            if (syncingAutoStart) return@setOnCheckedChangeListener
            if (isChecked) applyAutoStartDecision(AutoStartGate.Role.PARENT)
            else prefs?.edit()?.putBoolean(AutoStartGate.KEY_PARENT, false)?.apply()
        }

        // #357: all tunable settings (suppress toasts, granular disconnect-alert effects —
        // sound/vibration/screen-flash/torch) live in the advertised Settings screen.
        findViewById<Button>(R.id.openSettingsButton)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Load IP history
        loadIpHistory()

        val monitorButton = findViewById<Button>(R.id.useChildDevice)
        monitorButton.setOnClickListener { _: View? ->
            Log.i(TAG, "Starting up monitor")
            if (hasChildPermissions()) {
                startChildMode()
            } else {
                requestChildPermissions()
            }
        }
        val connectButton = findViewById<Button>(R.id.useParentDevice)
        connectButton.setOnClickListener { _: View? ->
            Log.i(TAG, "Starting connection activity")
            if (isMulticastPermissionGranted) {
                startParentMode()
            } else {
                requestMulticastPermission()
            }
        }
        findViewById<Button>(R.id.openKidsButton)?.setOnClickListener {
            startActivity(Intent(this, KidsActivity::class.java))
        }

        findViewById<Button>(R.id.openNightTimelineButton)?.setOnClickListener {
            startActivity(Intent(this, de.rochefort.childmonitor.nighttimeline.NightTimelineActivity::class.java))
        }
        findViewById<Button>(R.id.openDashboardButton)?.setOnClickListener {
            startActivity(Intent(this, DashboardActivity::class.java))
        }
        findViewById<Button>(R.id.openSetupWizardButton)?.setOnClickListener {
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
        findViewById<Button>(R.id.openDiagnosticsButton)?.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<Button>(R.id.openTimelineButton)?.setOnClickListener {
            startActivity(Intent(this, TimelineActivity::class.java))
        }
        findViewById<Button>(R.id.openPairingButton)?.setOnClickListener {
            startActivity(Intent(this, PairingActivity::class.java))
        }
        findViewById<Button>(R.id.openAudioCalibrationButton)?.setOnClickListener {
            startActivity(Intent(this, AudioCalibrationActivity::class.java))
        }
        findViewById<Button>(R.id.openBabyTrackerButton)?.setOnClickListener {
            startActivity(Intent(this, de.rochefort.childmonitor.tracker.ui.BabyDashboardActivity::class.java))
        }
        openCurrentModeButton.setOnClickListener {
            openActiveSession()
        }
        stopCurrentModeButton.setOnClickListener {
            stopActiveSessions()
        }

        // Setup quick reconnect button
        updateQuickReconnectButton()
        maybeLaunchSetupWizard()
        updateActiveSessionUi()
        ThemeManager.applyTheme(this)
    }

    private fun maybeLaunchSetupWizard() {
        val setupPrefs = getSharedPreferences(SETUP_PREFS, Context.MODE_PRIVATE)
        val setupComplete = setupPrefs.getBoolean(KEY_SETUP_COMPLETE, false)
        val autoShown = setupPrefs.getBoolean(KEY_SETUP_AUTO_SHOWN, false)
        if (!setupComplete && !autoShown) {
            setupPrefs.edit().putBoolean(KEY_SETUP_AUTO_SHOWN, true).apply()
            startActivity(Intent(this, SetupWizardActivity::class.java))
        }
    }

    private fun loadIpHistory() {
        val historyString = prefs?.getString("ipHistory", "") ?: ""
        connectedIpHistory.clear()
        if (historyString.isNotEmpty()) {
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

    override fun onResume() {
        super.onResume()
        // #375 item 5: one-time notice if the app lock auto-disabled itself because the device's
        // screen lock was removed (so the user knows the extra protection is no longer active).
        if (de.rochefort.childmonitor.security.AppLock.consumeAutoDisabledNotice(this)) {
            AppToast.show(this, R.string.appLockAutoDisabled, Toast.LENGTH_LONG)
        }
        // Reload IP history and refresh the quick reconnect button when returning to this activity
        loadIpHistory()
        updateQuickReconnectButton()
        updateActiveSessionUi()
        // #359/#379: reflect auto-start changes made in Settings (the mirrored checkboxes share prefs).
        syncingAutoStart = true
        try {
            autoStartCheckbox.isChecked = prefs?.getBoolean(AutoStartGate.KEY_CHILD, false) ?: false
            autoStartParentCheckbox.isChecked = prefs?.getBoolean(AutoStartGate.KEY_PARENT, false) ?: false
        } finally {
            syncingAutoStart = false
        }
        ThemeManager.applyTheme(this)
    }

    private fun startChildMode() {
        if (isParentSessionActive()) {
            AppToast.show(this, R.string.switchingToChild, Toast.LENGTH_SHORT)
            stopParentSession()
        }
        startActivity(Intent(applicationContext, MonitorActivity::class.java))
    }

    private fun startParentMode() {
        if (isChildSessionActive()) {
            AppToast.show(this, R.string.switchingToParent, Toast.LENGTH_SHORT)
            stopChildSession()
        }
        startActivity(Intent(applicationContext, DiscoverActivity::class.java))
    }

    private fun stopParentSession() {
        // [#274] Clear the watchdog's sessionActive latch and cancel its pending alarm — without
        // this the WatchdogReceiver tick (~90s) resurrects ListenService after the user stopped
        // it. This is the single choke point for stopping the parent session (direct stop,
        // role-switch, and stop-all all route through here).
        WatchdogReceiver.stopSession(this)
        stopService(Intent(this, ListenService::class.java))
    }

    private fun stopChildSession() {
        stopService(Intent(this, MonitorService::class.java))
    }

    private fun stopActiveSessions() {
        val mode = detectActiveSessionMode()
        when (mode) {
            SessionMode.NONE -> {
                AppToast.show(this, R.string.noActiveSession, Toast.LENGTH_SHORT)
            }
            SessionMode.PARENT -> {
                stopParentSession()
                AppToast.show(this, R.string.parentStopped, Toast.LENGTH_SHORT)
            }
            SessionMode.CHILD -> {
                stopChildSession()
                AppToast.show(this, R.string.childStopped, Toast.LENGTH_SHORT)
            }
            SessionMode.BOTH -> {
                stopParentSession()
                stopChildSession()
                AppToast.show(this, R.string.allSessionsStopped, Toast.LENGTH_SHORT)
            }
        }
        quickReconnectButton.postDelayed({ updateActiveSessionUi() }, 350)
    }

    private fun openActiveSession() {
        when (detectActiveSessionMode()) {
            SessionMode.PARENT -> {
                startActivity(Intent(this, ListenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            SessionMode.CHILD -> {
                startActivity(Intent(this, MonitorActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            SessionMode.BOTH -> {
                startActivity(Intent(this, ListenActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
            SessionMode.NONE -> {
                AppToast.show(this, R.string.noActiveSession, Toast.LENGTH_SHORT)
            }
        }
    }

    private fun updateActiveSessionUi() {
        when (detectActiveSessionMode()) {
            SessionMode.NONE -> {
                currentModeText.setText(R.string.noActiveSession)
                currentModeSubtext.setText(R.string.noActiveSessionHint)
                openCurrentModeButton.isEnabled = false
                stopCurrentModeButton.isEnabled = false
                openCurrentModeButton.setText(R.string.openCurrentSession)
                stopCurrentModeButton.setText(R.string.stopCurrentSession)
            }
            SessionMode.PARENT -> {
                currentModeText.setText(R.string.parentSessionActive)
                currentModeSubtext.setText(R.string.parentSessionHint)
                openCurrentModeButton.isEnabled = true
                stopCurrentModeButton.isEnabled = true
                openCurrentModeButton.setText(R.string.openParentSession)
                stopCurrentModeButton.setText(R.string.stopParentSession)
            }
            SessionMode.CHILD -> {
                currentModeText.setText(R.string.childSessionActive)
                currentModeSubtext.setText(R.string.childSessionHint)
                openCurrentModeButton.isEnabled = true
                stopCurrentModeButton.isEnabled = true
                openCurrentModeButton.setText(R.string.openChildSession)
                stopCurrentModeButton.setText(R.string.stopChildSession)
            }
            SessionMode.BOTH -> {
                currentModeText.setText(R.string.bothSessionsActive)
                currentModeSubtext.setText(R.string.bothSessionsHint)
                openCurrentModeButton.isEnabled = true
                stopCurrentModeButton.isEnabled = true
                openCurrentModeButton.setText(R.string.openEitherSession)
                stopCurrentModeButton.setText(R.string.stopAllSessions)
            }
        }
    }

    private fun showThemePicker() {
        val selected = ThemeManager.getCurrentPaletteIndex(this)
        val density = resources.displayMetrics.density

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (4 * density).toInt())
        }

        // #362: high contrast overrides the chosen theme, so a picked swatch won't show until it's
        // turned off — say so rather than leaving the swatches looking like dead controls.
        if (ThemeManager.isHighContrast(this)) {
            container.addView(TextView(this).apply {
                text = getString(R.string.themeOverriddenByHighContrast)
                textSize = 13f
                setPadding(0, 0, 0, (10 * density).toInt())
            })
        }

        // The per-palette click listener references the dialog (assigned below, before show()) so it
        // can dismiss after applying. Captured by the lambdas as a var — non-null by the time any
        // swatch can be tapped. NOTE: do NOT re-wire listeners by container child index afterwards —
        // when high contrast is on a warning TextView occupies child 0, so child index != palette
        // index and that off-by-one mis-selects the theme (#421).
        var dialog: AlertDialog? = null

        ThemeManager.palettes.forEachIndexed { index, palette ->
            val button = Button(this).apply {
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 14f * density
                    setColor(palette.buttonPrimary)
                    setStroke((1.5f * density).toInt(), palette.surfaceBorder)
                }
                background = shape
                text = if (index == selected) {
                    "✓ ${palette.name}  ${palette.sourceHexes.take(4).joinToString(" ")}"
                } else {
                    "${palette.name}  ${palette.sourceHexes.take(4).joinToString(" ")}"
                }
                isAllCaps = false
                textSize = 14f
                setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
                setTextColor(if (ThemeManager.isLightColorForUi(palette.buttonPrimary)) ThemeManager.onLightButtonText else palette.textPrimary)
                setOnClickListener {
                    ThemeManager.setThemeIndex(this@StartActivity, index)
                    ThemeManager.applyTheme(this@StartActivity)
                    dialog?.dismiss()
                }
            }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
            container.addView(button, params)
        }

        val scroll = ScrollView(this).apply {
            addView(container)
        }

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.chooseTheme)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
    }

    private fun updateQuickReconnectButton() {
        // #402 Kids: prefer launching a paired kid BY deviceId (default kid → most-recent) so
        // Quick-Reconnect gets the permanent-link path — token/pin reuse, the kid name, and the
        // fast + identity-strict reconnect. Fall back to the legacy IP-only path only when no kid
        // is paired (e.g. an old "Select by Address" connection that never created a record).
        val targetKid = QuickReconnectTarget.resolve(
            PairedChildrenStore.getDefaultKidDeviceId(this),
            PairedChildrenStore.list(this)
        )
        when {
            targetKid != null -> {
                quickReconnectButton.visibility = View.VISIBLE
                quickReconnectDescription.visibility = View.VISIBLE
                quickReconnectDescription.text = getString(R.string.quickReconnectTo, targetKid.displayName)
                quickReconnectButton.setOnClickListener { connectToKid(targetKid) }
            }
            connectedIpHistory.isNotEmpty() -> {
                quickReconnectButton.visibility = View.VISIBLE
                quickReconnectDescription.visibility = View.VISIBLE
                val mostRecentIp = connectedIpHistory.first()
                quickReconnectDescription.text = if (connectedIpHistory.size == 1) {
                    getString(R.string.quickReconnectTo, mostRecentIp)
                } else {
                    resources.getQuantityString(
                        R.plurals.quickReconnectSavedDevices,
                        connectedIpHistory.size,
                        mostRecentIp,
                        connectedIpHistory.size,
                    )
                }
                quickReconnectButton.setOnClickListener { connectToChildDevice(mostRecentIp) }
            }
            else -> {
                quickReconnectButton.visibility = View.GONE
                quickReconnectDescription.visibility = View.GONE
            }
        }
    }

    /** Quick-Reconnect to a paired kid by deviceId (permanent-link path). Same intent as KidsActivity.connect. */
    private fun connectToKid(kid: PairedChild) {
        startActivity(KidConnect.buildIntent(applicationContext, kid,
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun connectToChildDevice(ipPort: String) {
        val endpoint = NetworkEndpoint.parse(ipPort, defaultPort = 10000)
        if (endpoint == null) {
            AppToast.show(this, R.string.invalidIpAddressFormat, Toast.LENGTH_SHORT)
            return
        }

        val address = endpoint.host
        val port = endpoint.port

        // Start ListenActivity with auto-reconnect enabled
        // This will make it automatically try all IPs in history if first one fails
        val i = Intent(applicationContext, ListenActivity::class.java)
        val b = Bundle()
        b.putString("address", address)
        b.putInt("port", port)
        b.putString("name", address)
        b.putBoolean("autoReconnect", true)  // Enable automatic sequential reconnect
        i.putExtras(b)
        startActivity(i)
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return activityManager.getRunningServices(Int.MAX_VALUE).any { info ->
            info.service.className == serviceClass.name && info.foreground
        }
    }

    private fun isParentSessionActive(): Boolean = isServiceRunning(ListenService::class.java)

    private fun isChildSessionActive(): Boolean = isServiceRunning(MonitorService::class.java)

    private fun detectActiveSessionMode(): SessionMode {
        val parent = isParentSessionActive()
        val child = isChildSessionActive()
        return when {
            parent && child -> SessionMode.BOTH
            parent -> SessionMode.PARENT
            child -> SessionMode.CHILD
            else -> SessionMode.NONE
        }
    }

    private val isMulticastPermissionGranted: Boolean
        get() = (ContextCompat.checkSelfPermission(this@StartActivity, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
                == PackageManager.PERMISSION_GRANTED)
    private val isAudioRecordingPermissionGranted: Boolean
        get() = (ContextCompat.checkSelfPermission(this@StartActivity, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED)
    private val isCameraPermissionGranted: Boolean
        get() = (ContextCompat.checkSelfPermission(this@StartActivity, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED)
    private fun hasChildPermissions(): Boolean = isAudioRecordingPermissionGranted && isCameraPermissionGranted

    private fun requestChildPermissions() {
        ActivityCompat.requestPermissions(this@StartActivity, arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
                PERMISSIONS_REQUEST_CHILD_PERMISSIONS)
    }

    private fun requestMulticastPermission() {
        ActivityCompat.requestPermissions(this@StartActivity, arrayOf(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE),
                PERMISSIONS_REQUEST_MULTICAST)
    }

    /**
     * #379: the single auto-start gate, shared with SettingsActivity/SetupWizard via [AutoStartGate].
     * Re-invoked from the permission + overlay callbacks so the flow chains permissions → overlay →
     * persist as each grant lands. Behavior-preserving vs the old inline blocks.
     */
    private fun applyAutoStartDecision(role: AutoStartGate.Role) {
        val p = prefs ?: return
        when (AutoStartGate.decideEnable(role, hasAutoStartPermissions(role), autoStartHasSavedChild(), canDrawOverlaysCompat())) {
            AutoStartGate.Decision.PERSIST -> {
                setAutoStartCheckedSilently(role, true)
                p.edit().putBoolean(AutoStartGate.prefKey(role), true).apply()
            }
            AutoStartGate.Decision.REQUEST_PERMISSIONS -> {
                setAutoStartCheckedSilently(role, false)
                if (role == AutoStartGate.Role.PARENT) {
                    AppToast.show(this, "Microphone permission is required for parent auto-start", Toast.LENGTH_LONG)
                }
                val code = if (role == AutoStartGate.Role.CHILD) PERMISSIONS_REQUEST_AUTO_START else REQUEST_PARENT_AUTOSTART_PERMISSION
                ActivityCompat.requestPermissions(this, AutoStartGate.requiredPermissions(role), code)
            }
            AutoStartGate.Decision.REQUEST_OVERLAY -> {
                setAutoStartCheckedSilently(role, false)
                AppToast.show(this, "Please enable 'Display over other apps' for auto-start to work", Toast.LENGTH_LONG)
                overlayRequestForParent = (role == AutoStartGate.Role.PARENT)
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQUEST_OVERLAY_PERMISSION
                )
            }
            AutoStartGate.Decision.BLOCK_NO_SAVED_CHILD -> {
                setAutoStartCheckedSilently(role, false)
                AppToast.show(this, "Connect to a child device at least once before enabling parent auto-start", Toast.LENGTH_LONG)
            }
        }
    }

    private fun setAutoStartCheckedSilently(role: AutoStartGate.Role, value: Boolean) {
        syncingAutoStart = true
        try {
            (if (role == AutoStartGate.Role.CHILD) autoStartCheckbox else autoStartParentCheckbox).isChecked = value
        } finally {
            syncingAutoStart = false
        }
    }

    private fun hasAutoStartPermissions(role: AutoStartGate.Role): Boolean =
        AutoStartGate.requiredPermissions(role).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun canDrawOverlaysCompat(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun autoStartHasSavedChild(): Boolean =
        AutoStartGate.hasSavedChild(prefs?.getString("ipHistory", ""), PairedChildrenStore.list(this).size)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        if (requestCode == PERMISSIONS_REQUEST_CHILD_PERMISSIONS && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startChildMode()
        } else if (requestCode == PERMISSIONS_REQUEST_MULTICAST) {
            // its okay if the permission was denied... the user will have to type the address manually
            startParentMode()
        } else if (requestCode == PERMISSIONS_REQUEST_AUTO_START && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            applyAutoStartDecision(AutoStartGate.Role.CHILD) // perms granted → chain to overlay/persist
        } else if (requestCode == REQUEST_PARENT_AUTOSTART_PERMISSION && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            applyAutoStartDecision(AutoStartGate.Role.PARENT) // mic granted (#265) → chain to saved-child/overlay/persist
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            if (canDrawOverlaysCompat()) {
                // [#265] overlay granted → re-run the gate for whichever role initiated it → persists.
                applyAutoStartDecision(if (overlayRequestForParent) AutoStartGate.Role.PARENT else AutoStartGate.Role.CHILD)
            } else {
                AppToast.show(this, "Overlay permission required for auto-start on boot", Toast.LENGTH_SHORT)
            }
        }
    }

    companion object {
        const val TAG = "ChildMonitor"
        private const val PERMISSIONS_REQUEST_CHILD_PERMISSIONS = 298349824
        private const val PERMISSIONS_REQUEST_MULTICAST = 298349825
        private const val PERMISSIONS_REQUEST_AUTO_START = 298349826
        private const val REQUEST_OVERLAY_PERMISSION = 298349827
        private const val REQUEST_PARENT_AUTOSTART_PERMISSION = 298349828
        private const val SETUP_PREFS = "SetupPreferences"
        private const val KEY_SETUP_COMPLETE = "setupWizardCompleted"
        private const val KEY_SETUP_AUTO_SHOWN = "setupWizardAutoShown"
    }

    private enum class SessionMode {
        NONE,
        PARENT,
        CHILD,
        BOTH
    }
}
