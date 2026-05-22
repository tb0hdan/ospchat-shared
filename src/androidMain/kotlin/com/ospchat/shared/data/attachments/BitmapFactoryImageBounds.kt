package com.ospchat.shared.data.attachments

import android.graphics.BitmapFactory

/**
 * Android [ImageBounds] backed by [BitmapFactory.Options.inJustDecodeBounds].
 * The "just bounds" mode parses the header to populate `outWidth` /
 * `outHeight` without allocating any bitmap pixels, so a 50 KB JPEG that
 * decodes to 100k × 100k px costs only the header parse — not the ~40 GB
 * RGBA expansion that would otherwise OOM the process. Cf.
 * docs/SECURITY.md F4 / D6.
 */
class BitmapFactoryImageBounds : ImageBounds {
    override fun assertOk(
        bytes: ByteArray,
        maxEdge: Int,
    ) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val w = options.outWidth
        val h = options.outHeight
        require(w > 0 && h > 0) { "bytes are not a decodable image" }
        require(w <= maxEdge && h <= maxEdge) {
            "image ${w}x$h exceeds cap ${maxEdge}px"
        }
    }
}
