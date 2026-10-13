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
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import de.rochefort.childmonitor.privacy.PrivacyFact
import de.rochefort.childmonitor.privacy.PrivacyInputs
import de.rochefort.childmonitor.privacy.PrivacyLevel
import de.rochefort.childmonitor.privacy.PrivacyPosture

/**
 * #366: a friendly, card-styled privacy screen for non-technical parents. Renders the same honest
 * [PrivacyPosture] facts as the technical [SecurityStatusActivity] but with GOOD/CAUTION/INFO icons
 * and theme colours; "Technical details" drops one tap deeper into [SecurityStatusActivity].
 *
 * Colours are read directly from the active palette (incl. high contrast) and set explicitly, so this
 * screen does NOT call ThemeManager.applyTheme — there's no view tree for it to recolour, and that
 * avoids the luminance-recolour pass fighting these deliberate semantic accent colours.
 */
class PrivacyActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.privacyScreenTitle)
        val p = ThemeManager.getCurrentPalette(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.gradientStart, p.gradientMid, p.gradientEnd),
            )
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.privacyScreenTitle)
            setTextColor(p.textPrimary)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(4))
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.privacyScreenSubtitle)
            setTextColor(p.textSecondary)
            textSize = 14f
            setPadding(dp(4), 0, dp(4), dp(14))
        })

        for (fact in PrivacyPosture.evaluate(currentInputs())) {
            root.addView(factCard(fact, p, ::dp))
        }

        root.addView(Button(this).apply {
            text = getString(R.string.privacyScreenTechnicalDetails)
            isAllCaps = false
            textSize = 15f
            background = pill(p.buttonSecondary, p.buttonSecondaryBorder, dp(12).toFloat(), dp(1))
            setTextColor(
                if (ThemeManager.isLightColorForUi(p.buttonSecondary)) ThemeManager.onLightButtonText else p.textPrimary
            )
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener {
                startActivity(Intent(this@PrivacyActivity, SecurityStatusActivity::class.java))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    /** Reads the same runtime flags the technical pane uses (update check + event hooks). */
    private fun currentInputs(): PrivacyInputs {
        val updateOn = UpdateChecker.isAutoCheckEnabled(applicationContext)
        val monitorPrefs = getSharedPreferences(MonitorService.MONITOR_PREFS, MODE_PRIVATE)
        val hooksOn = monitorPrefs.getBoolean(MonitorService.PREF_HOOKS_ENABLED, false)
        val hookUrlSet = !monitorPrefs.getString(MonitorService.PREF_HOOKS_URL, null).isNullOrBlank()
        return PrivacyInputs(
            updateCheckEnabled = updateOn,
            eventHooksEnabled = hooksOn,
            eventHookUrlConfigured = hookUrlSet,
        )
    }

    /** Accent colour per level — fixed, accessible semantic colours that read on the dark/HC bg. */
    private fun accentColor(level: PrivacyLevel): Int = when (level) {
        PrivacyLevel.GOOD -> Color.parseColor("#6FCF97")    // green
        PrivacyLevel.CAUTION -> Color.parseColor("#F2C94C") // amber
        PrivacyLevel.INFO -> Color.parseColor("#7FB0E0")    // soft blue
    }

    private fun factCard(fact: PrivacyFact, p: ThemeManager.ThemePalette, dp: (Int) -> Int): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = pill(p.surfaceCard, p.surfaceBorder, dp(14).toFloat(), dp(1))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.privacyFactHeading, PrivacyPosture.glyph(fact.level), fact.title)
            setTextColor(accentColor(fact.level))
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
        })
        card.addView(TextView(this).apply {
            text = fact.detail
            setTextColor(p.textSecondary)
            textSize = 14f
            setPadding(0, dp(4), 0, 0)
        })
        return card
    }

    private fun pill(fill: Int, border: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(fill)
        setStroke(stroke, border)
    }
}
