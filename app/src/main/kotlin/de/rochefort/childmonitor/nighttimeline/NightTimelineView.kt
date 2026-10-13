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
package de.rochefort.childmonitor.nighttimeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.accessibility.HighContrastChartColors
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Custom-Canvas night timeline (#283). Three stacked lanes share one horizontal time axis
 * (windowStart → windowEnd): a state colour band, a relative-loudness envelope (min→peak
 * fill + mean line + dashed "awake" threshold), and a motion strip. No charting library —
 * a whole night is ≤720 one-minute columns, trivial to draw. Paints are cached.
 */
class NightTimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var buckets: List<MinuteBucket> = emptyList()
    private var windowStart = 0L
    private var windowEnd = 0L
    private var awakeDbfs = NightRecorder.DEFAULT_AWAKE_DBFS

    // #362: when high contrast is on, paint with the high-contrast chart palette instead of the
    // themed hex (this Canvas view isn't reached by ThemeManager's push-based recolor). Read once at
    // construction — the screen is re-created when opened after toggling the setting.
    private val hc = ThemeManager.isHighContrast(context)
    private fun hcOr(hcColor: Int, normalHex: String): Int = if (hc) hcColor else Color.parseColor(normalHex)

    private val bg = paint(hcOr(HighContrastChartColors.bg, "#1A2822"))
    private val grid = paint(hcOr(HighContrastChartColors.gridFaint, "#33FFFFFF"), stroke = 1.5f)
    private val mean = paint(hcOr(HighContrastChartColors.line, "#F7F4EA"), stroke = 2f)
    private val envelope = paint(hcOr(HighContrastChartColors.envelope, "#8ED3A9"))
    private val motionPaint = paint(hcOr(HighContrastChartColors.motion, "#FFD27F"))
    private val thresholdPaint = paint(hcOr(HighContrastChartColors.threshold, "#A0FF6B6B"), stroke = 2f).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    // #363: Canvas text is raw px (Paint.textSize), so it ignored the system font-size setting.
    // Scale by the user's fontScale so large-text users get bigger labels; identical at fontScale=1.0
    // and consistent with this view's px-based lane geometry (no density change → no lane overflow).
    private val text = paint(hcOr(HighContrastChartColors.text, "#B9C9BF")).apply { textSize = fontScaled(26f) }
    private val stateAsleep = paint(hcOr(HighContrastChartColors.asleep, "#2E6E55"))
    private val stateAwake = paint(hcOr(HighContrastChartColors.awake, "#E0A33B"))
    private val stateNoisy = paint(hcOr(HighContrastChartColors.noisy, "#E0584B"))
    private val stateNoSignal = paint(hcOr(HighContrastChartColors.noSignal, "#3A4A42"))
    private val hourFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun setData(buckets: List<MinuteBucket>, windowStart: Long, windowEnd: Long, awakeDbfs: Double) {
        this.buckets = buckets
        this.windowStart = windowStart
        this.windowEnd = windowEnd
        this.awakeDbfs = awakeDbfs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        if (windowEnd <= windowStart) {
            canvas.drawText("No data yet", 24f, h / 2f, text)
            return
        }

        val pad = 8f
        val axisH = 34f
        val bandTop = pad
        val bandH = 26f
        val motionH = (h - axisH - bandTop - bandH) * 0.28f
        val noiseTop = bandTop + bandH + 6f
        val noiseBottom = h - axisH - motionH - 6f
        val motionTop = noiseBottom + 6f
        val motionBottom = h - axisH
        val span = (windowEnd - windowStart).toFloat()

        fun mapX(ms: Long): Float = ((ms - windowStart) / span) * w
        fun mapY(dbfs: Double): Float {
            val frac = ((dbfs - AudioLevel.FLOOR_DBFS) / (0.0 - AudioLevel.FLOOR_DBFS)).coerceIn(0.0, 1.0)
            return (noiseBottom - frac * (noiseBottom - noiseTop)).toFloat()
        }

        // Noise lane gridlines + dashed awake threshold
        canvas.drawLine(0f, noiseTop, w, noiseTop, grid)
        canvas.drawLine(0f, noiseBottom, w, noiseBottom, grid)
        val thY = mapY(awakeDbfs)
        canvas.drawLine(0f, thY, w, thY, thresholdPaint)

        for (b in buckets) {
            val x0 = mapX(b.minuteEpochMs)
            val x1 = mapX(b.minuteEpochMs + 60_000L).coerceAtLeast(x0 + 1f)
            // state band
            canvas.drawRect(x0, bandTop, x1, bandTop + bandH, stateColor(b.state))
            // noise envelope (min→peak) + mean
            canvas.drawRect(x0, mapY(b.rmsPeakDbfs), x1, mapY(b.rmsMinDbfs), envelope)
            val my = mapY(b.rmsMeanDbfs)
            canvas.drawLine(x0, my, x1, my, mean)
            // motion strip
            if (b.motion > 0) {
                val mh = (b.motion / 100f) * motionH
                canvas.drawRect(x0, motionBottom - mh, x1, motionBottom, motionPaint)
            }
        }

        // Hour ticks along the axis
        val cal = Calendar.getInstance().apply {
            timeInMillis = windowStart
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
        }
        // #363: gridline every hour, but only draw a label when it clears the previous one — at
        // large font scale 12 hourly labels would otherwise overlap. Keeps the axis legible at any
        // font size / density.
        var lastLabelRight = -Float.MAX_VALUE
        while (cal.timeInMillis < windowEnd) {
            val x = mapX(cal.timeInMillis)
            canvas.drawLine(x, noiseTop, x, motionBottom, grid)
            val label = hourFmt.format(cal.time)
            val labelLeft = x + 4f
            if (labelLeft > lastLabelRight + 8f) {
                canvas.drawText(label, labelLeft, motionBottom + axisH - 8f, text)
                lastLabelRight = labelLeft + text.measureText(label)
            }
            cal.add(Calendar.HOUR_OF_DAY, 1)
        }
    }

    private fun stateColor(s: NightState): Paint = when (s) {
        NightState.ASLEEP -> stateAsleep
        NightState.AWAKE -> stateAwake
        NightState.NOISY -> stateNoisy
        NightState.NO_SIGNAL -> stateNoSignal
    }

    /** #363: a raw-px Canvas text size scaled by the system font-size setting (large-text a11y). */
    private fun fontScaled(basePx: Float): Float = basePx * resources.configuration.fontScale

    private fun paint(c: Int, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        if (stroke > 0f) { strokeWidth = stroke; style = Paint.Style.STROKE } else style = Paint.Style.FILL
    }
}
