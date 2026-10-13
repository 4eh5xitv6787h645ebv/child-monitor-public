package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import java.util.WeakHashMap

/**
 * Dependency-free, TalkBack-visible five-second undo surface for tracker writes.
 *
 * Toasts cannot expose an action and adding the full Material dependency solely for Snackbar would
 * materially increase this small offline APK. This bar attaches to Android's content frame, uses a
 * real 48dp button, announces itself as an assertive live region, and removes all callbacks when a
 * newer tracker operation replaces it.
 */
object TrackerUndoBar {
    private const val UNDO_WINDOW_MS = 5_000L
    private val handler = Handler(Looper.getMainLooper())
    private val pending = WeakHashMap<Activity, Pending>()

    private data class Pending(val view: View, val timeout: Runnable)

    fun show(
        activity: Activity,
        message: CharSequence,
        onUndo: () -> Unit,
        onTimeout: () -> Unit = {},
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "undo UI must be shown on main" }
        dismiss(activity)

        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        val palette = ThemeManager.getCurrentPalette(activity)
        val bar = LinearLayout(activity).apply {
            id = R.id.trackerUndoBar
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            setBackgroundColor(palette.surfaceCard)
            elevation = dp(8).toFloat()
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val text = TextView(activity).apply {
            this.text = message
            setTextColor(palette.textPrimary)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val undo = Button(activity).apply {
            id = R.id.trackerUndoButton
            setText(R.string.tracker_undo)
            minWidth = dp(64)
            minHeight = dp(48)
            contentDescription = activity.getString(R.string.tracker_undo_accessibility, message)
            backgroundTintList = ColorStateList.valueOf(palette.buttonPrimary)
            setTextColor(
                if (ThemeManager.isLightColorForUi(palette.buttonPrimary)) {
                    ThemeManager.onLightButtonText
                } else {
                    palette.textPrimary
                }
            )
        }
        bar.addView(text)
        bar.addView(undo)
        content.addView(
            bar,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )

        val timeout = Runnable {
            val current = pending.remove(activity)
            if (current?.view === bar) {
                (bar.parent as? ViewGroup)?.removeView(bar)
                onTimeout()
            }
        }
        pending[activity] = Pending(bar, timeout)
        undo.setOnClickListener {
            val current = pending.remove(activity)
            if (current?.view === bar) {
                handler.removeCallbacks(timeout)
                (bar.parent as? ViewGroup)?.removeView(bar)
                onUndo()
            }
        }
        handler.postDelayed(timeout, UNDO_WINDOW_MS)
    }

    fun dismiss(activity: Activity) {
        val current = pending.remove(activity) ?: return
        handler.removeCallbacks(current.timeout)
        (current.view.parent as? ViewGroup)?.removeView(current.view)
    }
}
