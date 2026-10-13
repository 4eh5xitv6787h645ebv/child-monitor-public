package de.rochefort.childmonitor

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Accumulates multiple low-light camera frames and averages the Y (luminance)
 * channel to reduce noise, then applies gamma correction to brighten the result.
 *
 * At ~3fps long-exposure, 16 frames takes ~5s. Intermediate previews are
 * produced every [previewInterval] frames so the parent sees progressive updates.
 */
class NightModeProcessor(
    private val targetFrames: Int = 16,
    private val previewInterval: Int = 4,
    private val jpegQuality: Int = 75,
    private val gamma: Double = 0.45
) {
    private var accumulator: IntArray? = null
    private var latestUv: ByteArray? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var accumulatedFrames = 0

    /**
     * Add a frame's NV21 data to the accumulator.
     * Returns a JPEG byte array when a preview or final result is ready, null otherwise.
     */
    fun addFrame(nv21: ByteArray, width: Int, height: Int): ByteArray? {
        val ySize = width * height
        val uvSize = nv21.size - ySize

        // Reset accumulator if dimensions changed
        if (width != frameWidth || height != frameHeight) {
            frameWidth = width
            frameHeight = height
            accumulator = IntArray(ySize)
            accumulatedFrames = 0
        }

        val acc = accumulator ?: IntArray(ySize).also { accumulator = it }

        // Accumulate Y channel values
        for (i in 0 until ySize) {
            acc[i] += nv21[i].toInt() and 0xFF
        }

        // Keep latest UV data (chroma noise is imperceptible)
        if (latestUv == null || latestUv!!.size != uvSize) {
            latestUv = ByteArray(uvSize)
        }
        System.arraycopy(nv21, ySize, latestUv!!, 0, uvSize)

        accumulatedFrames++

        val isPreview = accumulatedFrames % previewInterval == 0 && accumulatedFrames < targetFrames
        val isFinal = accumulatedFrames >= targetFrames

        if (!isPreview && !isFinal) return null

        val result = buildResult()

        if (isFinal) {
            Log.d(TAG, "Night mode: final result from $accumulatedFrames frames")
            reset()
        } else {
            Log.d(TAG, "Night mode: preview at $accumulatedFrames/$targetFrames frames")
        }

        return result
    }

    private fun buildResult(): ByteArray {
        val acc = accumulator!!
        val uv = latestUv!!
        val ySize = frameWidth * frameHeight
        val nv21Out = ByteArray(ySize + uv.size)

        // Average Y channel and apply gamma correction
        val count = accumulatedFrames
        for (i in 0 until ySize) {
            val averaged = acc[i] / count
            nv21Out[i] = GAMMA_LUT[averaged].toByte()
        }

        // Copy UV unchanged
        System.arraycopy(uv, 0, nv21Out, ySize, uv.size)

        val yuvImage = YuvImage(nv21Out, ImageFormat.NV21, frameWidth, frameHeight, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, frameWidth, frameHeight), jpegQuality, stream)
        return stream.toByteArray()
    }

    fun reset() {
        accumulator?.let { it.fill(0) }
        accumulatedFrames = 0
    }

    fun isAccumulating(): Boolean = accumulatedFrames > 0

    companion object {
        private const val TAG = "NightModeProcessor"

        /** Pre-computed gamma lookup table: index 0-255 -> corrected 0-255 */
        private val GAMMA_LUT = IntArray(256) { i ->
            val normalized = i / 255.0
            val corrected = Math.pow(normalized, 0.45) * 255.0
            corrected.toInt().coerceIn(0, 255)
        }
    }
}
