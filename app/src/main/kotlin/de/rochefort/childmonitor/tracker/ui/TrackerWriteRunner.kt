package de.rochefort.childmonitor.tracker.ui

import android.app.Activity
import android.util.Log
import android.widget.Toast
import de.rochefort.childmonitor.R
import java.util.concurrent.Executors

/** Lifecycle-safe, serialized off-main-thread persistence for tracker screens (#459). */
object TrackerWriteRunner {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tracker-writes").apply { isDaemon = true }
    }

    class Rejected(message: String) : IllegalArgumentException(message)

    fun <T> run(
        activity: Activity,
        write: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: ((Throwable) -> Unit)? = null,
    ) {
        executor.execute {
            val result = runCatching(write)
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = onSuccess,
                    onFailure = { error ->
                        Log.e("TrackerWriteRunner", "Tracker write failed", error)
                        if (onFailure != null) onFailure(error)
                        else Toast.makeText(
                            activity,
                            error.message?.takeIf { error is Rejected } ?: activity.getString(R.string.tracker_save_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }
}
