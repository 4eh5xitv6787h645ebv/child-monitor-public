package de.rochefort.childmonitor.cast

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-scoped DLNA output controller. The parent already runs a foreground listening service,
 * so keeping the lightweight snapshot refresher here lets a slideshow continue when its setup
 * Activity closes without leaking an Activity or starting a second foreground service.
 */
object LocalDisplayOutputManager {
    private val controller = DlnaController()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "dlna-local-display").apply { isDaemon = true }
    }
    private var refreshTask: ScheduledFuture<*>? = null
    @Volatile private var renderer: DlnaRenderer? = null
    @Volatile private var failures = 0

    @Synchronized
    fun playLive(target: DlnaRenderer, streamUrl: String, displayTitle: String): Boolean {
        stopExistingBeforeSwitch(target)
        val ok = controller.play(target, streamUrl, DlnaController.MIME_MJPEG, displayTitle)
        renderer = if (ok) target else null
        failures = if (ok) 0 else 1
        return ok
    }

    @Synchronized
    fun playSlideshow(target: DlnaRenderer, snapshotUrl: String, displayTitle: String): Boolean {
        stopExistingBeforeSwitch(target)
        val sequence = AtomicInteger(0)
        val firstOk = controller.play(
            target,
            "$snapshotUrl&v=${sequence.incrementAndGet()}",
            DlnaController.MIME_JPEG,
            displayTitle,
        )
        if (!firstOk) {
            renderer = null
            failures = 1
            return false
        }
        renderer = target
        failures = 0
        // Delay from completion rather than the original wall schedule. Android may cache this
        // process for minutes; fixed-rate scheduling would replay every missed network refresh in
        // a burst when execution resumes (#485).
        refreshTask = executor.scheduleWithFixedDelay({
            synchronized(this) {
                if (renderer == target) {
                    val ok = controller.play(
                        target,
                        "$snapshotUrl&v=${sequence.incrementAndGet()}",
                        DlnaController.MIME_JPEG,
                        displayTitle,
                    )
                    failures = if (ok) 0 else failures + 1
                    if (failures >= MAX_CONSECUTIVE_FAILURES) {
                        cancelRefresh()
                        renderer = null
                    }
                }
            }
        }, SNAPSHOT_REFRESH_SECONDS, SNAPSHOT_REFRESH_SECONDS, TimeUnit.SECONDS)
        return true
    }

    @Synchronized
    fun stop(): Boolean {
        cancelRefresh()
        val active = renderer
        renderer = null
        failures = 0
        return active?.let { controller.stop(it) } ?: false
    }

    fun activeRendererName(): String? = renderer?.friendlyName
    fun failed(): Boolean = failures >= MAX_CONSECUTIVE_FAILURES

    private fun cancelRefresh() {
        refreshTask?.cancel(true)
        refreshTask = null
    }

    private fun stopExistingBeforeSwitch(next: DlnaRenderer) {
        cancelRefresh()
        val previous = renderer
        renderer = null
        if (previous != null && previous != next) controller.stop(previous)
    }

    private const val SNAPSHOT_REFRESH_SECONDS = 3L
    private const val MAX_CONSECUTIVE_FAILURES = 3
}
