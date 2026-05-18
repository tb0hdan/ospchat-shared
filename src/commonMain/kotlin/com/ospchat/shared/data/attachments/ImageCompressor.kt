package com.ospchat.shared.data.attachments

/**
 * Down-scales an arbitrary user-picked image to at most [DEFAULT_MAX_EDGE]
 * pixels on its longest edge, applies any EXIF orientation as a real pixel
 * rotation, and re-encodes the result as JPEG at quality 85.
 *
 * Baking the rotation into the pixels means the produced JPEG is visually
 * upright and contains no EXIF (Bitmap.compress / ImageIO write doesn't
 * preserve EXIF), so the receiver renders it as-is without needing its own
 * rotation pipeline. The reported [Result.width] / [Result.height] are the
 * dimensions *after* rotation.
 */
interface ImageCompressor {
    data class Result(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val mimeType: String,
    )

    fun compress(
        bytes: ByteArray,
        maxEdge: Int = DEFAULT_MAX_EDGE,
    ): Result

    companion object {
        const val DEFAULT_MAX_EDGE: Int = 1920
        const val JPEG_QUALITY: Int = 85
    }
}
