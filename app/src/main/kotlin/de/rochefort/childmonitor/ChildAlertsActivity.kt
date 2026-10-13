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
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * #291 / #308: the parent's in-app view of the connected child's alerting config — per-trigger alert
 * toggles + quiet hours (#291) and event hooks / webhooks (#308) — previously only reachable from
 * the child's web console. Launched from the live [ListenActivity] session; all commands go over the
 * shared pinned/bearer [ParentControlSession] (same transport as [ChildControlsActivity]).
 *
 * Programmatic, theme-aware UI; all network calls run off the UI thread via the session queue.
 */
class ChildAlertsActivity : Activity() {

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_DEVICE_ID = "deviceId"
    }

    private lateinit var session: ParentControlSession
    private lateinit var alertStatus: TextView
    private lateinit var hooksStatus: TextView
    private lateinit var cryBtn: Button
    private lateinit var coughBtn: Button
    private lateinit var motionBtn: Button
    private lateinit var quietBtn: Button
    private lateinit var quietStartBtn: Button
    private lateinit var quietEndBtn: Button
    private lateinit var floorSeek: SeekBar
    private lateinit var hooksBtn: Button
    private lateinit var lanBtn: Button
    private lateinit var urlField: EditText
    private lateinit var mqttStatus: TextView
    private lateinit var mqttBtn: Button
    private lateinit var mqttServer: EditText
    private lateinit var mqttUsername: EditText
    private lateinit var mqttPassword: EditText
    private lateinit var mqttBaseTopic: EditText
    private lateinit var mqttTemperatureTopic: EditText
    private lateinit var mqttHumidityTopic: EditText
    private lateinit var mqttTemperatureMin: EditText
    private lateinit var mqttTemperatureMax: EditText
    private lateinit var mqttHumidityMin: EditText
    private lateinit var mqttHumidityMax: EditText

    // Local UI mirrors; the child's /api/status is the source of truth (refreshStatus reconciles).
    private var cryOn = true
    private var coughOn = true
    private var motionOn = true
    private var quietOn = false
    private var quietStart = "22:00"
    private var quietEnd = "07:00"
    private var floorPct = 50
    private var hooksOn = false
    private var lanOnly = true
    private var mqttOn = false
    @Volatile private var draggingFloor = false
    // Bumped on every floor-slider touch; a status read snapshots it and only seeds when unchanged
    // (and not dragging) so a slow read can't yank the slider back after release (see ChildControls).
    @Volatile private var floorGen = 0
    // Bumped on every discrete user action (toggle / time / URL save+clear); a status read snapshots
    // it and skips the discrete reconcile if it changed, so a slow read can't overwrite a just-tapped
    // toggle or restore a cleared URL with the old value (Codex).
    @Volatile private var discreteGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ParentControlSession(
            this,
            intent.getStringExtra(EXTRA_ADDRESS),
            intent.getStringExtra(EXTRA_DEVICE_ID),
        )
        if (!session.hasTarget()) { finish(); return } // need a reachable, safe child
        title = getString(R.string.childAlertsTitle)

        val p = ThemeManager.getCurrentPalette(this)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.gradientStart, p.gradientMid, p.gradientEnd),
            )
        }
        root.addView(TextView(this).apply {
            text = getString(R.string.childAlertsTitle)
            setTextColor(p.textPrimary); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(12))
        })
        root.addView(buildAlertsCard(p, ::dp))
        root.addView(buildHooksCard(p, ::dp))
        root.addView(buildMqttCard(p, ::dp))
        setContentView(ScrollView(this).apply { addView(root) })

        // Defense-in-depth (security review): warn if this child's control channel is cleartext.
        val warnDeviceId = session.deviceId()
        val hasPin = ParentSessionStore.fingerprintFor(this, warnDeviceId) != null
        if (TransportPolicy.isInsecureCleartext(hasPin, ParentSessionStore.hasEverPinned(this, warnDeviceId))) {
            AppToast.show(this, getString(R.string.insecure_connection_warning), android.widget.Toast.LENGTH_LONG)
        }

        refreshStatus()
    }

    // ---- Alerts (#291) ----
    private fun buildAlertsCard(p: ThemeManager.ThemePalette, dp: (Int) -> Int): View {
        val card = card(p, dp)
        card.addView(header(getString(R.string.childAlertsAlertsHeader), p))
        alertStatus = statusLine(p, dp)
        card.addView(alertStatus)

        cryBtn = toggleButton(getString(R.string.childAlertsCry), cryOn, p, dp) {
            cryOn = !cryOn; setToggle(cryBtn, cryOn); control("/api/alerts/config?cry=$cryOn")
        }
        coughBtn = toggleButton(getString(R.string.childAlertsCough), coughOn, p, dp) {
            coughOn = !coughOn; setToggle(coughBtn, coughOn); control("/api/alerts/config?cough=$coughOn")
        }
        motionBtn = toggleButton(getString(R.string.childAlertsMotion), motionOn, p, dp) {
            motionOn = !motionOn; setToggle(motionBtn, motionOn); control("/api/alerts/config?motion=$motionOn")
        }
        quietBtn = toggleButton(getString(R.string.childAlertsQuiet), quietOn, p, dp) {
            quietOn = !quietOn; setToggle(quietBtn, quietOn); control("/api/alerts/config?quiet=$quietOn")
        }
        card.addView(cryBtn); card.addView(coughBtn); card.addView(motionBtn); card.addView(quietBtn)

        // Quiet-hours window (start / end) as two time-picker buttons side by side.
        val times = hrow(dp)
        quietStartBtn = themedButton(quietLabel(R.string.childAlertsQuietStart, quietStart), p, dp) {
            pickTime(quietStart) { hhmm ->
                quietStart = hhmm; quietStartBtn.text = quietLabel(R.string.childAlertsQuietStart, hhmm)
                control("/api/alerts/config?quietStart=${ParentControlClient.enc(hhmm)}")
            }
        }
        quietEndBtn = themedButton(quietLabel(R.string.childAlertsQuietEnd, quietEnd), p, dp) {
            pickTime(quietEnd) { hhmm ->
                quietEnd = hhmm; quietEndBtn.text = quietLabel(R.string.childAlertsQuietEnd, hhmm)
                control("/api/alerts/config?quietEnd=${ParentControlClient.enc(hhmm)}")
            }
        }
        times.addView(quietStartBtn); times.addView(quietEndBtn)
        card.addView(times)

        card.addView(TextView(this).apply {
            text = getString(R.string.childAlertsFloor)
            setTextColor(p.textSecondary); textSize = 13f; setPadding(0, dp(10), 0, 0)
        })
        floorSeek = SeekBar(this).apply {
            max = 100; progress = floorPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) { floorPct = value }
                override fun onStartTrackingTouch(s: SeekBar?) { draggingFloor = true; floorGen++ }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    draggingFloor = false; floorGen++
                    control("/api/alerts/config?floor=${floorPct / 100f}")
                }
            })
        }
        card.addView(floorSeek)
        return card
    }

    // ---- Event hooks (#308) ----
    private fun buildHooksCard(p: ThemeManager.ThemePalette, dp: (Int) -> Int): View {
        val card = card(p, dp)
        card.addView(header(getString(R.string.childAlertsHooksHeader), p))
        hooksStatus = statusLine(p, dp)
        card.addView(hooksStatus)

        hooksBtn = toggleButton(getString(R.string.childAlertsHooksEnabled), hooksOn, p, dp) {
            hooksOn = !hooksOn; setToggle(hooksBtn, hooksOn); control("/api/hooks?enabled=$hooksOn")
        }
        card.addView(hooksBtn)

        urlField = EditText(this).apply {
            hint = getString(R.string.childAlertsWebhookHint)
            setHintTextColor(p.textSecondary); setTextColor(p.textPrimary); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            background = pill(p.surfaceCard, p.surfaceBorder, dp(10).toFloat(), dp(1))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        card.addView(urlField)

        val urlRow = hrow(dp)
        urlRow.addView(themedButton(getString(R.string.childAlertsSaveUrl), p, dp) { saveWebhookUrl() })
        urlRow.addView(themedButton(getString(R.string.childAlertsClearUrl), p, dp) {
            urlField.setText("")
            control("/api/hooks?webhookUrl=") // blank clears the URL on the child
        })
        card.addView(urlRow)

        lanBtn = toggleButton(getString(R.string.childAlertsLanOnly), lanOnly, p, dp) {
            lanOnly = !lanOnly; setToggle(lanBtn, lanOnly); control("/api/hooks?lanOnly=$lanOnly")
        }
        card.addView(lanBtn)

        card.addView(themedButton(getString(R.string.childAlertsSendTest), p, dp) { sendTest() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        })
        return card
    }

    // ---- MQTT / Home Assistant (#305/#315) ----
    private fun buildMqttCard(p: ThemeManager.ThemePalette, dp: (Int) -> Int): View {
        val card = card(p, dp)
        card.addView(header(getString(R.string.childAlertsMqttHeader), p))
        mqttStatus = statusLine(p, dp)
        card.addView(mqttStatus)
        mqttBtn = toggleButton(getString(R.string.childAlertsMqttEnabled), mqttOn, p, dp) {
            mqttOn = !mqttOn
            setToggle(mqttBtn, mqttOn)
            mqttControl(mapOf("X-CM-MQTT-enabled" to mqttOn.toString()))
        }
        card.addView(mqttBtn)

        mqttServer = mqttField(R.string.childAlertsMqttServer, p, dp)
        mqttUsername = mqttField(R.string.childAlertsMqttUsername, p, dp)
        mqttPassword = mqttField(R.string.childAlertsMqttPassword, p, dp).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        mqttBaseTopic = mqttField(R.string.childAlertsMqttBaseTopic, p, dp)
        mqttTemperatureTopic = mqttField(R.string.childAlertsMqttTemperatureTopic, p, dp)
        mqttHumidityTopic = mqttField(R.string.childAlertsMqttHumidityTopic, p, dp)
        mqttTemperatureMin = mqttNumberField(R.string.childAlertsMqttTemperatureMin, p, dp)
        mqttTemperatureMax = mqttNumberField(R.string.childAlertsMqttTemperatureMax, p, dp)
        mqttHumidityMin = mqttNumberField(R.string.childAlertsMqttHumidityMin, p, dp)
        mqttHumidityMax = mqttNumberField(R.string.childAlertsMqttHumidityMax, p, dp)
        listOf(
            mqttServer, mqttUsername, mqttPassword, mqttBaseTopic, mqttTemperatureTopic,
            mqttHumidityTopic, mqttTemperatureMin, mqttTemperatureMax, mqttHumidityMin,
            mqttHumidityMax,
        ).forEach(card::addView)

        val actions = hrow(dp)
        actions.addView(themedButton(getString(R.string.childAlertsMqttSave), p, dp) { saveMqtt() })
        actions.addView(themedButton(getString(R.string.childAlertsMqttClearPassword), p, dp) {
            mqttPassword.setText("")
            mqttControl(mapOf("X-CM-MQTT-password-action" to "clear"))
        })
        card.addView(actions)
        card.addView(TextView(this).apply {
            text = getString(R.string.childAlertsMqttTlsHelp)
            setTextColor(p.textSecondary); textSize = 12f; setPadding(0, dp(8), 0, 0)
        })
        return card
    }

    private fun mqttField(hintId: Int, p: ThemeManager.ThemePalette, dp: (Int) -> Int) = EditText(this).apply {
        hint = getString(hintId)
        setHintTextColor(p.textSecondary); setTextColor(p.textPrimary); textSize = 14f
        inputType = InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        background = pill(p.surfaceCard, p.surfaceBorder, dp(10).toFloat(), dp(1))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(7) }
    }

    private fun mqttNumberField(hintId: Int, p: ThemeManager.ThemePalette, dp: (Int) -> Int) =
        mqttField(hintId, p, dp).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                InputType.TYPE_NUMBER_FLAG_SIGNED
        }

    private fun saveMqtt() {
        val password = mqttPassword.text?.toString().orEmpty()
        val headers = linkedMapOf(
            "X-CM-MQTT-enabled" to mqttOn.toString(),
            "X-CM-MQTT-server" to mqttServer.text.toString().trim(),
            "X-CM-MQTT-username" to mqttUsername.text.toString().trim(),
            "X-CM-MQTT-base-topic" to mqttBaseTopic.text.toString().trim(),
            "X-CM-MQTT-temperature-topic" to mqttTemperatureTopic.text.toString().trim(),
            "X-CM-MQTT-humidity-topic" to mqttHumidityTopic.text.toString().trim(),
            "X-CM-MQTT-temperature-min" to mqttTemperatureMin.text.toString().trim(),
            "X-CM-MQTT-temperature-max" to mqttTemperatureMax.text.toString().trim(),
            "X-CM-MQTT-humidity-min" to mqttHumidityMin.text.toString().trim(),
            "X-CM-MQTT-humidity-max" to mqttHumidityMax.text.toString().trim(),
            "X-CM-MQTT-password-action" to if (password.isBlank()) "keep" else "set",
        )
        if (password.isNotBlank()) headers["X-CM-MQTT-password"] = password
        mqttControl(headers)
    }

    private fun mqttControl(headers: Map<String, String>) {
        discreteGen++
        session.submit {
            val ok = session.resolveClient()?.postWithHeaders("/api/mqtt/config", headers) != null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (ok) mqttPassword.setText("")
                else AppToast.show(this, getString(R.string.childControlsFailed), android.widget.Toast.LENGTH_SHORT)
            }
            fetchAndApplyStatus()
        }
    }

    private fun saveWebhookUrl() {
        val url = urlField.text?.toString()?.trim().orEmpty()
        if (!ChildAlertsLogic.isLikelyWebhookUrl(url)) {
            AppToast.show(this, getString(R.string.childAlertsBadUrl), android.widget.Toast.LENGTH_SHORT)
            return
        }
        control("/api/hooks?webhookUrl=${ParentControlClient.enc(url)}")
    }

    private fun sendTest() {
        session.submit {
            val result = session.resolveClient()?.post("/api/hooks/test")
            val ok = result?.optBoolean("success", false) == true
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val msg = if (ok) R.string.childAlertsTestSent else R.string.childAlertsTestFailed
                AppToast.show(this, getString(msg), android.widget.Toast.LENGTH_SHORT)
            }
            fetchAndApplyStatus()
        }
    }

    private fun pickTime(current: String, onPicked: (String) -> Unit) {
        val (h, m) = ChildAlertsLogic.parseHhmm(current) ?: (22 to 0)
        TimePickerDialog(this, { _, hour, minute ->
            onPicked(ChildAlertsLogic.formatHhmm(hour, minute))
        }, h, m, true).show()
    }

    private fun quietLabel(resId: Int, hhmm: String) = getString(resId, hhmm)

    // ---- networking ----

    /** Fire a config POST then refresh status, serialized on the shared session queue (Codex). */
    private fun control(path: String) {
        discreteGen++ // user changed a discrete control; invalidate any in-flight status seed
        session.submit {
            val ok = session.resolveClient()?.post(path) != null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) AppToast.show(this, getString(R.string.childControlsFailed), android.widget.Toast.LENGTH_SHORT)
            }
            fetchAndApplyStatus()
        }
    }

    private fun refreshStatus() = session.submit { fetchAndApplyStatus() }

    /** Blocking status GET + UI apply. MUST run on the session queue. */
    private fun fetchAndApplyStatus() {
        val fGen = floorGen
        val dGen = discreteGen
        val status = session.resolveClient()?.get("/api/status")
        val alert = status?.optJSONObject("alertConfig")
        val quiet = alert?.optJSONObject("quietHours")
        val hooks = status?.optJSONObject("hooks")
        val mqtt = status?.optJSONObject("mqtt")

        val cry = alert?.optBoolean("cryAlerts", cryOn) ?: cryOn
        val cough = alert?.optBoolean("coughAlerts", coughOn) ?: coughOn
        val motion = alert?.optBoolean("motionAlerts", motionOn) ?: motionOn
        val qEnabled = quiet?.optBoolean("enabled", quietOn) ?: quietOn
        val qStart = quiet?.optString("start", quietStart)?.takeIf { it.isNotBlank() } ?: quietStart
        val qEnd = quiet?.optString("end", quietEnd)?.takeIf { it.isNotBlank() } ?: quietEnd
        val floor = quiet?.let { if (it.has("escalationFloor")) ChildControlsLogic.sliderPercent(it.optDouble("escalationFloor", -1.0)) else ChildControlsLogic.NO_VALUE } ?: ChildControlsLogic.NO_VALUE
        val activeNow = quiet?.optBoolean("activeNow", false) ?: false

        val hEnabled = hooks?.optBoolean("enabled", hooksOn) ?: hooksOn
        val hLanOnly = hooks?.optBoolean("lanOnly", lanOnly) ?: lanOnly
        val hUrl = hooks?.let { if (it.isNull("webhookUrl")) "" else it.optString("webhookUrl", "") }
        val mqttEnabled = mqtt?.optBoolean("enabled", mqttOn) ?: mqttOn
        val mqttConnected = mqtt?.optBoolean("connected", false) ?: false

        val alertText = if (alert == null) getString(R.string.childControlsUnknown) else {
            val q = if (qEnabled) getString(R.string.childAlertsQuietSummary, qStart, qEnd) +
                (if (activeNow) getString(R.string.childAlertsQuietActive) else "") else getString(R.string.childAlertsQuietOff)
            getString(R.string.childAlertsSummary, onOff(cry), onOff(cough), onOff(motion)) + " · " + q
        }
        val hooksText = when {
            hooks == null -> getString(R.string.childControlsUnknown)
            hEnabled -> getString(R.string.childAlertsHooksOn, if (hLanOnly) getString(R.string.childAlertsLanShort) else getString(R.string.childAlertsWanShort))
            else -> getString(R.string.childAlertsHooksOff)
        }
        val mqttText = when {
            mqtt == null -> getString(R.string.childControlsUnknown)
            mqttConnected -> getString(R.string.childAlertsMqttConnected)
            mqttEnabled -> getString(R.string.childAlertsMqttDisconnected)
            else -> getString(R.string.childAlertsMqttOff)
        }

        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            // Floor slider has its own drag-aware guard, independent of the discrete edits below.
            if (ChildControlsLogic.shouldSeed(draggingFloor, fGen, floorGen, floor)) {
                floorPct = floor; floorSeek.progress = floor
            }
            // Skip the discrete reconcile if the user toggled/edited anything since this read began,
            // so a slow read can't overwrite a just-tapped toggle or restore a cleared URL (Codex).
            if (dGen != discreteGen) return@runOnUiThread
            cryOn = cry; coughOn = cough; motionOn = motion; quietOn = qEnabled; quietStart = qStart; quietEnd = qEnd
            hooksOn = hEnabled; lanOnly = hLanOnly
            mqttOn = mqttEnabled
            alertStatus.text = alertText
            hooksStatus.text = hooksText
            mqttStatus.text = mqttText
            setToggle(cryBtn, cry); setToggle(coughBtn, cough); setToggle(motionBtn, motion); setToggle(quietBtn, qEnabled)
            setToggle(hooksBtn, hEnabled); setToggle(lanBtn, hLanOnly)
            setToggle(mqttBtn, mqttEnabled)
            quietStartBtn.text = quietLabel(R.string.childAlertsQuietStart, qStart)
            quietEndBtn.text = quietLabel(R.string.childAlertsQuietEnd, qEnd)
            // A failed/missing hooks read must not blank a saved URL: only apply when we have status
            // and the user isn't editing the field.
            if (hooks != null && !urlField.hasFocus()) urlField.setText(hUrl ?: "")
            if (mqtt != null) {
                fun seed(field: EditText, key: String, fallback: String = "") {
                    if (!field.hasFocus()) field.setText(mqtt.optString(key, fallback))
                }
                seed(mqttServer, "server")
                seed(mqttUsername, "username")
                seed(mqttBaseTopic, "baseTopic")
                seed(mqttTemperatureTopic, "temperatureTopic")
                seed(mqttHumidityTopic, "humidityTopic")
                seed(mqttTemperatureMin, "temperatureMinC", "16")
                seed(mqttTemperatureMax, "temperatureMaxC", "24")
                seed(mqttHumidityMin, "humidityMinPct", "30")
                seed(mqttHumidityMax, "humidityMaxPct", "70")
            }
        }
    }

    private fun onOff(v: Boolean) = getString(if (v) R.string.childAlertsOn else R.string.childAlertsOff)

    // ---- UI helpers ----
    private fun setToggle(b: Button, on: Boolean) {
        b.text = getString(if (on) R.string.childAlertsToggleOn else R.string.childAlertsToggleOff, b.tag as? String ?: "")
        b.alpha = if (on) 1f else 0.55f
    }

    private fun toggleButton(label: String, initial: Boolean, p: ThemeManager.ThemePalette, dp: (Int) -> Int, onClick: () -> Unit): Button {
        val b = Button(this).apply {
            tag = label; isAllCaps = false; textSize = 14f
            background = pill(p.buttonSecondary, p.buttonSecondaryBorder, dp(10).toFloat(), dp(1))
            setTextColor(if (ThemeManager.isLightColorForUi(p.buttonSecondary)) ThemeManager.onLightButtonText else p.textPrimary)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        setToggle(b, initial)
        return b
    }

    private fun card(p: ThemeManager.ThemePalette, dp: (Int) -> Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = pill(p.surfaceCard, p.surfaceBorder, dp(14).toFloat(), dp(1))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(12) }
    }

    private fun header(text: String, p: ThemeManager.ThemePalette) = TextView(this).apply {
        this.text = text; setTextColor(p.textPrimary); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
    }

    private fun statusLine(p: ThemeManager.ThemePalette, dp: (Int) -> Int) = TextView(this).apply {
        text = getString(R.string.childControlsUnknown)
        setTextColor(p.textSecondary); textSize = 13f; setPadding(0, dp(2), 0, dp(8))
    }

    private fun hrow(dp: (Int) -> Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6) }
    }

    private fun themedButton(label: String, p: ThemeManager.ThemePalette, dp: (Int) -> Int, onClick: () -> Unit) =
        Button(this).apply {
            text = label; isAllCaps = false; textSize = 13f
            background = pill(p.buttonSecondary, p.buttonSecondaryBorder, dp(10).toFloat(), dp(1))
            setTextColor(if (ThemeManager.isLightColorForUi(p.buttonSecondary)) ThemeManager.onLightButtonText else p.textPrimary)
            setPadding(dp(6), dp(10), dp(6), dp(10))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(3); marginEnd = dp(3) }
        }

    private fun pill(fill: Int, border: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; cornerRadius = radius; setColor(fill); setStroke(stroke, border)
    }
}
