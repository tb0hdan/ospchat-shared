package com.ospchat.shared.data.attachments

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageIoCompressorTest {
    private fun makeJpegBytes(
        width: Int,
        height: Int,
    ): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color.RED
            g.fillRect(0, 0, width / 2, height)
            g.color = Color.BLUE
            g.fillRect(width / 2, 0, width / 2, height)
        } finally {
            g.dispose()
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", baos)
        return baos.toByteArray()
    }

    @Test
    fun smallImagePassesThroughUnscaled() {
        val src = makeJpegBytes(800, 600)
        val out = ImageIoCompressor().compress(src, maxEdge = 1920)
        assertEquals(800, out.width)
        assertEquals(600, out.height)
        assertEquals("image/jpeg", out.mimeType)
        assertTrue(out.bytes.isNotEmpty())
    }

    @Test
    fun largeImageScaledToMaxEdge() {
        val src = makeJpegBytes(4000, 3000)
        val out = ImageIoCompressor().compress(src, maxEdge = 1920)
        assertEquals(1920, out.width)
        assertEquals(1440, out.height) // 3000 * (1920/4000)
    }

    @Test
    fun portraitOrientationHandled() {
        val src = makeJpegBytes(1200, 3600)
        val out = ImageIoCompressor().compress(src, maxEdge = 1920)
        // Longest edge clamps to 1920; aspect ratio preserved.
        assertEquals(1920, out.height)
        assertEquals(640, out.width)
    }

    @Test
    fun outputIsDecodableJpeg() {
        val src = makeJpegBytes(2400, 1800)
        val out = ImageIoCompressor().compress(src, maxEdge = 1920)
        val decoded = ByteArrayInputStream(out.bytes).use { ImageIO.read(it) }
        assertEquals(out.width, decoded.width)
        assertEquals(out.height, decoded.height)
    }

    @Test
    fun rejectsGarbageBytes() {
        try {
            ImageIoCompressor().compress(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            error("expected IllegalStateException for non-image bytes")
        } catch (expected: IllegalStateException) {
            // ok — wraps ImageIO failure
        }
    }
}
