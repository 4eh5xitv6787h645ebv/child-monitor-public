package de.rochefort.childmonitor

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuantityResourcesInstrumentedTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().let { context ->
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(Locale.US)
        }
        context.createConfigurationContext(configuration).resources
    }

    @Test
    fun singularCaregiverCountsUseSingularEnglish() {
        assertEquals(
            "Restored 1 setting. Restart the app to apply.",
            resources.getQuantityString(R.plurals.backupRestored, 1, 1),
        )
        assertEquals(
            "1 night · avg asleep 8h · avg wakings 1.0",
            resources.getQuantityString(R.plurals.sleepTrendsSummary, 1, 1, "8h", "1.0"),
        )
        assertEquals("Age: 1 month", resources.getQuantityString(R.plurals.tracker_age_months, 1, 1))
        assertEquals("1 day overdue", resources.getQuantityString(R.plurals.tracker_vaccine_overdue, 1, 1))
        assertEquals(
            "1 safer-sleep check still unmet.",
            resources.getQuantityString(R.plurals.tracker_sleep_safety_missing, 1, 1),
        )
        assertEquals(
            "Earliest next dose in 1 minute.",
            resources.getQuantityString(R.plurals.tracker_med_next_ok_in, 1, 1),
        )
        assertTrue(
            resources.getQuantityString(R.plurals.tracker_growth_out_of_range, 1, 1)
                .startsWith("Age 1 month is outside"),
        )
        assertTrue(
            resources.getQuantityString(R.plurals.tracker_med_stale_weight, 1, 1)
                .contains("is 1 day old"),
        )
        assertEquals(
            "Found 1 child device",
            resources.getQuantityString(R.plurals.discoveredChildren, 1, 1),
        )
        assertEquals(
            "Age: 1 month (corrected 2 months)",
            resources.getString(
                R.string.tracker_age_with_corrected,
                resources.getQuantityString(R.plurals.tracker_months_count, 1, 1),
                resources.getQuantityString(R.plurals.tracker_months_count, 2, 2),
            ),
        )
    }

    @Test
    fun pluralCaregiverCountsUsePluralEnglish() {
        assertEquals(
            "Restored 2 settings. Restart the app to apply.",
            resources.getQuantityString(R.plurals.backupRestored, 2, 2),
        )
        assertEquals(
            "2 nights · avg asleep 8h · avg wakings 1.5",
            resources.getQuantityString(R.plurals.sleepTrendsSummary, 2, 2, "8h", "1.5"),
        )
        assertEquals("Age: 2 months", resources.getQuantityString(R.plurals.tracker_age_months, 2, 2))
        assertEquals("2 days overdue", resources.getQuantityString(R.plurals.tracker_vaccine_overdue, 2, 2))
        assertEquals(
            "2 safer-sleep checks still unmet.",
            resources.getQuantityString(R.plurals.tracker_sleep_safety_missing, 2, 2),
        )
        assertEquals(
            "Earliest next dose in 2 minutes.",
            resources.getQuantityString(R.plurals.tracker_med_next_ok_in, 2, 2),
        )
        assertTrue(
            resources.getQuantityString(R.plurals.tracker_growth_out_of_range, 2, 2)
                .startsWith("Age 2 months is outside"),
        )
        assertTrue(
            resources.getQuantityString(R.plurals.tracker_med_stale_weight, 2, 2)
                .contains("is 2 days old"),
        )
        assertEquals(
            "Found 2 child devices",
            resources.getQuantityString(R.plurals.discoveredChildren, 2, 2),
        )
    }
}
