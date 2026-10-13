package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import de.rochefort.childmonitor.R
import de.rochefort.childmonitor.tracker.guidance.Sources

class SourcesActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sources)

        findViewById<TextView>(R.id.sourcesIntro).text =
            getString(R.string.tracker_sources_intro, Sources.COUNT, Sources.ALL.size)

        val container = findViewById<LinearLayout>(R.id.sourcesContainer)
        val dp = resources.displayMetrics.density

        Sources.ALL.forEach { group ->
            val groupView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            }
            val heading = TextView(this).apply {
                text = group.heading
                setTextColor(androidx.core.content.ContextCompat.getColor(this@SourcesActivity, de.rochefort.childmonitor.R.color.tracker_text_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val description = TextView(this).apply {
                text = group.description
                setTextColor(androidx.core.content.ContextCompat.getColor(this@SourcesActivity, de.rochefort.childmonitor.R.color.tracker_text_secondary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, (2 * dp).toInt(), 0, (6 * dp).toInt())
            }
            groupView.addView(heading)
            groupView.addView(description)

            group.items.forEach { source ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { openUrl(source.url) }
                    setBackgroundResource(android.R.drawable.list_selector_background)
                }
                val title = TextView(this).apply {
                    text = source.title
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@SourcesActivity, de.rochefort.childmonitor.R.color.tracker_source_title))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                }
                val url = TextView(this).apply {
                    text = source.url
                    setTextColor(androidx.core.content.ContextCompat.getColor(this@SourcesActivity, de.rochefort.childmonitor.R.color.tracker_source_url))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                }
                row.addView(title)
                row.addView(url)
                if (source.note.isNotBlank()) {
                    val note = TextView(this).apply {
                        text = source.note
                        setTextColor(androidx.core.content.ContextCompat.getColor(this@SourcesActivity, de.rochefort.childmonitor.R.color.tracker_text_muted))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                        setPadding(0, (2 * dp).toInt(), 0, 0)
                    }
                    row.addView(note)
                }
                groupView.addView(row)
            }

            container.addView(groupView)
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * dp).toInt()
                )
                setBackgroundColor(androidx.core.content.ContextCompat.getColor(this@SourcesActivity, de.rochefort.childmonitor.R.color.tracker_bg_surface))
            }
            container.addView(divider)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            // Copy to clipboard so the user can open it elsewhere instead of
            // silently failing (CM-TRK-CR2-P2-11).
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
            android.widget.Toast.makeText(this,
                getString(R.string.tracker_sources_copied, url),
                android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
