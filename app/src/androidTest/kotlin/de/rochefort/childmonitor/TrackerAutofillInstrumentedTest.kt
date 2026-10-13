package de.rochefort.childmonitor

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerAutofillInstrumentedTest {
    @Test
    fun childHealthFieldsAreExcludedFromAutofill() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val fieldsByLayout = listOf(
            R.layout.activity_child_profile to intArrayOf(R.id.nameEdit),
            R.layout.activity_feed_entry to intArrayOf(R.id.durationEdit, R.id.volumeEdit, R.id.noteEdit),
            R.layout.activity_growth_entry to intArrayOf(R.id.weightEdit, R.id.lengthEdit, R.id.headEdit),
            R.layout.activity_medication_entry to intArrayOf(R.id.tempEdit, R.id.weightEdit, R.id.strengthEdit),
            R.layout.activity_sleep_entry to intArrayOf(R.id.tempEdit),
            R.layout.activity_tracker_event_edit to intArrayOf(
                R.id.editSubkind,
                R.id.editValue,
                R.id.editValue2,
                R.id.editNote,
            ),
        )

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val appContext = ApplicationProvider.getApplicationContext<Context>()
            val inflater = LayoutInflater.from(ContextThemeWrapper(appContext, R.style.AppTheme))
            fieldsByLayout.forEach { (layoutId, viewIds) ->
                val root = inflater.inflate(layoutId, null, false)
                viewIds.forEach { viewId ->
                    assertEquals(
                        "view $viewId in layout $layoutId",
                        View.IMPORTANT_FOR_AUTOFILL_NO,
                        root.findViewById<View>(viewId).importantForAutofill,
                    )
                }
            }
        }
    }
}
