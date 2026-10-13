package de.rochefort.childmonitor

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView

class TimelineActivity : Activity() {

    private lateinit var timelineList: ListView
    private lateinit var emptyText: TextView

    private var allEvents: List<DiagnosticsRepository.LogEvent> = emptyList()
    private var activeCategory: String = CATEGORY_ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timeline)
        ThemeManager.applyTheme(this)

        timelineList = findViewById(R.id.timelineList)
        emptyText = findViewById(R.id.timelineEmptyText)
        timelineList.emptyView = emptyText

        findViewById<Button>(R.id.timelineRefreshButton).setOnClickListener { refreshTimeline() }
        findViewById<Button>(R.id.timelineFilterAll).setOnClickListener { setCategory(CATEGORY_ALL) }
        findViewById<Button>(R.id.timelineFilterConnection).setOnClickListener { setCategory("CONNECTION") }
        findViewById<Button>(R.id.timelineFilterNetwork).setOnClickListener { setCategory("NETWORK") }
        findViewById<Button>(R.id.timelineFilterAudio).setOnClickListener { setCategory("AUDIO") }
        findViewById<Button>(R.id.timelineFilterSystem).setOnClickListener { setCategory("SYSTEM") }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.applyTheme(this)
        refreshTimeline()
    }

    private fun setCategory(category: String) {
        activeCategory = category
        renderTimeline()
        updateFilterButtons()
    }

    private fun updateFilterButtons() {
        findViewById<Button>(R.id.timelineFilterAll).isEnabled = activeCategory != CATEGORY_ALL
        findViewById<Button>(R.id.timelineFilterConnection).isEnabled = activeCategory != "CONNECTION"
        findViewById<Button>(R.id.timelineFilterNetwork).isEnabled = activeCategory != "NETWORK"
        findViewById<Button>(R.id.timelineFilterAudio).isEnabled = activeCategory != "AUDIO"
        findViewById<Button>(R.id.timelineFilterSystem).isEnabled = activeCategory != "SYSTEM"
    }

    private fun refreshTimeline() {
        Thread {
            val events = DiagnosticsRepository.loadRecentEvents(this, maxFiles = 7, maxLinesPerFile = 900)
            runOnUiThread {
                allEvents = events
                renderTimeline()
                updateFilterButtons()
            }
        }.start()
    }

    private fun renderTimeline() {
        val filtered = if (activeCategory == CATEGORY_ALL) {
            allEvents
        } else {
            allEvents.filter { it.category == activeCategory }
        }

        val rows = filtered.take(300).map {
            "${it.timestamp}  [${it.level}] [${it.category}]\n${it.message}"
        }

        val adapter = ArrayAdapter(
            this,
            R.layout.row_timeline_event,
            R.id.timelineEventText,
            rows
        )
        timelineList.adapter = adapter
        emptyText.text = if (allEvents.isEmpty()) {
            "No diagnostic events available yet."
        } else {
            "No events match this category."
        }
    }

    companion object {
        private const val CATEGORY_ALL = "ALL"
    }
}
