package com.ospchat.shared.data.attachments

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Covers the desktop [ImageBounds] implementation. The Android equivalent
 * (BitmapFactoryImageBounds) is a thin wrapper over Android-only APIs and
 * is not unit-tested here; both share the same contract — header peek
 * only, reject undecodable, reject over-cap dimensions. See
 * docs/SECURITY.md F4 / D6.
 */
class ImageIoImageBoundsTest {
    private val bounds = ImageIoImageBounds()

    @Test
    fun acceptsSmallJpeg() {
        val bytes = synthesizeJpeg(width = 64, height = 48)
        bounds.assertOk(bytes, maxEdge = 1024)
    }

    @Test
    fun acceptsSmallPng() {
        val bytes = synthesizePng(width = 32, height = 32)
        bounds.assertOk(bytes, maxEdge = 1024)
    }

    @Test
    fun rejectsOverCapDimensions() {
        // 2048 px on either axis with the avatar cap (1024).
        val bytes = synthesizePng(width = 2048, height = 32)
        assertFailsWith<IllegalArgumentException> {
            bounds.assertOk(bytes, maxEdge = 1024)
        }
    }

    @Test
    fun rejectsGarbageBytes() {
        val bytes = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        assertFailsWith<IllegalArgumentException> {
            bounds.assertOk(bytes, maxEdge = 1024)
        }
    }

    @Test
    fun rejectsEmpty() {
        assertFailsWith<IllegalArgumentException> {
            bounds.assertOk(byteArrayOf(), maxEdge = 1024)
        }
    }

    @Test
    fun atCapPasses() {
        val bytes = synthesizePng(width = 1024, height = 1024)
        bounds.assertOk(bytes, maxEdge = 1024)
    }

    private fun synthesizeJpeg(
        width: Int,
        height: Int,
    ): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", out)
        return out.toByteArray()
    }

    private fun synthesizePng(
        width: Int,
        height: Int,
    ): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
}
