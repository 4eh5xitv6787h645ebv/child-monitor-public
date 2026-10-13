package de.rochefort.childmonitor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.rochefort.childmonitor.media.GallerySaver
import java.io.InputStream
import java.net.HttpURLConnection

/**
 * Fullscreen camera view with pure gesture controls:
 * pinch to zoom, two-finger rotate, drag to pan, double-tap to exit.
 */
class FullscreenCameraActivity : Activity() {

    private lateinit var imageView: ZoomableImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var eventJumpBadge: TextView
    private var snapshotUrl: String? = null
    private var deviceId: String? = null
    private var hasLoggedFirstFrame = false
    // #370: the latest displayed frame, kept as a fallback for "save" if a full-res re-fetch fails.
    @Volatile private var lastFrameBitmap: Bitmap? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentRefreshInterval = 500L
    private val minRefreshInterval = 200L
    private val maxRefreshInterval = 1500L
    private var isRunning = false
    private var firstFrameApplied = false
    private var pendingViewportReset = true
    private var snapshotFailureStreak = 0
    private var lastFrameWidth = -1
    private var lastFrameHeight = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // #296: keep the fullscreen feed out of screenshots / recents thumbnail when app-lock is on.
        if (de.rochefort.childmonitor.security.AppLock.isEnabled(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        setContentView(R.layout.activity_fullscreen_camera)

        imageView = findViewById(R.id.fullscreenImage)
        progressBar = findViewById(R.id.loadingProgress)
        eventJumpBadge = findViewById(R.id.eventJumpBadge)
        snapshotUrl = intent.getStringExtra("snapshotUrl")
        deviceId = intent.getStringExtra("deviceId")
        Log.i(TAG, "Fullscreen camera opened with URL: $snapshotUrl")

        imageView.onDoubleTapListener = { _, _ -> finish() }
        findViewById<TextView>(R.id.fullscreenSaveButton).setOnClickListener { saveCurrentFrame() }
        renderEventContext()
    }

    override fun onPause() {
        isRunning = false
        pendingViewportReset = true
        firstFrameApplied = false
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!isRunning) {
            isRunning = true
            startRefreshLoop()
        }
    }

    override fun onDestroy() {
        // [#239] stop the reused fetch executor so its worker thread doesn't outlive the activity.
        snapshotFetchExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startRefreshLoop() {
        handler.post(refreshRunnable)
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            fetchSnapshot()
            if (isRunning) {
                handler.postDelayed(this, currentRefreshInterval)
            }
        }
    }

    private val snapshotFetchInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val snapshotFetchExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "FullscreenCamera-snapshot").apply { isDaemon = true }
    }

    private fun fetchSnapshot() {
        val url = snapshotUrl ?: return
        // [#239] One fetch at a time on a reused single-thread executor (overlapping ticks
        // are dropped). The old Thread-per-tick — as frequent as every 200ms with 2s
        // timeouts — could pile up ~10 concurrent fetches each holding a large bitmap.
        if (!snapshotFetchInFlight.compareAndSet(false, true)) return
        snapshotFetchExecutor.execute {
            try {
                // [#231] Use the SAME pinned-TLS + bearer path as the inline snapshot loop.
                // Previously this opened a plain (unpinned) connection with no Authorization
                // header, so for a TLS-paired child it silently bypassed cert pinning (MITM)
                // and got 401 on every fetch (perpetual spinner).
                val connection = PinnedHttp.openPinned(this@FullscreenCameraActivity, url, deviceId)
                connection.connectTimeout = 2000
                connection.readTimeout = 2000
                connection.doInput = true
                ParentSessionStore.get(this@FullscreenCameraActivity, deviceId)?.let { tok ->
                    connection.setRequestProperty("Authorization", "Bearer $tok")
                }
                connection.connect()
                if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    // Token expired / never set → forget it so the user is prompted to re-pair.
                    try {
                        connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) }
                    } catch (_: Exception) {
                    }
                    ParentSessionStore.forget(this@FullscreenCameraActivity, deviceId)
                    snapshotFailureStreak++
                    if (snapshotFailureStreak >= SNAPSHOT_RECOVERY_RESET_THRESHOLD) {
                        pendingViewportReset = true
                    }
                } else if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val hadRecentFailures = snapshotFailureStreak >= SNAPSHOT_RECOVERY_RESET_THRESHOLD
                    snapshotFailureStreak = 0
                    connection.inputStream.use { stream: InputStream ->
                        // #208: bound bytes + dimensions so a hostile host can't OOM the
                        // parent with an oversized image.
                        val bmp = BoundedBitmap.decodeBoundedStream(
                            stream,
                            maxBytes = BoundedBitmap.MAX_SNAPSHOT_BYTES,
                            maxDimension = BoundedBitmap.MAX_SNAPSHOT_DIMENSION,
                            // [#239] RGB_565 halves bitmap memory vs the default ARGB_8888;
                            // JPEG snapshots carry no alpha, so it is visually identical.
                            config = Bitmap.Config.RGB_565
                        )
                        if (bmp != null) {
                            lastFrameBitmap = bmp // #370: keep for save-fallback
                            val frameSizeChanged = bmp.width != lastFrameWidth || bmp.height != lastFrameHeight
                            if (frameSizeChanged) {
                                lastFrameWidth = bmp.width
                                lastFrameHeight = bmp.height
                            }
                            if (!hasLoggedFirstFrame) {
                                hasLoggedFirstFrame = true
                                Log.i(TAG, "Fullscreen first frame received from $url")
                            }
                            val brightness = calculateBrightness(bmp)
                            adjustRefreshRate(brightness)

                            runOnUiThread {
                                imageView.updateBitmap(bmp)
                                if (!firstFrameApplied || pendingViewportReset || frameSizeChanged || hadRecentFailures) {
                                    imageView.resetToFit()
                                    firstFrameApplied = true
                                    pendingViewportReset = false
                                }
                                progressBar.visibility = View.GONE
                            }
                        }
                    }
                } else {
                    snapshotFailureStreak++
                    if (snapshotFailureStreak >= SNAPSHOT_RECOVERY_RESET_THRESHOLD) {
                        pendingViewportReset = true
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                snapshotFailureStreak++
                if (snapshotFailureStreak >= SNAPSHOT_RECOVERY_RESET_THRESHOLD) {
                    pendingViewportReset = true
                }
                Log.w(TAG, "Fullscreen snapshot fetch failed: ${e.message}")
            } finally {
                snapshotFetchInFlight.set(false)
            }
        }
    }

    private fun calculateBrightness(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        var totalBrightness = 0L
        var sampleCount = 0

        val step = 10
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                totalBrightness += luminance
                sampleCount++
            }
        }

        return if (sampleCount > 0) (totalBrightness / sampleCount).toInt() else 128
    }

    private fun adjustRefreshRate(brightness: Int) {
        currentRefreshInterval = when {
            brightness < 50 -> maxRefreshInterval
            brightness < 100 -> 1000L
            brightness < 150 -> 500L
            else -> minRefreshInterval
        }
    }

    private fun renderEventContext() {
        val eventType = intent.getStringExtra(ListenActivity.EXTRA_EVENT_TYPE)
        val eventTimestamp = intent.getLongExtra(ListenActivity.EXTRA_EVENT_TIMESTAMP_MS, -1L)
        if (eventType.isNullOrBlank() || eventTimestamp <= 0L) {
            eventJumpBadge.visibility = View.GONE
            return
        }
        val whenText = android.text.format.DateFormat.format("HH:mm:ss", eventTimestamp)
        eventJumpBadge.text = getString(R.string.fullscreenJumpedFrom, eventType, whenText)
        eventJumpBadge.visibility = View.VISIBLE
    }

    /**
     * #370: save the current frame to the gallery. Prefers the child's actual full-resolution JPEG
     * (re-fetched over the same pinned/bearer transport, saved raw — no decode/re-encode), and falls
     * back to the displayed (downscaled) frame if the re-fetch fails (e.g. camera just turned off).
     */
    private fun saveCurrentFrame() {
        // Pre-29 needs WRITE_EXTERNAL_STORAGE (mirrors the #288 inline save).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_REQUEST_SAVE_SNAPSHOT
            )
            return
        }
        val url = snapshotUrl
        val frame = lastFrameBitmap
        if (url == null && frame == null) {
            AppToast.show(this, R.string.saveSnapshotNone, Toast.LENGTH_SHORT)
            return
        }
        val ts = System.currentTimeMillis()
        Thread {
            // Full-res: the child's raw JPEG bytes — but only if they're actually a valid JPEG within
            // the snapshot dimension bound. A misbehaving/MITM child could 200 with a non-JPEG or an
            // oversized image; the preview path validates by decoding, so the raw save must too, or
            // we'd persist (and report "saved") an unviewable/huge file. Else fall back to the frame.
            val rawBytes = url?.let { fetchSnapshotBytes(it) }?.takeIf { isValidBoundedJpeg(it) }
            val haveSomething = rawBytes != null || frame != null
            val ok = if (rawBytes != null) {
                GallerySaver.saveJpegBytes(applicationContext, rawBytes, ts)
            } else {
                frame != null && GallerySaver.saveJpeg(applicationContext, frame, ts)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val msg = when {
                    ok -> R.string.saveSnapshotOk
                    !haveSomething -> R.string.saveSnapshotNone // camera off / no frame yet → nothing to save
                    else -> R.string.saveSnapshotFailed         // had data but the write failed
                }
                AppToast.show(this, msg, Toast.LENGTH_SHORT)
            }
        }.apply { isDaemon = true; name = "fullscreen-save" }.start()
    }

    /**
     * #370: confirm raw bytes decode as a JPEG within the snapshot dimension bound (bounds-only, no
     * pixel allocation), so a malformed/oversized `/snapshot` response isn't persisted raw as a
     * "saved" .jpg (the displayed-frame fallback then handles it). Mirrors the BoundedBitmap guard.
     */
    private fun isValidBoundedJpeg(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outMimeType == "image/jpeg" &&
            opts.outWidth in 1..BoundedBitmap.MAX_SNAPSHOT_DIMENSION &&
            opts.outHeight in 1..BoundedBitmap.MAX_SNAPSHOT_DIMENSION
    }

    /** Re-fetch /snapshot and return the raw JPEG bytes (bounded), or null on any non-200/error. */
    private fun fetchSnapshotBytes(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = PinnedHttp.openPinned(this, url, deviceId)
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doInput = true
            ParentSessionStore.get(this, deviceId)?.let { tok ->
                connection.setRequestProperty("Authorization", "Bearer $tok")
            }
            connection.connect()
            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.use { BoundedBitmap.readBounded(it, BoundedBitmap.MAX_SNAPSHOT_BYTES) }
            } else {
                if (connection.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    try { connection.errorStream?.use { it.copyTo(java.io.ByteArrayOutputStream()) } } catch (_: Exception) {}
                    ParentSessionStore.forget(this, deviceId)
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Full-res snapshot fetch failed: ${e.message}")
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_SAVE_SNAPSHOT) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveCurrentFrame()
            } else {
                AppToast.show(this, R.string.saveSnapshotPermission, Toast.LENGTH_SHORT)
            }
        }
    }

    companion object {
        private const val TAG = "FullscreenCamera"
        private const val SNAPSHOT_RECOVERY_RESET_THRESHOLD = 2
        private const val PERMISSION_REQUEST_SAVE_SNAPSHOT = 12347
    }
}
