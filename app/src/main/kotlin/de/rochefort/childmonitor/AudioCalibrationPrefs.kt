package de.rochefort.childmonitor

import android.content.Context

/**
 * Shared preference-backed audio calibration values.
 * These multipliers are local-only and applied on-device.
 */
object AudioCalibrationPrefs {
    private const val PREFS_NAME = "AudioCalibrationPreferences"
    private const val KEY_CAPTURE_GAIN_MULTIPLIER = "captureGainMultiplier"
    private const val KEY_PLAYBACK_GAIN_MULTIPLIER = "playbackGainMultiplier"

    const val DEFAULT_CAPTURE_GAIN_MULTIPLIER = 1.0f
    const val DEFAULT_PLAYBACK_GAIN_MULTIPLIER = 1.0f

    const val MIN_CAPTURE_GAIN_MULTIPLIER = 1.0f
    const val MAX_CAPTURE_GAIN_MULTIPLIER = 3.0f
    const val MIN_PLAYBACK_GAIN_MULTIPLIER = 1.0f
    const val MAX_PLAYBACK_GAIN_MULTIPLIER = 2.5f

    fun clampCapture(multiplier: Float): Float =
        multiplier.coerceIn(MIN_CAPTURE_GAIN_MULTIPLIER, MAX_CAPTURE_GAIN_MULTIPLIER)

    fun clampPlayback(multiplier: Float): Float =
        multiplier.coerceIn(MIN_PLAYBACK_GAIN_MULTIPLIER, MAX_PLAYBACK_GAIN_MULTIPLIER)

    fun getCaptureGainMultiplier(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return clampCapture(
            prefs.getFloat(KEY_CAPTURE_GAIN_MULTIPLIER, DEFAULT_CAPTURE_GAIN_MULTIPLIER)
        )
    }

    fun getPlaybackGainMultiplier(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return clampPlayback(
            prefs.getFloat(KEY_PLAYBACK_GAIN_MULTIPLIER, DEFAULT_PLAYBACK_GAIN_MULTIPLIER)
        )
    }

    fun setCaptureGainMultiplier(context: Context, multiplier: Float) {
        val safe = clampCapture(multiplier)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_CAPTURE_GAIN_MULTIPLIER, safe)
            .apply()
    }

    fun setPlaybackGainMultiplier(context: Context, multiplier: Float) {
        val safe = clampPlayback(multiplier)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putFloat(KEY_PLAYBACK_GAIN_MULTIPLIER, safe)
            .apply()
    }
}
