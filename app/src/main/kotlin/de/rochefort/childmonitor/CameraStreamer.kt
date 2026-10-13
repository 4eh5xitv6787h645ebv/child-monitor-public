package de.rochefort.childmonitor

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight CameraX wrapper that keeps the latest JPEG snapshot in memory.
 * This avoids running a full video pipeline while still giving parents a live view.
 */
class CameraStreamer(private val context: Context) : ImageAnalysis.Analyzer, LifecycleOwner {
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val isRunning = AtomicBoolean(false)
    private var frameCount = 0
    private var maxCamerasErrorCount = 0

    // #287: snapshot cadence throttle. 0 = publish every frame (back-compat default). Otherwise
    // publish a new JPEG at most once per [encodeIntervalMs] (set by MonitorService from
    // SnapshotCadencePolicy), so we skip the per-frame JPEG encode when no one needs near-live.
    // Long.MAX_VALUE pauses publishing entirely (thermal protection). Read on the camera
    // executor thread, written from the service thread → @Volatile.
    @Volatile var encodeIntervalMs: Long = 0L
    // @Volatile: written on the camera executor thread (analyze) AND reset from stop() on the
    // service thread, so it needs visibility across both.
    @Volatile private var lastEmitMs = 0L

    // #337: optional sink for each freshly-published JPEG, invoked on the camera executor thread
    // with the same bytes + monotonic timestamp stored in [latestFrame]. MonitorService taps this
    // to fill the snapshot replay ring; null = no replay capture.
    @Volatile var frameListener: ((ByteArray, Long) -> Unit)? = null
    // #266/#343: continuous luma-plane motion feed, independent of JPEG publish cadence so it
    // remains active while the parent screen is off or snapshot mode is battery-throttled.
    private val motionDetector = de.rochefort.childmonitor.events.MotionEventDetector()
    @Volatile var motionListener: ((de.rochefort.childmonitor.events.MotionEventDetector.Event) -> Unit)? = null

    // Night mode: automatic low-light enhancement via frame averaging + gamma
    private val nightModeProcessor = NightModeProcessor()
    @Volatile private var isLowLightMode = false
    private var manualExposureApplied = false

    // Hysteresis thresholds to prevent flickering between modes
    private val darkThreshold = 40    // Enter night mode below this Y mean
    private val brightThreshold = 60  // Exit night mode above this Y mean

    // Debounce: skip luminance checks after mode transitions to let exposure settle
    private var modeTransitionFrame = 0
    private val modeSettlingFrames = 60  // ~2s at 30fps or ~15s at 4fps night mode

    // Custom lifecycle that we control explicitly - recreated on each start()
    // because LifecycleRegistry cannot transition back from DESTROYED
    private var lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /** Callback invoked when camera successfully starts or fails */
    var onCameraStateChanged: ((running: Boolean, error: String?) -> Unit)? = null

    // Using 640x480 which works reliably, with lower JPEG quality for bandwidth savings
    @Suppress("UNUSED_PARAMETER")
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    fun start(lifecycleOwner: LifecycleOwner? = null, targetSize: Size = Size(640, 480)) {
        if (isRunning.get()) {
            Log.i(TAG, "Camera already running, ignoring start request")
            return
        }
        motionDetector.reset()

        // Create a fresh lifecycle if the previous one was destroyed
        // LifecycleRegistry cannot transition back from DESTROYED state
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
            Log.i(TAG, "Previous lifecycle was DESTROYED, creating fresh lifecycle")
            lifecycleRegistry = LifecycleRegistry(this)
        }

        // Start our custom lifecycle
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        Log.i(TAG, "Custom lifecycle set to RESUMED state")

        Log.i(TAG, "Requesting camera provider...")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                Log.i(TAG, "Camera provider obtained, binding to lifecycle (state: ${lifecycleRegistry.currentState})...")

                val analysisBuilder = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    targetSize,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

                // Exposure is controlled only after binding. Camera2Interop options attached to
                // the use case are ALWAYS_OVERRIDE in CameraX and therefore conflict with the
                // Camera2CameraControl options used when low-light mode switches AE off. This
                // surfaced on Pixel 4 as an Option values conflicts exception. The normal-mode
                // exposure compensation below and apply/restore methods own these settings.

                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(executor, this)

                // Try back camera first, fall back to front if unavailable
                val selector = try {
                    if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                        Log.i(TAG, "Using back camera")
                        CameraSelector.DEFAULT_BACK_CAMERA
                    } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                        Log.i(TAG, "Back camera unavailable, using front camera")
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        throw IllegalStateException("No camera available on this device")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Camera availability check failed", e)
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                provider.unbindAll()
                // Use our own lifecycle owner instead of the service's
                camera = provider.bindToLifecycle(this@CameraStreamer, selector, analysis)
                cameraProvider = provider
                isRunning.set(true)
                frameCount = 0
                maxCamerasErrorCount = 0

                // Observe camera state to detect ERROR_MAX_CAMERAS_IN_USE deadlock.
                // CameraX retries internally every 2s but each HAL attempt takes ~3s,
                // creating overlapping connections that permanently block the camera.
                // When detected, we unbind to break the retry loop and signal failure.
                camera?.cameraInfo?.cameraState?.observe(this@CameraStreamer, Observer { state ->
                    val error = state.error
                    if (error != null && error.code == CameraState.ERROR_MAX_CAMERAS_IN_USE) {
                        maxCamerasErrorCount++
                        Log.w(TAG, "ERROR_MAX_CAMERAS_IN_USE detected (count: $maxCamerasErrorCount)")
                        if (maxCamerasErrorCount >= 3) {
                            Log.e(TAG, "Camera stuck in retry loop - unbinding to break deadlock")
                            provider.unbindAll()
                            isRunning.set(false)
                            onCameraStateChanged?.invoke(false, "Camera busy (max cameras)")
                        }
                    } else if (state.type == CameraState.Type.OPEN) {
                        maxCamerasErrorCount = 0
                    }
                })

                // Try to maximize exposure compensation for dark rooms
                camera?.cameraControl?.let { control ->
                    // Set exposure to maximum for brighter image
                    camera?.cameraInfo?.exposureState?.let { exposureState ->
                        if (exposureState.isExposureCompensationSupported) {
                            val maxExposure = exposureState.exposureCompensationRange.upper
                            val targetExposure = minOf(maxExposure, 6)  // Cap at +3 stops
                            control.setExposureCompensationIndex(targetExposure)
                            Log.i(TAG, "Exposure compensation set to +$targetExposure (max: $maxExposure)")
                        }
                    }
                }

                Log.i(TAG, "Camera streamer started with resolution ${targetSize.width}x${targetSize.height} (low-res mode for bandwidth)")
                onCameraStateChanged?.invoke(true, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind camera stream", e)
                isRunning.set(false)
                onCameraStateChanged?.invoke(false, e.message ?: "Unknown camera error")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun isRunning(): Boolean = isRunning.get()

    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    override fun analyze(image: ImageProxy) {
        try {
            val nv21 = yuvToNv21(image)
            if (nv21 == null) {
                Log.w(TAG, "YUV conversion returned null for frame ${image.width}x${image.height}")
                return
            }

            val meanY = measureMeanLuminance(nv21, image.width, image.height)

            // Debounce: skip mode transitions while exposure is settling
            val framesSinceTransition = frameCount - modeTransitionFrame
            if (framesSinceTransition >= modeSettlingFrames) {
                // Hysteresis: enter night mode at Y<40, exit at Y>60
                if (!isLowLightMode && meanY < darkThreshold) {
                    isLowLightMode = true
                    modeTransitionFrame = frameCount
                    Log.i(TAG, "Entering low-light mode (meanY=$meanY)")
                    applyManualExposure()
                } else if (isLowLightMode && meanY > brightThreshold) {
                    isLowLightMode = false
                    modeTransitionFrame = frameCount
                    Log.i(TAG, "Exiting low-light mode (meanY=$meanY)")
                    nightModeProcessor.reset()
                    restoreAutoExposure()
                }
            }

            // #287: throttle JPEG *publishing* to the requested cadence. The luminance/night
            // bookkeeping above still runs every frame (cheap); here we skip the expensive
            // encode + publish until the interval elapses. Long.MAX_VALUE => never due => paused.
            val interval = encodeIntervalMs
            val now = android.os.SystemClock.elapsedRealtime()
            motionListener?.let { listener ->
                motionDetector.process(nv21, image.width, image.height, now)?.let(listener)
            }
            val due = interval <= 0L || (now - lastEmitMs) >= interval

            if (isLowLightMode) {
                // Always feed the averager (it needs a continuous frame stream); only publish
                // its output when due so cadence still applies in low light. Note: at night the
                // effective rate is also floored by the averager's preview rhythm (it emits a
                // result only every few frames), so a near-live request is clamped to that.
                val result = nightModeProcessor.addFrame(nv21, image.width, image.height)
                if (result != null && due) {
                    latestFrame.set(result)
                    lastEmitMs = now
                    frameListener?.invoke(result, now) // #337
                }
            } else if (due) {
                // Normal path: direct JPEG encode
                val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
                val stream = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 45, stream)
                val jpeg = stream.toByteArray()
                latestFrame.set(jpeg)
                lastEmitMs = now
                frameListener?.invoke(jpeg, now) // #337
            }

            frameCount++
            if (frameCount == 1) {
                Log.i(TAG, "First camera frame captured: ${image.width}x${image.height}")
            } else if (frameCount % 100 == 0) {
                Log.d(TAG, "Camera frames captured: $frameCount (nightMode=$isLowLightMode)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera frame processing failed", e)
        } finally {
            image.close()
        }
    }

    fun latestJpeg(): ByteArray? = latestFrame.get()

    fun isInNightMode(): Boolean = isLowLightMode

    /** Sample every 10th Y pixel to compute mean luminance. O(1) memory, negligible CPU. */
    private fun measureMeanLuminance(nv21: ByteArray, width: Int, height: Int): Int {
        val ySize = width * height
        var sum = 0L
        var count = 0
        var i = 0
        while (i < ySize) {
            sum += nv21[i].toInt() and 0xFF
            count++
            i += 10
        }
        return if (count > 0) (sum / count).toInt() else 0
    }

    /**
     * Switch to manual exposure for maximum light gathering.
     * Uses Camera2CameraControl to set AE off, max exposure time, and max ISO.
     * Falls back gracefully if the device doesn't support manual AE.
     */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun applyManualExposure() {
        val cam = camera ?: return
        try {
            val camera2Info = Camera2CameraInfo.from(cam.cameraInfo)
            val characteristics = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES
            )

            // Check if manual AE is supported
            if (characteristics == null || !characteristics.contains(CaptureRequest.CONTROL_AE_MODE_OFF)) {
                Log.w(TAG, "Manual AE not supported - relying on frame averaging + gamma only")
                return
            }

            val maxExposureTime = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )?.upper ?: 100_000_000L // 100ms fallback

            val maxIso = camera2Info.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE
            )?.upper ?: 3200

            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, maxExposureTime)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, maxIso)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(1, 5)
                )
                // Disabling AE can also disable AF on some HALs — keep AF continuous
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
                .build()

            camera2Control.captureRequestOptions = options
            manualExposureApplied = true
            Log.i(TAG, "Manual exposure: time=${maxExposureTime / 1_000_000}ms, ISO=$maxIso, fps=1-5")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply manual exposure, falling back to auto", e)
        }
    }

    /** Restore auto-exposure when exiting night mode. */
    @androidx.camera.camera2.interop.ExperimentalCamera2Interop
    private fun restoreAutoExposure() {
        if (!manualExposureApplied) return
        val cam = camera ?: return
        try {
            val camera2Control = Camera2CameraControl.from(cam.cameraControl)
            val options = CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                .clearCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME)
                .clearCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(15, 30)
                )
                .build()

            camera2Control.captureRequestOptions = options
            manualExposureApplied = false
            Log.i(TAG, "Restored auto exposure")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore auto exposure", e)
        }
    }

    fun stop() {
        if (!isRunning.get()) {
            Log.d(TAG, "Camera already stopped, ignoring stop request")
            return
        }
        try {
            // Transition lifecycle to destroyed state
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            cameraProvider?.unbindAll()
            Log.i(TAG, "Camera streamer stopped after $frameCount frames")
        } catch (e: Exception) {
            Log.w(TAG, "Camera unbind failed", e)
        }
        isRunning.set(false)
        isLowLightMode = false
        manualExposureApplied = false
        modeTransitionFrame = 0
        lastEmitMs = 0L // #287: next start publishes its first frame immediately
        motionDetector.reset()
        nightModeProcessor.reset()
        latestFrame.set(null)
        onCameraStateChanged?.invoke(false, null)
    }

    /**
     * #422: permanently release resources. Unlike [stop] — which leaves the instance reusable for a
     * later [start] (the camera-health restart and the user toggle both stop→start the SAME instance) —
     * this also shuts down the single-thread analyzer executor. `newSingleThreadExecutor` keeps its
     * worker thread alive indefinitely once started, so without this the thread leaks for the life of
     * the process when the streamer is discarded (MonitorService nulls it in onDestroy). Call exactly
     * once when the streamer is being thrown away; do NOT [start] it again afterwards.
     */
    fun release() {
        stop()
        executor.shutdownNow()
    }

    private fun yuvToNv21(image: ImageProxy): ByteArray? {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        // Reset positions so absolute addressing works
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        var outputOffset = ySize
        val width = image.width
        val height = image.height
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * uRowStride + col * uPixelStride
                val vIndex = row * vRowStride + col * vPixelStride
                if (uIndex >= uBuffer.limit() || vIndex >= vBuffer.limit()) continue
                nv21[outputOffset++] = vBuffer.get(vIndex)
                nv21[outputOffset++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    companion object {
        private const val TAG = "CameraStreamer"
    }
}
