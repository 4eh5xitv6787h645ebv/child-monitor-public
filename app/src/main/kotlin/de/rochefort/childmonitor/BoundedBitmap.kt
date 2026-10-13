package de.rochefort.childmonitor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Bounded JPEG decoding for parent-side camera snapshots (#208).
 *
 * The parent fetches snapshots from a child over the LAN and decoded them with an
 * unbounded `BitmapFactory.decodeStream`, so a hostile or misbehaving host (the
 * threat model of a parent pointed at a MITM/compromised peer) could serve a huge
 * image and OOM the parent — which, for a monitoring app, takes the watcher
 * offline. This caps both the bytes read off the wire and the decoded pixel
 * dimensions before allocating the bitmap.
 *
 * [computeInSampleSize] and [readBounded] are pure (no Android) and unit-tested;
 * [decodeBoundedStream] is the thin `BitmapFactory` glue that composes them.
 */
object BoundedBitmap {

    /** Power-of-two subsample factor so neither side exceeds [maxDimension]. */
    fun computeInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (maxDimension <= 0 || width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    /**
     * Read up to [maxBytes] from [stream]. Returns null if the stream carries
     * MORE than [maxBytes] — we refuse to buffer an oversized payload rather than
     * letting it grow unbounded.
     */
    fun readBounded(stream: InputStream, maxBytes: Int): ByteArray? {
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            total += n
            if (total > maxBytes) return null
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    /**
     * Decode [stream] into a bitmap, rejecting payloads larger than [maxBytes]
     * and subsampling so neither dimension exceeds [maxDimension]. Returns null
     * if the payload is too large or not a decodable image.
     */
    fun decodeBoundedStream(
        stream: InputStream,
        maxBytes: Int,
        maxDimension: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888
    ): Bitmap? {
        val bytes = readBounded(stream, maxBytes) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            inPreferredConfig = config
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** ~12 MB: far above any legitimate camera JPEG, low enough to refuse a flood. */
    const val MAX_SNAPSHOT_BYTES = 12 * 1024 * 1024

    /** Cap decoded snapshots to 2048 px on the long edge — ample for the preview. */
    const val MAX_SNAPSHOT_DIMENSION = 2048
}
