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
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #311/#312 (and #328 part 3): the parent's in-app remote controls for the connected child's
 * comfort features — night light and soother — that were previously only reachable from the child's
 * web console. Launched from the live [ListenActivity] session, which passes the child's [address]
 * and [deviceId]; all commands go over the same pinned/bearer transport via [ParentControlClient].
 *
 * Programmatic, theme-aware UI (reads the active palette incl. high contrast) and all network calls
 * run off the UI thread. Alert-config (#291) and event-hooks (#308) panels are a planned follow-up.
 */
class ChildControlsActivity : Activity() {

    companion object {
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_DEVICE_ID = "deviceId"
        private const val STATUS_REFRESH_MS = 5_000L
    }

    // Shared control-plane session: resolves the live child + serializes requests (see the class).
    private lateinit var session: ParentControlSession
    private lateinit var nightStatus: TextView
    private lateinit var sootherStatus: TextView
    private lateinit var nightTimerStatus: TextView
    private lateinit var sootherTimerStatus: TextView
    private lateinit var brightnessSeek: SeekBar
    private lateinit var volumeSeek: SeekBar
    private var brightnessPct = 70
    private var volumePct = 60
    @Volatile private var nightLightOn = false
    @Volatile private var sootherPlaying = false
    private var nightTimerChoice: TimerChoice? = null
    private var sootherTimerChoice: TimerChoice? = null
    @Volatile private var draggingBrightness = false
    @Volatile private var draggingVolume = false
    // Bumped on every slider touch (start AND stop). A status fetch snapshots the value when it
    // begins; its seed is applied only if no touch happened since (and the slider isn't being
    // dragged), so a status read that started before a drag can't yank the slider back after release
    // or clobber the cached pct that the next button tap sends (Codex).
    @Volatile private var brightnessGen = 0
    @Volatile private var volumeGen = 0
    @Volatile private var nightTimerGen = 0
    @Volatile private var sootherTimerGen = 0
    private val statusHandler = Handler(Looper.getMainLooper())
    private val statusRefreshQueued = AtomicBoolean(false)
    private val statusRefresh = object : Runnable {
        override fun run() {
            if (!isFinishing && !isDestroyed) {
                refreshStatus()
                statusHandler.postDelayed(this, STATUS_REFRESH_MS)
            }
        }
    }

    private enum class TimerTarget { NIGHT_LIGHT, SOOTHER }

    /** A duration starts when the feature starts; a clock choice retains its absolute local time. */
    private data class TimerChoice(
        val durationMs: Long? = null,
        val deadlineMs: Long? = null,
        val pending: Boolean,
    ) {
        fun requestedDurationMs(nowMs: Long): Long? = BedtimeTimerPolicy.clampDurationMs(
            durationMs ?: deadlineMs?.minus(nowMs),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = ParentControlSession(
            this,
            intent.getStringExtra(EXTRA_ADDRESS),
            intent.getStringExtra(EXTRA_DEVICE_ID),
        )
        if (!session.hasTarget()) { finish(); return } // need a reachable, safe child to control
        title = getString(R.string.childControlsTitle)

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
            text = getString(R.string.childControlsTitle)
            setTextColor(p.textPrimary); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(4), 0, dp(4), dp(12))
        })

        root.addView(buildNightLightCard(p, ::dp))
        root.addView(buildSootherCard(p, ::dp))

        setContentView(ScrollView(this).apply { addView(root) })

        // Defense-in-depth (security review): warn if THIS child's control channel is cleartext
        // (never pinned), so the warning is bound to this screen, not just the launching session.
        val warnDeviceId = session.deviceId()
        val hasPin = ParentSessionStore.fingerprintFor(this, warnDeviceId) != null
        if (TransportPolicy.isInsecureCleartext(hasPin, ParentSessionStore.hasEverPinned(this, warnDeviceId))) {
            AppToast.show(this, getString(R.string.insecure_connection_warning), android.widget.Toast.LENGTH_LONG)
        }

    }

    override fun onStart() {
        super.onStart()
        statusHandler.removeCallbacks(statusRefresh)
        statusRefresh.run()
    }

    override fun onStop() {
        statusHandler.removeCallbacks(statusRefresh)
        super.onStop()
    }

    // ---- Night light (#312) ----
    private fun buildNightLightCard(p: ThemeManager.ThemePalette, dp: (Int) -> Int): View {
        val card = card(p, dp)
        card.addView(header(getString(R.string.childControlsNightLight), p))
        nightStatus = statusLine(p, dp)
        card.addView(nightStatus)

        val row = hrow(dp)
        row.addView(themedButton(getString(R.string.childControlsColorAmber), p, dp) { nightOn("amber") })
        row.addView(themedButton(getString(R.string.childControlsColorWarm), p, dp) { nightOn("#FFB870") })
        row.addView(themedButton(getString(R.string.childControlsColorRed), p, dp) { nightOn("#FF5A4D") })
        row.addView(themedButton(getString(R.string.childControlsColorOff), p, dp) { nightOff() })
        card.addView(row)

        card.addView(TextView(this).apply {
            text = getString(R.string.childControlsBrightness)
            setTextColor(p.textSecondary); textSize = 13f; setPadding(0, dp(10), 0, 0)
        })
        brightnessSeek = SeekBar(this).apply {
            max = 100; progress = brightnessPct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) { brightnessPct = value }
                override fun onStartTrackingTouch(s: SeekBar?) { draggingBrightness = true; brightnessGen++ }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    draggingBrightness = false; brightnessGen++
                    control("/api/nightlight?brightness=${brightnessPct / 100f}")
                }
            })
        }
        card.addView(brightnessSeek)
        card.addView(buildTimerControls(TimerTarget.NIGHT_LIGHT, p, dp))
        return card
    }

    private fun nightOn(color: String) {
        nightLightOn = true
        renderTimerStatus(TimerTarget.NIGHT_LIGHT)
        val timer = timerParameter(nightTimerChoice)
        control(
            "/api/nightlight?on=true&color=${ParentControlClient.enc(color)}" +
                "&brightness=${brightnessPct / 100f}$timer",
        )
    }

    private fun nightOff() {
        nightLightOn = false
        nightTimerChoice = null
        nightTimerGen++
        renderTimerStatus(TimerTarget.NIGHT_LIGHT)
        control("/api/nightlight?on=false")
    }

    // ---- Soother (#311/#328) ----
    private fun buildSootherCard(p: ThemeManager.ThemePalette, dp: (Int) -> Int): View {
        val card = card(p, dp)
        card.addView(header(getString(R.string.childControlsSoother), p))
        sootherStatus = statusLine(p, dp)
        card.addView(sootherStatus)

        val noise = hrow(dp)
        noise.addView(themedButton(getString(R.string.childControlsNoiseWhite), p, dp) { playSoother("WHITE") })
        noise.addView(themedButton(getString(R.string.childControlsNoisePink), p, dp) { playSoother("PINK") })
        noise.addView(themedButton(getString(R.string.childControlsNoiseBrown), p, dp) { playSoother("BROWN") })
        card.addView(noise)
        val tunes = hrow(dp)
        tunes.addView(themedButton(getString(R.string.childControlsTuneTwinkle), p, dp) { playSoother("TWINKLE") })
        tunes.addView(themedButton(getString(R.string.childControlsTuneFrere), p, dp) { playSoother("FRERE_JACQUES") })
        tunes.addView(themedButton(getString(R.string.childControlsTuneMary), p, dp) { playSoother("MARY") })
        card.addView(tunes)
        card.addView(themedButton(getString(R.string.childControlsTuneBrahms), p, dp) {
            playSoother("BRAHMS")
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        })
        card.addView(TextView(this).apply {
            text = getString(R.string.childControlsBrahmsAttribution)
            setTextColor(ContextCompat.getColor(this@ChildControlsActivity, R.color.text_hint))
            textSize = 11f
            setPadding(0, dp(4), 0, 0)
        })

        card.addView(TextView(this).apply {
            text = getString(R.string.childControlsVolume)
            setTextColor(p.textSecondary); textSize = 13f; setPadding(0, dp(10), 0, 0)
        })
        volumeSeek = SeekBar(this).apply {
            max = 100; progress = volumePct
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) { volumePct = value }
                override fun onStartTrackingTouch(s: SeekBar?) { draggingVolume = true; volumeGen++ }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    draggingVolume = false; volumeGen++
                    control("/api/soother?action=volume&volume=${volumePct / 100f}")
                }
            })
        }
        card.addView(volumeSeek)

        card.addView(buildTimerControls(TimerTarget.SOOTHER, p, dp))

        card.addView(themedButton(getString(R.string.childControlsStop), p, dp) { stopSoother() }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
        })
        return card
    }

    private fun playSoother(sound: String) {
        val timer = timerParameter(sootherTimerChoice)
        // Optimistic so a timer tap immediately after this one is queued behind the play request
        // instead of being lost while the first status refresh is still in flight.
        sootherPlaying = true
        renderTimerStatus(TimerTarget.SOOTHER)
        control("/api/soother?action=play&sound=${ParentControlClient.enc(sound)}&volume=${volumePct / 100f}$timer")
    }

    private fun stopSoother() {
        sootherPlaying = false
        sootherTimerChoice = null
        sootherTimerGen++
        renderTimerStatus(TimerTarget.SOOTHER)
        control("/api/soother?action=stop")
    }

    private fun buildTimerControls(
        target: TimerTarget,
        p: ThemeManager.ThemePalette,
        dp: (Int) -> Int,
    ): View = LinearLayout(this).apply {
        id = if (target == TimerTarget.NIGHT_LIGHT) R.id.nightLightTimerControls else R.id.sootherTimerControls
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@ChildControlsActivity).apply {
            text = getString(
                if (target == TimerTarget.NIGHT_LIGHT) R.string.childControlsNightTimer
                else R.string.childControlsSoundTimer,
            )
            setTextColor(p.textSecondary)
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        })
        val timerStatus = statusLine(p, dp).apply {
            id = if (target == TimerTarget.NIGHT_LIGHT) R.id.nightLightTimerStatus else R.id.sootherTimerStatus
            text = getString(R.string.childControlsTimerNone)
            setPadding(0, dp(2), 0, 0)
        }
        if (target == TimerTarget.NIGHT_LIGHT) nightTimerStatus = timerStatus else sootherTimerStatus = timerStatus
        addView(timerStatus)
        addView(hrow(dp).apply {
            addView(themedButton(getString(R.string.childControlsTimerFor), p, dp) {
                showDurationDialog(target)
            }.apply {
                id = if (target == TimerTarget.NIGHT_LIGHT) R.id.nightLightTimerFor else R.id.sootherTimerFor
            })
            addView(themedButton(getString(R.string.childControlsTimerUntil), p, dp) {
                showClockDialog(target)
            }.apply {
                id = if (target == TimerTarget.NIGHT_LIGHT) R.id.nightLightTimerUntil else R.id.sootherTimerUntil
            })
            addView(themedButton(getString(R.string.childControlsTimerClear), p, dp) {
                setTimerChoice(target, null)
            }.apply {
                id = if (target == TimerTarget.NIGHT_LIGHT) R.id.nightLightTimerClear else R.id.sootherTimerClear
            })
        })
    }

    private fun showDurationDialog(target: TimerTarget) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.childControlsTimerMinutesHint)
            setSelectAllOnFocus(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.childControlsTimerDurationTitle)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.childControlsTimerSet, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val duration = ChildControlsLogic.durationFromMinutes(input.text.toString().toIntOrNull())
                if (duration == null) {
                    input.error = getString(R.string.childControlsTimerMinutesError)
                } else {
                    setTimerChoice(target, TimerChoice(durationMs = duration, pending = true))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun showClockDialog(target: TimerTarget) {
        val now = Calendar.getInstance()
        TimePickerDialog(
            this,
            { _, hour, minute ->
                val selectedAt = System.currentTimeMillis()
                val duration = ChildControlsLogic.durationUntilClockTime(selectedAt, hour, minute)
                    ?: return@TimePickerDialog
                setTimerChoice(
                    target,
                    TimerChoice(deadlineMs = selectedAt + duration, pending = true),
                )
            },
            now.get(Calendar.HOUR_OF_DAY),
            now.get(Calendar.MINUTE),
            android.text.format.DateFormat.is24HourFormat(this),
        ).show()
    }

    private fun setTimerChoice(target: TimerTarget, choice: TimerChoice?) {
        when (target) {
            TimerTarget.NIGHT_LIGHT -> {
                nightTimerChoice = choice
                nightTimerGen++
            }
            TimerTarget.SOOTHER -> {
                sootherTimerChoice = choice
                sootherTimerGen++
            }
        }
        renderTimerStatus(target)
        val active = if (target == TimerTarget.NIGHT_LIGHT) nightLightOn else sootherPlaying
        if (choice != null && !active) return
        val duration = choice?.requestedDurationMs(System.currentTimeMillis()) ?: 0L
        val path = if (target == TimerTarget.NIGHT_LIGHT) {
            "/api/nightlight?durationMs=$duration"
        } else {
            "/api/soother?action=timer&durationMs=$duration"
        }
        control(path)
    }

    private fun timerParameter(choice: TimerChoice?): String {
        val duration = choice?.requestedDurationMs(System.currentTimeMillis()) ?: return ""
        return "&durationMs=$duration"
    }

    private fun renderTimerStatus(target: TimerTarget) {
        val choice = if (target == TimerTarget.NIGHT_LIGHT) nightTimerChoice else sootherTimerChoice
        val active = if (target == TimerTarget.NIGHT_LIGHT) nightLightOn else sootherPlaying
        val view = if (target == TimerTarget.NIGHT_LIGHT) nightTimerStatus else sootherTimerStatus
        if (choice == null) {
            view.text = getString(R.string.childControlsTimerNone)
            return
        }
        if (!active && choice.durationMs != null) {
            val minutes = ChildControlsLogic.minutesRemaining(choice.durationMs)
            view.text = resources.getQuantityString(
                R.plurals.childControlsTimerWhenStarted,
                minutes.toInt(),
                minutes,
            )
            return
        }
        val remaining = choice.requestedDurationMs(System.currentTimeMillis()) ?: 0L
        val minutes = ChildControlsLogic.minutesRemaining(remaining)
        val deadline = choice.deadlineMs ?: (System.currentTimeMillis() + remaining)
        val clock = android.text.format.DateFormat.getTimeFormat(this).format(Date(deadline))
        view.text = resources.getQuantityString(
            R.plurals.childControlsTimerStatus,
            minutes.toInt(),
            clock,
            minutes,
        )
    }

    // ---- networking ----

    /** Fire a control POST then refresh both status lines, serialized on the shared session queue so
     *  taps and the follow-up status read reach the child in tap order (Codex). */
    private fun control(path: String) {
        session.submit {
            val ok = session.resolveClient()?.post(path) != null
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!ok) AppToast.show(this, getString(R.string.childControlsFailed), android.widget.Toast.LENGTH_SHORT)
            }
            fetchAndApplyStatus()
        }
    }

    /** Enqueue at most one poll, so an unreachable child cannot build an unbounded request backlog. */
    private fun refreshStatus() {
        if (!statusRefreshQueued.compareAndSet(false, true)) return
        session.submit {
            try {
                fetchAndApplyStatus()
            } finally {
                statusRefreshQueued.set(false)
            }
        }
    }

    /** Blocking status GET + UI apply. MUST run on the session queue. */
    private fun fetchAndApplyStatus() {
        // Snapshot slider-edit generations before the (slow) GET; a touch during/after invalidates the seed.
        val bGen = brightnessGen
        val vGen = volumeGen
        val nTimerGen = nightTimerGen
        val sTimerGen = sootherTimerGen
        val status = session.resolveClient()?.get("/api/status")
        val night = status?.optJSONObject("nightLight")
        val soother = status?.optJSONObject("soother")
        val fetchedAtMs = System.currentTimeMillis()
        val reportedNightOn = night?.optBoolean("on", false)
        val reportedSootherPlaying = soother?.optBoolean("playing", false)
        val nightRemaining = night?.optLong("remainingMs", 0L)?.coerceAtLeast(0L) ?: 0L
        val sootherRemaining = soother?.optLong("remainingMs", 0L)?.coerceAtLeast(0L) ?: 0L
        // Seed the sliders to the child's actual brightness/volume (0..1) so the UI matches reality.
        val nightBright = night?.let { if (it.has("brightness")) ChildControlsLogic.sliderPercent(it.optDouble("brightness", -1.0)) else ChildControlsLogic.NO_VALUE } ?: ChildControlsLogic.NO_VALUE
        val sootherVol = soother?.let { if (it.has("volume")) ChildControlsLogic.sliderPercent(it.optDouble("volume", -1.0)) else ChildControlsLogic.NO_VALUE } ?: ChildControlsLogic.NO_VALUE
        val nightText = when {
            night == null -> getString(R.string.childControlsUnknown)
            night.optBoolean("on") -> getString(R.string.childControlsNightOn, night.optString("color", "amber"))
            else -> getString(R.string.childControlsNightOff)
        }
        val sootherText = when {
            soother == null -> getString(R.string.childControlsUnknown)
            soother.optBoolean("playing") -> getString(R.string.childControlsSootherOn, soother.optString("sound", "?"))
            else -> getString(R.string.childControlsSootherOff)
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            if (reportedNightOn != null) nightLightOn = reportedNightOn
            if (reportedSootherPlaying != null) sootherPlaying = reportedSootherPlaying
            nightStatus.text = nightText
            sootherStatus.text = sootherText
            // Don't stomp a slider the user is dragging or touched since this read began (Codex).
            if (ChildControlsLogic.shouldSeed(draggingBrightness, bGen, brightnessGen, nightBright)) brightnessSeek.progress = nightBright
            if (ChildControlsLogic.shouldSeed(draggingVolume, vGen, volumeGen, sootherVol)) volumeSeek.progress = sootherVol
            // Active deadlines live on the child and are polled, so either parent can change one
            // and every other parent converges within five seconds. Preserve only an intentional
            // local pending choice while its feature is still off.
            if (night != null && nTimerGen == nightTimerGen) {
                nightTimerChoice = when {
                    nightLightOn && nightRemaining > 0L -> TimerChoice(
                        deadlineMs = fetchedAtMs + nightRemaining,
                        pending = false,
                    )
                    nightTimerChoice?.pending == true && !nightLightOn -> nightTimerChoice
                    else -> null
                }
                renderTimerStatus(TimerTarget.NIGHT_LIGHT)
            }
            if (soother != null && sTimerGen == sootherTimerGen) {
                sootherTimerChoice = when {
                    sootherPlaying && sootherRemaining > 0L -> TimerChoice(
                        deadlineMs = fetchedAtMs + sootherRemaining,
                        pending = false,
                    )
                    sootherTimerChoice?.pending == true && !sootherPlaying -> sootherTimerChoice
                    else -> null
                }
                renderTimerStatus(TimerTarget.SOOTHER)
            }
        }
    }

    // ---- UI helpers ----
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
