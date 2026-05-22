package com.ospchat.shared.data.attachments

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Desktop [ImageBounds] backed by `javax.imageio.ImageReader`. We open an
 * `ImageInputStream`, ask `ImageIO.getImageReaders` for a matching reader,
 * and read just the width/height — no pixel decode happens. A
 * decompression-bomb image therefore costs only the header parse, not
 * the full RGBA expansion that would otherwise OOM the JVM. Cf.
 * docs/SECURITY.md F4 / D6.
 */
class ImageIoImageBounds : ImageBounds {
    override fun assertOk(
        bytes: ByteArray,
        maxEdge: Int,
    ) {
        val iis =
            ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
                ?: throw IllegalArgumentException("bytes are not a decodable image")
        try {
            val readers = ImageIO.getImageReaders(iis)
            require(readers.hasNext()) { "no ImageIO reader matches bytes" }
            val reader = readers.next()
            try {
                reader.input = iis
                val w =
                    try {
                        reader.getWidth(0)
                    } catch (t: IOException) {
                        throw IllegalArgumentException("malformed image header", t)
                    }
                val h =
                    try {
                        reader.getHeight(0)
                    } catch (t: IOException) {
                        throw IllegalArgumentException("malformed image header", t)
                    }
                require(w > 0 && h > 0) { "invalid image dimensions: ${w}x$h" }
                require(w <= maxEdge && h <= maxEdge) {
                    "image ${w}x$h exceeds cap ${maxEdge}px"
                }
            } finally {
                reader.dispose()
            }
        } finally {
            iis.close()
        }
    }
}
