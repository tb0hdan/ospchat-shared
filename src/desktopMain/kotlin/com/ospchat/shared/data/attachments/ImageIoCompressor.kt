package com.ospchat.shared.data.attachments

import com.ospchat.shared.data.attachments.ImageCompressor.Companion.JPEG_QUALITY
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Desktop [ImageCompressor] backed by `javax.imageio`. Decodes via
 * `ImageIO.read`, draws into a smaller BufferedImage at `BILINEAR`
 * interpolation, writes back as JPEG via a quality-tuned `ImageWriteParam`.
 *
 * Note: EXIF orientation is **not** applied. Almost all desktop-picked images
 * are already upright; phone-taken JPEGs that include an EXIF rotation tag
 * will currently render in their stored orientation. If we start seeing
 * rotated images in practice, plug `com.drewnoakes:metadata-extractor` into
 * the front of this pipeline.
 */
class ImageIoCompressor : ImageCompressor {
    override fun compress(
        bytes: ByteArray,
        maxEdge: Int,
    ): ImageCompressor.Result {
        val source =
            ByteArrayInputStream(bytes).use { ImageIO.read(it) }
                ?: error("ImageIO could not decode image bytes")

        val scaled = scaleToFit(source, maxEdge)

        val baos = ByteArrayOutputStream()
        encodeJpeg(scaled, baos, JPEG_QUALITY)

        return ImageCompressor.Result(
            bytes = baos.toByteArray(),
            width = scaled.width,
            height = scaled.height,
            mimeType = "image/jpeg",
        )
    }

    private fun scaleToFit(
        source: BufferedImage,
        maxEdge: Int,
    ): BufferedImage {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source
        val ratio = maxEdge.toDouble() / longest
        val targetW = (source.width * ratio).toInt().coerceAtLeast(1)
        val targetH = (source.height * ratio).toInt().coerceAtLeast(1)

        val dest = BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB)
        val g = dest.createGraphics()
        try {
            g.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY,
            )
            g.drawImage(source, 0, 0, targetW, targetH, null)
        } finally {
            g.dispose()
        }
        return dest
    }

    private fun encodeJpeg(
        image: BufferedImage,
        out: ByteArrayOutputStream,
        quality: Int,
    ) {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val params =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality / 100f
            }
        val ios = ImageIO.createImageOutputStream(out)
        try {
            writer.output = ios
            writer.write(null, IIOImage(image, null, null), params)
        } finally {
            writer.dispose()
            ios.close()
        }
    }
}
