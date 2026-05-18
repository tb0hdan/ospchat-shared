package com.ospchat.shared.data.attachments

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.ospchat.shared.data.attachments.ImageCompressor.Companion.JPEG_QUALITY
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Android [ImageCompressor] backed by [BitmapFactory] + [Matrix] + EXIF
 * orientation parsing. Two-pass decode: bounds first to pick an inSampleSize,
 * then the real decode at the sampled resolution, then exact-scale to the
 * `maxEdge` longest-edge constraint, then EXIF rotation as a pixel transform,
 * then JPEG re-encode.
 */
class AndroidImageCompressor : ImageCompressor {
    override fun compress(
        bytes: ByteArray,
        maxEdge: Int,
    ): ImageCompressor.Result {
        val orientation =
            ByteArrayInputStream(bytes).use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }

        // First pass: dimensions only.
        val dimensionOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensionOptions)
        val sourceWidth = dimensionOptions.outWidth
        val sourceHeight = dimensionOptions.outHeight
        require(sourceWidth > 0 && sourceHeight > 0) { "Image bytes did not decode" }

        val sampleSize = computeSampleSize(sourceWidth, sourceHeight, maxEdge)

        // Second pass: decode at the sampled resolution.
        val decodeOptions =
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        val sampledBitmap =
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                ?: error("BitmapFactory could not decode image bytes")

        val scaled = scaleToFit(sampledBitmap, maxEdge)
        if (scaled !== sampledBitmap) sampledBitmap.recycle()

        val rotated = applyExifRotation(scaled, orientation)
        if (rotated !== scaled) scaled.recycle()

        val baos = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        val width = rotated.width
        val height = rotated.height
        rotated.recycle()

        return ImageCompressor.Result(
            bytes = baos.toByteArray(),
            width = width,
            height = height,
            mimeType = "image/jpeg",
        )
    }

    private fun computeSampleSize(
        srcW: Int,
        srcH: Int,
        maxEdge: Int,
    ): Int {
        var sample = 1
        var w = srcW
        var h = srcH
        while (w / 2 >= maxEdge || h / 2 >= maxEdge) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleToFit(
        bitmap: Bitmap,
        maxEdge: Int,
    ): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        val newW = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun applyExifRotation(
        bitmap: Bitmap,
        orientation: Int,
    ): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> {
                return bitmap
            }

            ExifInterface.ORIENTATION_ROTATE_90 -> {
                matrix.postRotate(90f)
            }

            ExifInterface.ORIENTATION_ROTATE_180 -> {
                matrix.postRotate(180f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 -> {
                matrix.postRotate(270f)
            }

            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                matrix.preScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.preScale(1f, -1f)
            }

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }

            else -> {
                return bitmap
            }
        }
        // Last arg is `filter` — bilinear-resample during the matrix transform.
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
