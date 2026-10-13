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
 */
package de.rochefort.childmonitor

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import de.rochefort.childmonitor.security.AppLock
import de.rochefort.childmonitor.security.AppPin
import de.rochefort.childmonitor.security.AuditLog
import de.rochefort.childmonitor.security.PinPolicy

/**
 * #296/#375: the unlock gate. Shown over the app (by [ChildMonitorApp]) when "Require unlock to open"
 * is on and the app is opened / returns from the background. OPAQUE so content behind is never
 * visible while locked. Two unlock paths:
 *  - **Device credential / biometric** (default): confirms against the device's own secure lock
 *    through [BiometricPrompt] — no app-managed secret.
 *  - **In-app PIN** (#375, optional): if the user set one (only possible while the device is secure),
 *    a PIN field is shown first. "Use device lock instead" is ALWAYS available, so a forgotten PIN can
 *    never lock the user out, and a correct device-credential unlock clears the PIN backoff. Wrong-PIN
 *    backoff is strictly time-bounded ([PinPolicy]).
 *
 * Cancel / back backgrounds the app instead of revealing content.
 */
// FragmentActivity gives AndroidX BiometricPrompt a lifecycle/fragment host while preserving the
// project's existing non-AppCompat theme (#478). AppCompatActivity would crash before auth.
class LockActivity : FragmentActivity() {

    private var resolved = false
    private var confirmInFlight = false
    private var pinMode = false
    private val handler = Handler(Looper.getMainLooper())

    private var pinField: EditText? = null
    private var pinButton: Button? = null
    private var messageView: TextView? = null
    private var biometricPrompt: BiometricPrompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the lock screen (incl. the PIN field) out of screenshots / screen recording / recents.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // PIN path only when there's a PIN AND a device lock to fall back to (fail-safe invariant).
        pinMode = AppLock.isDeviceSecure(this) && AppPin.isPinSet(this)

        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = FrameLayout(this).apply { setBackgroundColor(SCREEN_BG) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = getString(R.string.appLockTitle)
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
        })
        column.addView(TextView(this).apply {
            text = getString(if (pinMode) R.string.appLockPinPrompt else R.string.appLockPrompt)
            setTextColor(0xFFB9C9BF.toInt()); textSize = 14f; gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        if (pinMode) {
            val field = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = getString(R.string.appLockPinHint)
                setHintTextColor(0xFF7E8E84.toInt()); setTextColor(Color.WHITE)
                gravity = Gravity.CENTER; textSize = 20f
                setOnEditorActionListener { _, _, _ -> submitPin(); true }
            }
            pinField = field
            column.addView(field)
            messageView = TextView(this).apply {
                setTextColor(0xFFFF8A80.toInt()); textSize = 13f; gravity = Gravity.CENTER
                setPadding(0, pad / 3, 0, pad / 3)
            }
            column.addView(messageView)
            pinButton = Button(this).apply {
                text = getString(R.string.appLockUnlock)
                setOnClickListener { submitPin() }
            }
            column.addView(pinButton)
            column.addView(Button(this).apply {
                text = getString(R.string.appLockUseDeviceLock)
                setOnClickListener { promptUnlock() } // always available — the fail-safe fallback
            })
        } else {
            column.addView(Button(this).apply {
                text = getString(R.string.appLockUnlock)
                setOnClickListener { promptUnlock() }
            })
        }

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply { gravity = Gravity.CENTER },
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (resolved) return
        if (pinMode) {
            refreshBackoffState()
            pinField?.requestFocus()
        } else if (!confirmInFlight) {
            promptUnlock()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // AndroidX retains the prompt across rotation. Cancelling during a configuration change
        // would turn a harmless rotation into an authentication cancellation and background the app.
        if (!isChangingConfigurations) biometricPrompt?.cancelAuthentication()
        biometricPrompt = null
        // If torn down before resolving (e.g. system kill while backgrounded from the lock screen),
        // clear the gate's "unlocking" flag so the next foreground RE-LOCKS instead of falling through
        // unauthenticated (ChildMonitorApp suppresses new locks while unlocking == true) (Codex).
        if (!resolved && !isChangingConfigurations) AppLock.gate.onUnlockFailedOrCancelled()
        super.onDestroy()
    }

    // ---- in-app PIN path (#375) ----

    private fun submitPin() {
        if (resolved) return
        val now = System.currentTimeMillis()
        val remaining = AppPin.remainingBackoffMs(this, now)
        if (remaining > 0L) { refreshBackoffState(); return } // still backing off
        val pin = pinField?.text?.toString().orEmpty()
        if (pin.isEmpty()) return
        if (AppPin.verify(this, pin)) {
            AppPin.resetAttempts(this)
            succeed("pin")
        } else {
            AppPin.recordFailure(this, now)
            runCatching { AuditLog.append(this, AuditLog.EVT_AUTH_FAIL, mapOf("source" to "app_pin")) }
            pinField?.setText("")
            messageView?.text = getString(R.string.appLockPinWrong)
            refreshBackoffState()
        }
    }

    /** Reflect any active backoff in the UI: disable the PIN button + show a countdown; re-enable
     *  when it elapses. The device-lock button stays enabled throughout (fail-safe). */
    private fun refreshBackoffState() {
        val remaining = AppPin.remainingBackoffMs(this, System.currentTimeMillis())
        val btn = pinButton ?: return
        if (remaining <= 0L) {
            btn.isEnabled = true
            return
        }
        btn.isEnabled = false
        val secs = ((remaining + 999L) / 1000L)
        messageView?.text = getString(R.string.appLockPinWait, secs)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ if (!resolved) refreshBackoffState() }, 1000L)
    }

    // ---- device-credential path (default + fail-safe fallback) ----

    private fun promptUnlock() {
        if (confirmInFlight) return
        // No device lock to confirm against → nothing to authenticate; fall open (never trap the user
        // out) AND turn the setting off so it doesn't misrepresent the posture.
        if (!AppLock.isDeviceSecure(this)) {
            AppLock.setEnabled(this, false)
            AppLock.markAutoDisabled(this) // #375 item 5: surface a one-time notice on next home view
            succeed("none")
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (resolved) return
                    confirmInFlight = false
                    AppPin.resetAttempts(this@LockActivity)
                    succeed("device_or_biometric")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (resolved) return
                    confirmInFlight = false
                    // In PIN mode the system prompt is an optional recovery path. Returning to the
                    // PIN field is safe; without a PIN, every cancellation/error backgrounds the app
                    // instead of exposing the protected activity or silently falling open.
                    if (pinMode) {
                        messageView?.text = errString
                        pinField?.requestFocus()
                    } else {
                        cancel()
                    }
                }

                override fun onAuthenticationFailed() {
                    // A rejected biometric is non-terminal: Android keeps the prompt open and may
                    // still accept another biometric or the device credential.
                }
            },
        )
        biometricPrompt = prompt

        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.appLockTitle))
            .setSubtitle(getString(R.string.appLockPrompt))
            .setConfirmationRequired(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else {
            // AndroidX provides the backwards-compatible device-credential bridge on API 21-29.
            // The deprecated method here belongs to PromptInfo's compatibility API; unlike the old
            // platform Intent path it remains lifecycle-aware and cannot return a nullable Intent.
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        confirmInFlight = true
        prompt.authenticate(builder.build())
    }

    private fun succeed(method: String) {
        if (resolved) return
        resolved = true
        AppLock.gate.onUnlockSucceeded()
        runCatching { AuditLog.append(this, AuditLog.EVT_APP_UNLOCK, mapOf("result" to "ok", "method" to method)) }
        finish()
    }

    private fun cancel() {
        if (resolved) return
        resolved = true
        AppLock.gate.onUnlockFailedOrCancelled()
        runCatching { AuditLog.append(this, AuditLog.EVT_APP_UNLOCK, mapOf("result" to "cancel")) }
        moveTaskToBack(true) // don't reveal the content behind the lock
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        cancel()
    }

    companion object {
        private const val SCREEN_BG = 0xFF14201B.toInt()
    }
}
