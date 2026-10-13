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
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import de.rochefort.childmonitor.ThemeManager
import de.rochefort.childmonitor.accessibility.HighContrastChartColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * #314: multi-night sleep trends — one stacked bar per night (asleep / awake / noisy minutes),
 * the wakings count above each bar, and a weekday/date label below. Pure Canvas, mirroring
 * [NightTimelineView]'s palette; a couple of weeks of nights is trivial to draw. No charting lib.
 */
class MultiNightTrendsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var nights: List<NightSummary> = emptyList()

    // #362: high-contrast chart palette when the toggle is on (this Canvas view isn't reached by
    // ThemeManager's push recolor); read once at construction.
    private val hc = ThemeManager.isHighContrast(context)
    private fun hcOr(hcColor: Int, normalHex: String): Int = if (hc) hcColor else Color.parseColor(normalHex)

    private val bg = paint(hcOr(HighContrastChartColors.bg, "#1A2822"))
    private val grid = paint(hcOr(HighContrastChartColors.gridFaint, "#33FFFFFF"), stroke = 1.5f)
    private val asleep = paint(hcOr(HighContrastChartColors.asleep, "#2E6E55"))
    private val awake = paint(hcOr(HighContrastChartColors.awake, "#E0A33B"))
    private val noisy = paint(hcOr(HighContrastChartColors.noisy, "#E0584B"))
    // #363: scale raw-px Canvas text by the system font-size setting (large-text a11y); unchanged at
    // fontScale=1.0, consistent with this view's px-based bar geometry.
    private val labelText = paint(hcOr(HighContrastChartColors.text, "#B9C9BF")).apply { textSize = fontScaled(24f) }
    private val wakeText = paint(hcOr(HighContrastChartColors.line, "#F7F4EA")).apply { textSize = fontScaled(24f) }
    private val emptyText = paint(hcOr(HighContrastChartColors.text, "#B9C9BF")).apply { textSize = fontScaled(30f) }

    private val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("M/d", Locale.getDefault())

    fun setData(nights: List<NightSummary>) {
        this.nights = nights
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bg)
        if (nights.isEmpty()) {
            canvas.drawText(NO_DATA, 24f, h / 2f, emptyText)
            return
        }

        val pad = 16f
        val labelH = 56f          // two text lines below each bar (weekday + date)
        val wakeLabelH = 30f      // wakings count above each bar
        val chartTop = pad + wakeLabelH
        val chartBottom = h - pad - labelH
        val chartH = (chartBottom - chartTop).coerceAtLeast(1f)
        canvas.drawLine(pad, chartBottom, w - pad, chartBottom, grid)

        // Scale every bar to the busiest night so trends are comparable; floor the denominator so a
        // single quiet night doesn't blow up into a full-height bar.
        val maxMinutes = maxOf(nights.maxOf { it.minutesRecorded }, 60)
        val n = nights.size
        val slot = (w - 2 * pad) / n
        val barW = (slot * 0.6f).coerceAtMost(80f)

        // #388: the date labels are centered per slot and grow with the font scale, so at many nights
        // / large text they collide. Thin them by a stride sized to the widest label rather than
        // dropping labels asymmetrically; the stride is anchored to the most recent night.
        val maxLabelW = nights.maxOf { s ->
            val d = Date(s.nightStartMs)
            maxOf(labelText.measureText(dayFmt.format(d)), labelText.measureText(dateFmt.format(d)))
        }
        val labelStride = TrendLabels.labelStride(slot, maxLabelW, gapPx = fontScaled(8f))

        for (i in nights.indices) {
            val s = nights[i]
            val cx = pad + slot * i + slot / 2f
            val x0 = cx - barW / 2f
            val x1 = cx + barW / 2f
            // Stacked from the baseline up: asleep, then awake, then noisy.
            var y = chartBottom
            for ((minutes, p) in listOf(s.minutesAsleep to asleep, s.minutesAwake to awake, s.minutesNoisy to noisy)) {
                if (minutes <= 0) continue
                val segH = (minutes.toFloat() / maxMinutes) * chartH
                canvas.drawRect(x0, y - segH, x1, y, p)
                y -= segH
            }
            // Wakings count above the bar.
            val barTopY = (chartBottom - (s.minutesRecorded.toFloat() / maxMinutes) * chartH)
            canvas.drawText(s.wakings.toString(), cx - wakeText.measureText(s.wakings.toString()) / 2f, barTopY - 8f, wakeText)
            // Weekday + date labels below the baseline (strided so they don't overlap — #388).
            if (TrendLabels.shouldLabel(i, n, labelStride)) {
                val date = Date(s.nightStartMs)
                val day = dayFmt.format(date)
                val dm = dateFmt.format(date)
                canvas.drawText(day, cx - labelText.measureText(day) / 2f, chartBottom + 26f, labelText)
                canvas.drawText(dm, cx - labelText.measureText(dm) / 2f, chartBottom + 50f, labelText)
            }
        }
    }

    /** #363: a raw-px Canvas text size scaled by the system font-size setting (large-text a11y). */
    private fun fontScaled(basePx: Float): Float = basePx * resources.configuration.fontScale

    private fun paint(c: Int, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = c
        if (stroke > 0f) { strokeWidth = stroke; style = Paint.Style.STROKE } else style = Paint.Style.FILL
    }

    companion object {
        private const val NO_DATA = "No nights recorded yet"
    }
}
