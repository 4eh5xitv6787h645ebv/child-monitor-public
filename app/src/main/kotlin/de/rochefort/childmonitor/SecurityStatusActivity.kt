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
import android.os.Bundle
import android.widget.TextView
import de.rochefort.childmonitor.privacy.PrivacyInputs
import de.rochefort.childmonitor.privacy.PrivacyLevel
import de.rochefort.childmonitor.privacy.PrivacyPosture
import de.rochefort.childmonitor.security.AuditLog
import de.rochefort.childmonitor.security.SessionAuthority
import de.rochefort.childmonitor.security.TlsIdentity

/**
 * CM-FEAT-003 (#126) minimal: read-only security status pane.
 * Shows TLS state, active session count, and the last 50 AuditLog events.
 *
 * Full feature set from the issue (biometric-gated revoke actions, integrity
 * checker output, copy-fingerprint button, auto-refresh) is a follow-up — the
 * thing the user actually asked for ("a place to see what's happening
 * security-wise") is fulfilled by the read-only view alone.
 */
class SecurityStatusActivity : Activity() {

    private lateinit var bodyView: TextView

    /** Held so onDestroy can interrupt() an in-flight load if the user
     *  rotates / backgrounds mid-fetch. Stops stacking workers across
     *  rapid resume cycles. */
    @Volatile private var loadThread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bodyView = TextView(this).apply {
            setPadding(48, 48, 48, 48)
            setTextIsSelectable(true)
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = android.widget.ScrollView(this).apply {
            addView(bodyView)
        }
        setContentView(scroll)
        ThemeManager.applyTheme(this)
        title = "Security status"
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        loadThread?.interrupt()
        loadThread = null
        super.onDestroy()
    }

    private fun refresh() {
        // Cancel any in-flight load — rapid resume/destroy cycles would
        // otherwise stack workers each loading the same 1MB audit file.
        loadThread?.interrupt()
        loadThread = Thread {
            val tls = try { TlsIdentity.getInstance(applicationContext) } catch (_: Exception) { null }
            val authority = SessionAuthority.getInstance(applicationContext)
            val sessions = try { authority.listSessions() } catch (_: Exception) { null }
            val sessionCount = sessions?.optInt("count", 0) ?: 0
            val auditTail = try { AuditLog.recent(applicationContext, limit = 50) } catch (_: Exception) { null }

            // #297: honest privacy summary first — derived from the pure PrivacyPosture engine so
            // we never overclaim (live audio is plaintext on the LAN). Flags read from local prefs.
            val updateOn = UpdateChecker.isAutoCheckEnabled(applicationContext)
            // #366: reference MonitorService's own keys (read-only) so a rename can't silently desync.
            val monitorPrefs = getSharedPreferences(MonitorService.MONITOR_PREFS, MODE_PRIVATE)
            val hooksOn = monitorPrefs.getBoolean(MonitorService.PREF_HOOKS_ENABLED, false)
            val hookUrlSet = !monitorPrefs.getString(MonitorService.PREF_HOOKS_URL, null).isNullOrBlank()
            val facts = PrivacyPosture.evaluate(
                PrivacyInputs(
                    updateCheckEnabled = updateOn,
                    eventHooksEnabled = hooksOn,
                    eventHookUrlConfigured = hookUrlSet,
                )
            )

            val sb = StringBuilder()
            sb.append("=== Privacy ===\n")
            for (f in facts) {
                val mark = when (f.level) {
                    PrivacyLevel.GOOD -> "[OK]  "
                    PrivacyLevel.CAUTION -> "[!]   "
                    PrivacyLevel.INFO -> "[i]   "
                }
                sb.append(mark).append(f.title).append('\n')
                sb.append("      ").append(f.detail).append("\n\n")
            }
            sb.append("=== TLS ===\n")
            sb.append("Ready: ${tls?.isReady() ?: false}\n")
            tls?.fingerprintSha256()?.let { sb.append("SHA-256: $it\n") }
            tls?.lastInitError?.let { sb.append("Last error: $it\n") }
            sb.append("\n=== Sessions ===\n")
            sb.append("Active: $sessionCount\n")
            sessions?.optJSONArray("sessions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    sb.append("  ")
                        .append(s.optString("deviceId").take(12))
                        .append(" exp=")
                        .append(java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                            .format(java.util.Date(s.optLong("expiresAtMs"))))
                        .append('\n')
                }
            }
            sb.append("\n=== Audit log (last ${auditTail?.length() ?: 0}) ===\n")
            if (auditTail != null) {
                for (i in 0 until auditTail.length()) {
                    val e = auditTail.optJSONObject(i) ?: continue
                    sb.append(e.optString("ts").substringAfter('T').substringBefore('+').take(8))
                        .append(' ')
                        .append(e.optString("event"))
                    val keys = e.keys()
                    val extras = StringBuilder()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        if (k == "ts" || k == "tsMs" || k == "event") continue
                        if (extras.isNotEmpty()) extras.append(' ')
                        extras.append(k).append('=').append(e.opt(k)?.toString().orEmpty().take(40))
                    }
                    if (extras.isNotEmpty()) sb.append(' ').append(extras)
                    sb.append('\n')
                }
            } else {
                sb.append("(no audit log)\n")
            }

            if (Thread.currentThread().isInterrupted) return@Thread
            val text = sb.toString()
            runOnUiThread {
                if (!isFinishing && !isDestroyed) bodyView.text = text
            }
        }.apply {
            name = "SecurityStatus-load"
            isDaemon = true
            start()
        }
    }
}
