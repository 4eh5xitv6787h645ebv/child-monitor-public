package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager

/**
 * Full-screen red alert surfaced when an anaphylaxis reaction is logged.
 *
 * Implements CM-TRK-REV-027. The only way off this screen is the "my baby is
 * safe now" button — the system back button is intercepted so a parent in
 * panic mode cannot dismiss it accidentally.
 */
class AnaphylaxisActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Make the alert visible even when the device is locked or the
        // app was backgrounded when the allergen was logged (CM-TRK-CR3-P0-1).
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // #190-MED tap-jacking: AnaphylaxisActivity is a life-safety alert; an
        // overlay (SYSTEM_ALERT_WINDOW from another app) could steal touches on
        // the "Call emergency" / "Open Action Plan" buttons and pretend the user
        // dismissed the alert. FLAG_SECURE blocks screenshot/recording of the
        // sensitive medical content; setHideOverlayWindows tells the system to
        // suppress overlays while we're on top (API 31+).
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try { window.setHideOverlayWindows(true) } catch (_: Exception) {}
        }
        setContentView(R.layout.activity_anaphylaxis)
        ThemeManager.applyTheme(this)
        // Block touches that pass through an obscuring window — defence in depth
        // for the buttons (the root view filterTouchesWhenObscured applies to its
        // children with no per-view override).
        findViewById<android.view.View>(android.R.id.content).filterTouchesWhenObscured = true

        findViewById<Button>(R.id.btnCall).setOnClickListener {
            startEmergencyCall()
        }
        findViewById<Button>(R.id.btnOpenPlan).setOnClickListener {
            val planUrl = "https://www.allergy.org.au/images/stories/anaphylaxis/2023/ASCIA_Action_Plan_Allergic_Reactions_Green_2023.pdf"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(planUrl)))
            } catch (_: Exception) {
                // No browser resolves the URL. Mirror the dialer fallback (#180):
                // copy the URL to the clipboard so the parent can paste it on a
                // second device, and surface it via toast so it's at least visible.
                try {
                    val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("ASCIA Action Plan URL", planUrl))
                } catch (_: Exception) {
                    // Clipboard service refused (very rare) — still toast the URL.
                }
                android.widget.Toast.makeText(this,
                    getString(R.string.tracker_anaphylaxis_no_browser, planUrl),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.btnDismiss).setOnClickListener {
            // Require explicit confirmation so a single accidental tap during
            // panic does not dismiss the alert (CM-TRK-CR2-P2-10).
            android.app.AlertDialog.Builder(this)
                .setMessage(R.string.tracker_anaphylaxis_confirm_dismiss)
                .setPositiveButton(R.string.tracker_anaphylaxis_safe) { _, _ ->
                    // #192-MED: cancel the parent's full-screen-intent fallback
                    // notification (AllergenActivity posts it with setOngoing(true)
                    // and ANAPHYLAXIS_NOTIF_ID=0xA11ED) so it doesn't linger in
                    // the shade forever — alarm fatigue. Notification ID matches
                    // AllergenActivity.Companion.ANAPHYLAXIS_NOTIF_ID.
                    try {
                        val nm = getSystemService(NOTIFICATION_SERVICE)
                            as android.app.NotificationManager
                        nm.cancel(0xA11ED)
                    } catch (_: Exception) {}
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun startEmergencyCall() {
        val number = when {
            java.util.Locale.getDefault().country.equals("US", ignoreCase = true) -> "911"
            java.util.Locale.getDefault().country.equals("AU", ignoreCase = true) -> "000"
            else -> "999"
        }
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        } catch (_: Exception) {
            // No dialer — surface the number so the parent can dial it on
            // another phone (CM-TRK-CR2-P2-11).
            android.widget.Toast.makeText(this,
                getString(R.string.tracker_anaphylaxis_no_dialer, number),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Android legacy back callback retained for API 21 compatibility")
    override fun onBackPressed() {
        // Intentionally swallowed: the dismiss button (with explicit
        // confirmation) is the only way out, so a panicked back-press cannot
        // hide the alert. Don't call super — that would finish the activity.
    }
}
