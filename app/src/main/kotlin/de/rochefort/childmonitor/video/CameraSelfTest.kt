package de.rochefort.childmonitor.video

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Camera capability self-test and startup health checks.
 * Implements CM-VID-011.
 *
 * Produces a structured report that the diagnostics screen can surface.
 * Callers can gate camera-based features on specific capabilities.
 */
object CameraSelfTest {

    data class Report(
        val ok: Boolean,
        val hasCameraFeature: Boolean,
        val hasAutoFocus: Boolean,
        val hasFlash: Boolean,
        val hasFrontCamera: Boolean,
        val permissionGranted: Boolean,
        val warnings: List<String>
    ) {
        fun toJson(): JSONObject {
            val warn = JSONArray()
            for (w in warnings) warn.put(w)
            return JSONObject()
                .put("ok", ok)
                .put("hasCameraFeature", hasCameraFeature)
                .put("hasAutoFocus", hasAutoFocus)
                .put("hasFlash", hasFlash)
                .put("hasFrontCamera", hasFrontCamera)
                .put("permissionGranted", permissionGranted)
                .put("warnings", warn)
        }
    }

    fun run(context: Context, permissionGranted: Boolean): Report {
        val pm = context.packageManager
        val has = { feat: String -> pm.hasSystemFeature(feat) }
        val warnings = mutableListOf<String>()
        val hasCamera = has(PackageManager.FEATURE_CAMERA_ANY)
        if (!hasCamera) warnings.add("no camera hardware detected")
        if (!permissionGranted) warnings.add("camera permission not granted")

        val hasAutoFocus = has(PackageManager.FEATURE_CAMERA_AUTOFOCUS)
        if (!hasAutoFocus) warnings.add("auto-focus unavailable, sharpness may vary")
        val hasFlash = has(PackageManager.FEATURE_CAMERA_FLASH)
        val hasFront = has(PackageManager.FEATURE_CAMERA_FRONT)

        return Report(
            ok = warnings.isEmpty(),
            hasCameraFeature = hasCamera,
            hasAutoFocus = hasAutoFocus,
            hasFlash = hasFlash,
            hasFrontCamera = hasFront,
            permissionGranted = permissionGranted,
            warnings = warnings
        )
    }
}
