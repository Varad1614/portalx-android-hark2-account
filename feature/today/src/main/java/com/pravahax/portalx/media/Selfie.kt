package com.pravahax.portalx.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns a full-resolution camera capture into the check-in upload:
 *  - decoded with power-of-two subsampling (a 12 MP bitmap is never held in memory),
 *  - EXIF orientation applied to the pixels (all 8 orientations),
 *  - scaled to ≤ [MAX_EDGE] px on the long edge, JPEG quality [QUALITY],
 *  - every metadata segment except JFIF/ICC removed (no EXIF: no GPS, camera serial, timestamps).
 * Bitmap.compress already writes no EXIF; [stripMetadata] makes that a guarantee rather than an assumption.
 */
object Selfie {
    const val MAX_EDGE = 1280
    const val QUALITY = 80

    /** Largest power-of-two sample size that still leaves the long edge ≥ [maxEdge] (final scale is done exactly). */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        var sample = 1
        val long = maxOf(width, height)
        while (long / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** Output size for a [width]×[height] source already in display orientation. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val long = maxOf(width, height)
        if (long <= maxEdge) return width to height
        val s = maxEdge.toDouble() / long
        return maxOf(1, Math.round(width * s).toInt()).coerceAtMost(maxEdge) to maxOf(1, Math.round(height * s).toInt()).coerceAtMost(maxEdge)
    }

    /** True when the EXIF orientation swaps width and height. */
    fun swapsAxes(orientation: Int): Boolean = orientation in setOf(
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
    )

    /** Matrix that brings the stored pixels upright for an EXIF orientation. */
    fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> {}
        }
    }

    fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /**
     * Removes APP1 (EXIF/XMP), APP3–APP15, COM and non-ICC APP2 segments from a JPEG. Keeps APP0 (JFIF), ICC
     * profiles and everything from the first SOS on. Returns the input unchanged if it isn't a parseable JPEG.
     */
    fun stripMetadata(jpeg: ByteArray): ByteArray {
        if (jpeg.size < 4 || (jpeg[0].toInt() and 0xFF) != 0xFF || (jpeg[1].toInt() and 0xFF) != 0xD8) return jpeg
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var i = 2
        while (i + 4 <= jpeg.size) {
            if ((jpeg[i].toInt() and 0xFF) != 0xFF) return jpeg // corrupt: don't guess
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue } // fill byte
            if (marker == 0xDA) { out.write(jpeg, i, jpeg.size - i); return out.toByteArray() } // SOS: entropy data follows
            if (marker == 0x01 || marker in 0xD0..0xD7) { out.write(jpeg, i, 2); i += 2; continue } // no length
            val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (len < 2 || i + 2 + len > jpeg.size) return jpeg
            val drop = when (marker) {
                0xE1, 0xFE -> true
                0xE2 -> !String(jpeg, i + 4, minOf(11, len - 2), Charsets.US_ASCII).startsWith("ICC_PROFILE")
                in 0xE3..0xEF -> true
                else -> false
            }
            if (!drop) out.write(jpeg, i, 2 + len)
            i += 2 + len
        }
        return out.toByteArray()
    }

    /** Decodes, orients, scales and re-encodes [src] into [dest] (EXIF-free). Throws if the capture can't be read. */
    fun process(src: File, dest: File, maxEdge: Int = MAX_EDGE, quality: Int = QUALITY): File {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "empty capture" }
        val sample = sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        val decoded = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("decode failed")
        val orientation = readOrientation(src)
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
        val m = orientationMatrix(orientation).apply { if (scale < 1f) postScale(scale, scale) }
        val out = if (!m.isIdentity) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true) else decoded
        try {
            val bytes = ByteArrayOutputStream().use { s -> out.compress(Bitmap.CompressFormat.JPEG, quality, s); s.toByteArray() }
            dest.writeBytes(stripMetadata(bytes))
        } finally {
            if (out !== decoded) out.recycle()
            decoded.recycle()
        }
        return dest
    }
}
