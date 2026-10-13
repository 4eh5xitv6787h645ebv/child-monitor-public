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
package de.rochefort.childmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import de.rochefort.childmonitor.accessibility.HighContrastChartColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class VolumeView : View {
    // #362: high-contrast chart palette when the toggle is on (this Canvas view isn't reached by
    // ThemeManager's push recolor). Re-read via refreshHighContrast() since VolumeView lives on the
    // long-lived ListenActivity (only onResume'd, not recreated, when returning from Settings).
    private var hc = ThemeManager.isHighContrast(context)
    private fun hcOr(hcColor: Int, normalHex: String): Int = if (hc) hcColor else Color.parseColor(normalHex)

    private val graphBackgroundPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val gridPaint = Paint().apply { strokeWidth = 1.1f; isAntiAlias = true }
    private val envelopePaint = Paint().apply { strokeWidth = 1.5f; isAntiAlias = true }
    private val waveformStrokePaint = Paint().apply { strokeWidth = 2.6f; style = Paint.Style.STROKE; isAntiAlias = true }
    private val waveformFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val baselinePaint = Paint().apply { strokeWidth = 1.8f; isAntiAlias = true }
    private val lastPointPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }

    init {
        assignChartColors()
    }

    /** Single source of the colour mapping, so init and [refreshHighContrast] can't drift. */
    private fun assignChartColors() {
        graphBackgroundPaint.color = hcOr(HighContrastChartColors.bg, "#1A2822")
        gridPaint.color = hcOr(HighContrastChartColors.grid, "#6D867A")
        envelopePaint.color = hcOr(HighContrastChartColors.baseline, "#EBC2AF")
        waveformStrokePaint.color = hcOr(HighContrastChartColors.line, "#F7F4EA")
        waveformFillPaint.color = hcOr(HighContrastChartColors.envelope, "#8ED3A9")
        waveformFillPaint.alpha = 150
        baselinePaint.color = hcOr(HighContrastChartColors.baseline, "#BCD1C4")
        lastPointPaint.color = hcOr(HighContrastChartColors.line, "#FFF8F0")
    }

    /** #362: re-read the high-contrast setting and recolour live (call from the host's onResume). */
    fun refreshHighContrast() {
        val now = ThemeManager.isHighContrast(context)
        if (now == hc) return
        hc = now
        assignChartColors()
        invalidate()
    }

    private val waveformPath = Path()
    private val fillPath = Path()
    private val graphClipPath = Path()
    private val graphRect = RectF()
    var volumeHistory: VolumeHistory? = null

    constructor(context: Context?) : super(context) {
    }

    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
    }

    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
    }

    override fun onDraw(canvas: Canvas) {
        val volumeHistory = this.volumeHistory ?: return
        val height = height.toFloat()
        val width = width.toFloat()
        if (width <= 1f || height <= 1f) {
            return
        }

        val insetX = (8f * resources.displayMetrics.density)
        val insetY = (6f * resources.displayMetrics.density)
        val graphLeft = insetX
        val graphRight = width - insetX
        val graphTop = insetY
        val graphBottom = height - insetY
        if (graphRight <= graphLeft || graphBottom <= graphTop) {
            return
        }

        val cornerRadius = 14f * resources.displayMetrics.density
        val graphHeight = graphBottom - graphTop
        val baselineY = graphBottom

        graphRect.set(graphLeft, graphTop, graphRight, graphBottom)
        graphClipPath.reset()
        graphClipPath.addRoundRect(graphRect, cornerRadius, cornerRadius, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(graphClipPath)
        canvas.drawRoundRect(graphRect, cornerRadius, cornerRadius, graphBackgroundPaint)
        drawGrid(canvas, graphLeft, graphRight, graphTop, graphBottom)
        canvas.drawLine(graphLeft, baselineY, graphRight, baselineY, baselinePaint)

        val graphPixelWidth = (graphRight - graphLeft).toInt().coerceAtLeast(1)
        val size = volumeHistory.size()
        if (size == 0) {
            canvas.restore()
            return
        }

        val leftMost = (size - graphPixelWidth).coerceAtLeast(0)
        val visibleCount = size - leftMost

        var localPeak = 0.0
        for (i in leftMost until size) {
            localPeak = max(localPeak, volumeHistory[i])
        }
        if (localPeak <= 0.0) {
            canvas.restore()
            return
        }

        // Blend global and local normalization so quiet sounds stay visible after loud spikes.
        val globalNorm = volumeHistory.volumeNorm
        val localNorm = 1.0 / localPeak
        val effectiveNorm = max(globalNorm, localNorm * 0.78)

        waveformPath.reset()
        fillPath.reset()
        fillPath.moveTo(0f, baselineY)

        var hasWavePoint = false
        var lastX = 0f
        var lastY = baselineY

        val xLimit = min(graphPixelWidth, visibleCount)
        for (x in 0 until xLimit) {
            val start = leftMost + (x * visibleCount / xLimit)
            val endExclusive = leftMost + ((x + 1) * visibleCount / xLimit)
            val end = max(start + 1, endExclusive)

            var minValue = Double.MAX_VALUE
            var maxValue = 0.0
            for (i in start until min(end, size)) {
                val value = volumeHistory[i]
                minValue = min(minValue, value)
                maxValue = max(maxValue, value)
            }

            if (minValue == Double.MAX_VALUE) {
                minValue = 0.0
            }

            val minLevel = enhanceLevel(minValue, effectiveNorm)
            val maxLevel = enhanceLevel(maxValue, effectiveNorm)
            val yMin = baselineY - (minLevel * graphHeight).toFloat()
            val yMax = baselineY - (maxLevel * graphHeight).toFloat()
            val xPos = graphLeft + x.toFloat()

            canvas.drawLine(xPos, yMin, xPos, yMax, envelopePaint)

            if (!hasWavePoint) {
                waveformPath.moveTo(xPos, yMax)
                fillPath.lineTo(xPos, yMax)
                hasWavePoint = true
            } else {
                waveformPath.lineTo(xPos, yMax)
                fillPath.lineTo(xPos, yMax)
            }
            lastX = xPos
            lastY = yMax
        }

        if (hasWavePoint) {
            fillPath.lineTo(lastX, baselineY)
            fillPath.close()
            canvas.drawPath(fillPath, waveformFillPaint)
            canvas.drawPath(waveformPath, waveformStrokePaint)
            canvas.drawCircle(lastX, lastY, 3.5f, lastPointPaint)
        }
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas, left: Float, right: Float, graphTop: Float, graphBottom: Float) {
        val rows = 4
        val step = (graphBottom - graphTop) / rows
        for (i in 0..rows) {
            val y = graphTop + step * i
            canvas.drawLine(left, y, right, y, gridPaint)
        }
    }

    private fun enhanceLevel(raw: Double, norm: Double): Double {
        // Gamma curve (<1) boosts lower amplitudes while preserving peaks.
        val normalized = (raw * norm).coerceIn(0.0, 1.0)
        return normalized.pow(0.64)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // Keep this graph compact but readable on phones.
        val minHeightPx = (84 * resources.displayMetrics.density).toInt()
        if (measuredHeight < minHeightPx) {
            setMeasuredDimension(measuredWidth, minHeightPx)
        }
    }
}
