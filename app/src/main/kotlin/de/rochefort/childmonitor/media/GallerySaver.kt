/*
 * This file is part of Child Monitor.
 *
 * Child Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Child Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package de.rochefort.childmonitor.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * #288: save a snapshot bitmap into the device's photo gallery (Pictures/ChildMonitor) so a parent
 * can keep a moment. Local-only; the image never leaves the phone unless the user shares it.
 *
 * The filename is pure + UTC-stable so it is unit-tested. The write is API-aware: API 29+ uses a
 * scoped MediaStore insert (no storage permission), pre-29 writes the file and registers it (needs
 * WRITE_EXTERNAL_STORAGE, declared with maxSdkVersion=28).
 */
object GallerySaver {

    private const val ALBUM = "ChildMonitor"
    private const val TAG = "GallerySaver"

    /** Deterministic, filesystem-safe, UTC-based gallery filename. */
    fun fileNameFor(epochMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return "ChildMonitor-${fmt.format(Date(epochMs))}.jpg"
    }

    /** Save [bitmap] as a JPEG into the gallery. Returns true on success. Safe to call off the UI thread. */
    fun saveJpeg(context: Context, bitmap: Bitmap, epochMs: Long, quality: Int = 90): Boolean =
        writeJpeg(context, epochMs) { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out) }

    /**
     * #370: save raw JPEG [jpeg] bytes — the child's actual frame, with no decode/re-encode round-trip
     * — into the gallery. Higher fidelity than re-compressing the downscaled RGB_565 preview bitmap.
     * Returns true on success. Safe to call off the UI thread.
     */
    fun saveJpegBytes(context: Context, jpeg: ByteArray, epochMs: Long): Boolean {
        if (jpeg.isEmpty()) return false
        return writeJpeg(context, epochMs) { out -> out.write(jpeg); true }
    }

    /**
     * Shared gallery-write plumbing (API-29 scoped MediaStore vs pre-29 legacy file + registration);
     * [write] does the actual write into the opened stream and returns true iff it fully succeeded.
     */
    private fun writeJpeg(context: Context, epochMs: Long, write: (OutputStream) -> Boolean): Boolean {
        val name = fileNameFor(epochMs)
        val resolver = context.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                Log.w(TAG, "snapshot save: MediaStore insert failed", e); null
            } ?: return false
            try {
                var ok = false
                resolver.openOutputStream(uri).use { out ->
                    ok = out != null && write(out)
                }
                if (!ok) {
                    resolver.delete(uri, null, null)
                    return false
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                // Don't leave an invisible IS_PENDING orphan behind.
                Log.w(TAG, "snapshot save: write failed", e)
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, name)
                FileOutputStream(file).use { out ->
                    if (!write(out)) return false
                }
                // Register the file so the gallery indexes it — report a failed registration honestly.
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) != null
            } catch (e: Exception) {
                Log.w(TAG, "snapshot save (legacy) failed", e)
                false
            }
        }
    }
}
