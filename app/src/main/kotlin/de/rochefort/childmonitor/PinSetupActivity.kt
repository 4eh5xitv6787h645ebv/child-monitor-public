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

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import de.rochefort.childmonitor.security.AppLock
import de.rochefort.childmonitor.security.AppPin
import de.rochefort.childmonitor.security.AuditLog
import de.rochefort.childmonitor.security.PinPolicy

/**
 * #375: set / change / remove the OPTIONAL in-app app-lock PIN. Standalone, theme-aware screen
 * reached from Settings. The PIN is additive to the device-credential lock and can only be set while
 * the device itself is secure (so the device-credential prompt remains an always-available "forgot
 * PIN" fallback — no lockout). Removing the PIN always works.
 */
class PinSetupActivity : Activity() {

    private lateinit var pinField: EditText
    private lateinit var confirmField: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the PIN entry out of screenshots / screen recording / recents.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
        )
        // Fail-safe invariant: an in-app PIN requires a device lock to fall back to.
        if (!AppLock.isDeviceSecure(this)) {
            AppToast.show(this, getString(R.string.pinSetupNeedDeviceLock), Toast.LENGTH_LONG)
            finish()
            return
        }
        title = getString(R.string.pinSetupTitle)
        val p = ThemeManager.getCurrentPalette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val pinSet = AppPin.isPinSet(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.gradientStart, p.gradientMid, p.gradientEnd),
            )
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.pinSetupTitle)
            setTextColor(p.textPrimary); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(TextView(this).apply {
            text = getString(if (pinSet) R.string.pinSetupEnterChange else R.string.pinSetupEnter)
            setTextColor(p.textSecondary); textSize = 14f; setPadding(0, 0, 0, dp(14))
        })

        pinField = pinEntry(getString(R.string.pinSetupHintPin), p, ::dp)
        root.addView(pinField)
        confirmField = pinEntry(getString(R.string.pinSetupHintConfirm), p, ::dp)
        root.addView(confirmField)

        root.addView(themedButton(getString(if (pinSet) R.string.pinSetupChange else R.string.pinSetupSave), p, ::dp) { save() })
        if (pinSet) {
            root.addView(themedButton(getString(R.string.pinSetupRemove), p, ::dp) { remove() })
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun save() {
        val pin = pinField.text?.toString().orEmpty()
        val confirm = confirmField.text?.toString().orEmpty()
        if (!PinPolicy.isValidPin(pin)) {
            AppToast.show(this, getString(R.string.pinSetupInvalid), Toast.LENGTH_SHORT)
            return
        }
        if (pin != confirm) {
            AppToast.show(this, getString(R.string.pinSetupMismatch), Toast.LENGTH_SHORT)
            confirmField.setText("")
            return
        }
        if (!AppPin.setPin(this, pin)) { // defensive: setPin re-validates
            AppToast.show(this, getString(R.string.pinSetupInvalid), Toast.LENGTH_SHORT)
            return
        }
        runCatching { AuditLog.append(this, AuditLog.EVT_APP_PIN_SET) }
        AppToast.show(this, getString(R.string.pinSetupSaved), Toast.LENGTH_SHORT)
        finish()
    }

    private fun remove() {
        AppPin.clearPin(this)
        runCatching { AuditLog.append(this, AuditLog.EVT_APP_PIN_CLEAR) }
        AppToast.show(this, getString(R.string.pinSetupRemoved), Toast.LENGTH_SHORT)
        finish()
    }

    private fun pinEntry(hintText: String, p: ThemeManager.ThemePalette, dp: (Int) -> Int) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        setSingleLine(true)
        setHintTextColor(p.textSecondary); setTextColor(p.textPrimary); textSize = 18f
        background = pill(p.surfaceCard, p.surfaceBorder, dp(10).toFloat(), dp(1))
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(10) }
    }

    private fun themedButton(label: String, p: ThemeManager.ThemePalette, dp: (Int) -> Int, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = false; textSize = 15f
            background = pill(p.buttonSecondary, p.buttonSecondaryBorder, dp(10).toFloat(), dp(1))
            setTextColor(if (ThemeManager.isLightColorForUi(p.buttonSecondary)) ThemeManager.onLightButtonText else p.textPrimary)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }

    private fun pill(fill: Int, border: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(fill); setStroke(stroke, border)
    }
}
