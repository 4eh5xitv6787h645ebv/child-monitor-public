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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import de.rochefort.childmonitor.alarm.AccessState
import de.rochefort.childmonitor.alarm.AlertAccess
import de.rochefort.childmonitor.alarm.AlertAccessPolicy
import de.rochefort.childmonitor.alarm.AlertEffects
import de.rochefort.childmonitor.alarm.AlertEffectsPolicy
import de.rochefort.childmonitor.alarm.ConnectionAlertNotifier
import de.rochefort.childmonitor.nighttimeline.Sensitivity

/**
 * #357: the advertised, discoverable in-app Settings screen. Per the UI conventions in CLAUDE.md,
 * every user-tunable setting must be reachable here with an explicit control, and multi-effect
 * features expose each effect as its own toggle.
 *
 * Disconnect-alert effects are independent: sound / vibration / screen-flash / camera-torch. The
 * old "vibration-only" and "deaf/HoH" presets are just combinations of these (deaf/HoH = sound off,
 * the other three on). Changes are read fresh at alert time, so they apply without a restart.
 */
class SettingsActivity : Activity() {

    // #359/#379: role awaiting a permission/overlay grant, and a guard so programmatic checkbox
    // re-syncs don't re-fire the gating listeners.
    private var pendingAutoStartRole: AutoStartGate.Role? = null
    private var syncingAutoStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        ThemeManager.applyTheme(this)

        val prefs = getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE)
        val effects = AlertEffectsPolicy.load(prefs)

        val sound = findViewById<CheckBox>(R.id.settingSoundCheckbox)
        val vibration = findViewById<CheckBox>(R.id.settingVibrationCheckbox)
        val screenFlash = findViewById<CheckBox>(R.id.settingScreenFlashCheckbox)
        val torch = findViewById<CheckBox>(R.id.settingTorchCheckbox)

        sound.isChecked = effects.sound
        vibration.isChecked = effects.vibration
        screenFlash.isChecked = effects.screenFlash
        torch.isChecked = effects.torch

        val persist = {
            AlertEffectsPolicy.save(
                prefs,
                AlertEffects(
                    sound = sound.isChecked,
                    vibration = vibration.isChecked,
                    screenFlash = screenFlash.isChecked,
                    torch = torch.isChecked
                )
            )
        }
        sound.setOnCheckedChangeListener { _, _ -> persist() }
        vibration.setOnCheckedChangeListener { _, _ -> persist() }
        screenFlash.setOnCheckedChangeListener { _, _ -> persist() }
        torch.setOnCheckedChangeListener { _, _ -> persist() }

        // Suppress pop-up (toast) messages — moved out of the StartActivity "Tools" block.
        val suppressToasts = findViewById<CheckBox>(R.id.settingSuppressToastsCheckbox)
        suppressToasts.isChecked = prefs.getBoolean(AppToast.KEY_SUPPRESS, false)
        suppressToasts.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(AppToast.KEY_SUPPRESS, checked).apply()
        }

        // #297: the only outbound internet the app makes on its own is the daily update check.
        // Expose an explicit off switch so a privacy-conscious user can guarantee zero egress.
        val updateCheck = findViewById<CheckBox>(R.id.settingUpdateCheckCheckbox)
        updateCheck.isChecked = UpdateChecker.isAutoCheckEnabled(this)
        updateCheck.setOnCheckedChangeListener { _, checked ->
            UpdateChecker.setAutoCheckEnabled(this, checked)
        }

        // #296: require the device unlock (PIN/biometric) to open the monitor. Only enableable when
        // the device actually has a secure lock to confirm against — otherwise there's nothing to
        // authenticate, so we refuse and revert the toggle.
        val appLock = findViewById<CheckBox>(R.id.settingAppLockCheckbox)
        appLock.isChecked = de.rochefort.childmonitor.security.AppLock.isEnabled(this)
        appLock.setOnCheckedChangeListener { _, checked ->
            if (checked && !de.rochefort.childmonitor.security.AppLock.isDeviceSecure(this)) {
                appLock.isChecked = false
                AppToast.show(this, R.string.appLockNoDeviceLock, android.widget.Toast.LENGTH_LONG)
            } else {
                de.rochefort.childmonitor.security.AppLock.setEnabled(this, checked)
                // #375 lock-on-enable: lock immediately (so it takes effect now AND the user confirms
                // they can unlock right after enabling) — requireReauth alone fires no lifecycle
                // callback while Settings stays foreground, so launch the lock screen ourselves,
                // mirroring ChildMonitorApp.showLock (Codex).
                if (checked) {
                    de.rochefort.childmonitor.security.AppLock.gate.requireReauth()
                    de.rochefort.childmonitor.security.AppLock.gate.beginUnlock()
                    startActivity(
                        android.content.Intent(this, LockActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
            }
        }

        // #375: open the in-app PIN setup screen (visible button — discoverable, not menu-only).
        findViewById<android.widget.Button>(R.id.settingAppPinButton)?.setOnClickListener {
            startActivity(android.content.Intent(this, PinSetupActivity::class.java))
        }

        // #362: high-contrast accessibility overlay (WCAG AA). Independent of the chosen pastel theme;
        // re-apply immediately so it takes effect on return without a restart (ThemeManager recolors
        // every tagged view via applyRecursive).
        val highContrast = findViewById<CheckBox>(R.id.settingHighContrastCheckbox)
        highContrast.isChecked = ThemeManager.isHighContrast(this)
        highContrast.setOnCheckedChangeListener { _, checked ->
            ThemeManager.setHighContrast(this, checked)
            ThemeManager.applyTheme(this)
        }

        // #361: monitor mode "keep screen on" (default ON) — BlackoutActivity reads it on entry.
        val monitorKeepScreenOn = findViewById<CheckBox>(R.id.settingMonitorKeepScreenOnCheckbox)
        monitorKeepScreenOn.isChecked = prefs.getBoolean("monitorModeKeepScreenOn", true)
        monitorKeepScreenOn.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("monitorModeKeepScreenOn", checked).apply()
        }

        // #359: consolidate the remaining settings here too (they also keep their in-context
        // checkboxes). Each writes the same ListenPreferences key — the service reads mute/persistent
        // fresh at alert time, and BootReceiver reads the auto-start flags at boot.
        fun bindPrefToggle(id: Int, key: String) {
            val cb = findViewById<CheckBox>(id) ?: return
            cb.isChecked = prefs.getBoolean(key, false)
            cb.setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(key, checked).apply()
            }
        }
        bindPrefToggle(R.id.settingMuteCheckbox, "muteAlerts")
        bindPrefToggle(R.id.settingPersistentAlarmCheckbox, "persistentAlarm")

        // #359/#379: auto-start on boot — consolidated here (also mirrored on the home-screen role
        // cards; both share AutoStartGate + the same prefs). Enabling gates on permissions →
        // (parent) a saved child → overlay permission, requesting each as needed before persisting.
        val autoStartChild = findViewById<CheckBox>(R.id.settingAutoStartChildCheckbox)
        val autoStartParent = findViewById<CheckBox>(R.id.settingAutoStartParentCheckbox)
        autoStartChild.isChecked = prefs.getBoolean(AutoStartGate.KEY_CHILD, false)
        autoStartParent.isChecked = prefs.getBoolean(AutoStartGate.KEY_PARENT, false)
        autoStartChild.setOnCheckedChangeListener { _, checked -> onAutoStartToggle(AutoStartGate.Role.CHILD, checked) }
        autoStartParent.setOnCheckedChangeListener { _, checked -> onAutoStartToggle(AutoStartGate.Role.PARENT, checked) }
        findViewById<Button>(R.id.settingTimerScreenOffButton)?.setOnClickListener {
            if (!ScreenOffController.isEnabled(this)) {
                startActivity(ScreenOffController.activationIntent(this))
            }
        }
        refreshTimerScreenOffUi()

        // #285: night-detection sensitivity — a granular Low/Medium/High selector (mirrors the
        // cycle-button on the Night Timeline screen; both share the nightSensitivity pref). The night
        // timeline re-classifies at render and NightRecorder seeds its live thresholds, so a change
        // here takes effect with no recapture.
        sensitivityButtonIds.forEach { (sens, id) ->
            findViewById<Button>(id)?.setOnClickListener {
                Sensitivity.save(this, sens)
                highlightSensitivity(sens)
            }
        }
        highlightSensitivity(Sensitivity.load(this))

        // Detection: adaptive-video + motion-detection, mirroring ListenActivity's in-session controls
        // (shared via DetectionPrefs). Read fresh by ListenActivity at session start.
        val adaptiveVideo = findViewById<CheckBox>(R.id.settingAdaptiveVideoCheckbox)
        adaptiveVideo.isChecked = prefs.getBoolean(DetectionPrefs.KEY_ADAPTIVE_VIDEO, DetectionPrefs.DEFAULT_ADAPTIVE_VIDEO)
        adaptiveVideo.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(DetectionPrefs.KEY_ADAPTIVE_VIDEO, checked).apply()
        }
        bindPrefToggle(R.id.settingMotionDetectionCheckbox, DetectionPrefs.KEY_MOTION_DETECTION) // default false

        val thresholdSeek = findViewById<SeekBar>(R.id.settingMotionThresholdSeek)
        val thresholdLabel = findViewById<TextView>(R.id.settingMotionThresholdLabel)
        thresholdSeek.max = DetectionPrefs.MOTION_THRESHOLD_MAX
        val curThreshold = DetectionPrefs.clampThreshold(
            prefs.getInt(DetectionPrefs.KEY_MOTION_THRESHOLD, DetectionPrefs.DEFAULT_MOTION_THRESHOLD)
        )
        thresholdSeek.progress = curThreshold
        thresholdLabel.text = getString(R.string.settingMotionThreshold, curThreshold)
        thresholdSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = DetectionPrefs.clampThreshold(progress)
                prefs.edit().putInt(DetectionPrefs.KEY_MOTION_THRESHOLD, v).apply()
                thresholdLabel.text = getString(R.string.settingMotionThreshold, v)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // #320: special-access grants so disconnect/cry alerts can pierce an ACTIVE Do-Not-Disturb
        // and reliably wake a locked screen. The buttons open the system grant screens; status is
        // refreshed in onResume (and the channels are re-created there once DnD access lands).
        findViewById<Button>(R.id.settingDndAccessButton)?.setOnClickListener {
            AlertAccess.openDndAccessSettings(this)
        }
        findViewById<Button>(R.id.settingFsiAccessButton)?.setOnClickListener {
            AlertAccess.openFullScreenIntentSettings(this)
        }
        // #310: open the encrypted backup/restore screen.
        findViewById<Button>(R.id.settingBackupButton)?.setOnClickListener {
            startActivity(android.content.Intent(this, BackupActivity::class.java))
        }
        refreshAlertAccessUi()
    }

    override fun onResume() {
        super.onResume()
        // Returning from a system grant screen: re-create the alert channels so a freshly-granted
        // DnD bypass takes effect, then reflect the live access state.
        runCatching { ConnectionAlertNotifier(this).refreshDndBypass() }
            .onFailure { android.util.Log.w("SettingsActivity", "refreshDndBypass failed", it) }
        refreshAlertAccessUi()
        refreshTimerScreenOffUi()
        // Reflect any auto-start change made on the home screen (the mirrored checkboxes share prefs).
        val p = getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE)
        syncingAutoStart = true
        try {
            findViewById<CheckBox>(R.id.settingAutoStartChildCheckbox)?.isChecked = p.getBoolean(AutoStartGate.KEY_CHILD, false)
            findViewById<CheckBox>(R.id.settingAutoStartParentCheckbox)?.isChecked = p.getBoolean(AutoStartGate.KEY_PARENT, false)
        } finally {
            syncingAutoStart = false
        }
        // Reflect a night-sensitivity change made via the Night Timeline cycle-button (shared pref).
        highlightSensitivity(Sensitivity.load(this))
    }

    private fun refreshTimerScreenOffUi() {
        findViewById<Button>(R.id.settingTimerScreenOffButton)?.apply {
            val enabled = ScreenOffController.isEnabled(this@SettingsActivity)
            text = getString(
                if (enabled) R.string.settingTimerScreenOffEnabled
                else R.string.settingTimerScreenOffEnable
            )
            isEnabled = !enabled
            alpha = if (enabled) 0.7f else 1f
        }
    }

    // ---- #359/#379: auto-start gating (decision shared via AutoStartGate) ----

    private fun autoStartCheckbox(role: AutoStartGate.Role): CheckBox? = findViewById(
        if (role == AutoStartGate.Role.CHILD) R.id.settingAutoStartChildCheckbox
        else R.id.settingAutoStartParentCheckbox
    )

    private fun onAutoStartToggle(role: AutoStartGate.Role, checked: Boolean) {
        if (syncingAutoStart) return
        if (!checked) {
            getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(AutoStartGate.prefKey(role), false).apply()
            return
        }
        applyAutoStartDecision(role)
    }

    /** The same decision drives the initial toggle AND the permission/overlay callbacks, so the flow
     *  chains permissions → overlay → persist as each grant lands. */
    private fun applyAutoStartDecision(role: AutoStartGate.Role) {
        when (AutoStartGate.decideEnable(role, hasAutoStartPermissions(role), hasSavedChild(), canDrawOverlays())) {
            AutoStartGate.Decision.PERSIST -> {
                pendingAutoStartRole = null
                setAutoStartChecked(role, true)
                getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(AutoStartGate.prefKey(role), true).apply()
            }
            AutoStartGate.Decision.REQUEST_PERMISSIONS -> {
                setAutoStartChecked(role, false)
                pendingAutoStartRole = role
                ActivityCompat.requestPermissions(this, AutoStartGate.requiredPermissions(role), REQ_AUTOSTART_PERMS)
            }
            AutoStartGate.Decision.REQUEST_OVERLAY -> {
                setAutoStartChecked(role, false)
                AppToast.show(this, R.string.autoStartOverlayNeeded, Toast.LENGTH_LONG)
                pendingAutoStartRole = role
                startActivityForResult(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    REQ_AUTOSTART_OVERLAY
                )
            }
            AutoStartGate.Decision.BLOCK_NO_SAVED_CHILD -> {
                pendingAutoStartRole = null
                setAutoStartChecked(role, false)
                AppToast.show(this, R.string.autoStartNeedChild, Toast.LENGTH_LONG)
            }
        }
    }

    private fun setAutoStartChecked(role: AutoStartGate.Role, value: Boolean) {
        syncingAutoStart = true
        try {
            autoStartCheckbox(role)?.isChecked = value
        } finally {
            syncingAutoStart = false
        }
    }

    private fun hasAutoStartPermissions(role: AutoStartGate.Role): Boolean =
        AutoStartGate.requiredPermissions(role).all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun hasSavedChild(): Boolean {
        val ipHistory = getSharedPreferences(LISTEN_PREFS, Context.MODE_PRIVATE).getString("ipHistory", "")
        return AutoStartGate.hasSavedChild(ipHistory, PairedChildrenStore.list(this).size)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_AUTOSTART_PERMS) return
        val role = pendingAutoStartRole ?: return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            applyAutoStartDecision(role) // perms granted → continue to overlay/persist
        } else {
            pendingAutoStartRole = null
            AppToast.show(this,
                if (role == AutoStartGate.Role.CHILD) R.string.autoStartPermsNeeded else R.string.autoStartMicNeeded,
                Toast.LENGTH_LONG)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUTOSTART_OVERLAY) return
        val role = pendingAutoStartRole ?: return
        if (canDrawOverlays()) {
            applyAutoStartDecision(role) // overlay granted → persist (or block)
        } else {
            pendingAutoStartRole = null
            AppToast.show(this, R.string.autoStartOverlayNeeded, Toast.LENGTH_SHORT)
        }
    }

    private fun refreshAlertAccessUi() {
        val dnd = AlertAccess.dndState(this)
        val fsi = AlertAccess.fullScreenIntentState(this)
        findViewById<TextView>(R.id.settingAlertAccessStatus)?.text = getString(
            if (AlertAccessPolicy.anyNeeded(dnd, fsi)) R.string.alertAccessNeeded
            else R.string.alertAccessGranted
        )
        // DnD grant button: hide when already granted or not applicable (pre-23).
        findViewById<Button>(R.id.settingDndAccessButton)?.visibility =
            if (dnd == AccessState.NEEDED) android.view.View.VISIBLE else android.view.View.GONE
        // Full-screen-intent grant button: only relevant (and only shown) when needed (API 34+).
        findViewById<Button>(R.id.settingFsiAccessButton)?.visibility =
            if (fsi == AccessState.NEEDED) android.view.View.VISIBLE else android.view.View.GONE
    }

    // #285: the night-sensitivity selector buttons (Low/Medium/High), highlighted by alpha-dim
    // like the snapshot-cadence selector.
    private val sensitivityButtonIds = linkedMapOf(
        Sensitivity.LOW to R.id.settingSensLow,
        Sensitivity.MEDIUM to R.id.settingSensMedium,
        Sensitivity.HIGH to R.id.settingSensHigh,
    )

    private fun highlightSensitivity(selected: Sensitivity) {
        sensitivityButtonIds.forEach { (sens, id) ->
            findViewById<Button>(id)?.alpha = if (sens == selected) 1f else 0.5f
        }
    }

    companion object {
        private const val LISTEN_PREFS = "ListenPreferences"
        private const val REQ_AUTOSTART_PERMS = 5201
        private const val REQ_AUTOSTART_OVERLAY = 5202
    }
}
