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

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.ThemeManager
import java.util.TimeZone

/**
 * #314: multi-night sleep trends — one bar per night (time-asleep split by state + wakings) over the
 * last [TREND_DAYS] nights. Parent-side, reading the local [NightMetricsStore] directly (mirrors
 * [NightTimelineActivity]); per-night rollup is the pure [NightSummaries.summarizeNights].
 */
class MultiNightTrendsActivity : Activity() {

    private lateinit var store: NightMetricsStore
    private lateinit var view: MultiNightTrendsView
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_night_trends)
        ThemeManager.applyTheme(this)
        store = NightMetricsStore(this)
        view = findViewById(R.id.sleepTrendsView)
        summary = findViewById(R.id.sleepTrendsSummary)
        findViewById<Button>(R.id.sleepTrendsRefresh).setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        load()
    }

    private fun load() {
        Thread {
            val sens = Sensitivity.load(this)
            // Anchor to the last recorded minute (like the timeline) so "the next morning" shows real
            // nights, not a window ending on an empty now.
            val end = (store.latestMinute() ?: System.currentTimeMillis()) + 60_000L
            val start = end - TREND_DAYS * 24L * 60L * 60L * 1000L
            val raw = try { store.range(start, end) } catch (_: Exception) { emptyList() }
            // One offset (at `end`) applied to all nights. A DST change inside the 14-day window
            // shifts an earlier night's 6pm boundary by an hour, but that's nowhere near sleep/wake
            // activity, so no real sleep minutes move between nights — an accepted approximation.
            val zoneOffsetMs = TimeZone.getDefault().getOffset(end).toLong()
            val nights = NightSummaries.summarizeNights(
                raw, sens.awakeDbfs, sens.motionThresholdPercent, zoneOffsetMs
            )
            val text = summarize(nights)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                view.setData(nights)
                summary.text = text
            }
        }.start()
    }

    override fun onDestroy() {
        try { store.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun summarize(nights: List<NightSummary>): String {
        if (nights.isEmpty()) return getString(R.string.sleepTrendsEmpty)
        val avgAsleep = nights.sumOf { it.minutesAsleep } / nights.size
        val avgWakings = nights.sumOf { it.wakings }.toDouble() / nights.size
        return resources.getQuantityString(
            R.plurals.sleepTrendsSummary,
            nights.size,
            nights.size,
            fmtMin(avgAsleep),
            String.format(java.util.Locale.getDefault(), "%.1f", avgWakings)
        )
    }

    private fun fmtMin(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"

    companion object {
        private const val TREND_DAYS = 14L
    }
}
