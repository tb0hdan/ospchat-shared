package com.ospchat.shared.data.attachments

/**
 * Pre-flight check on peer-supplied image bytes — confirms the bytes are
 * a decodable image whose declared dimensions fit within [maxEdge] pixels
 * on either axis. Implementations MUST NOT allocate a full bitmap (use
 * the platform's "header peek" mode — BitmapFactory.inJustDecodeBounds
 * on Android, ImageReader on Desktop), so a decompression-bomb image
 * costs only the header parse, not the full RGBA expansion. Cf.
 * docs/SECURITY.md F4 / D6.
 *
 * Throws [IllegalArgumentException] if the bytes are not a decodable
 * image, or if either dimension exceeds [maxEdge].
 *
 * Caps for the two production call sites:
 *  - Attachments: peer fetches that already passed the 16 MiB
 *    Content-Length cap (see [com.ospchat.shared.net.client.MessageClient]).
 *    Our own compressor emits images ≤ 1920 px; we accept up to 4 K so
 *    legitimate higher-resolution senders aren't gratuitously rejected.
 *  - Avatars: same response-cap story; outbound is 256 px, inbound cap 1 K.
 */
interface ImageBounds {
    fun assertOk(
        bytes: ByteArray,
        maxEdge: Int,
    )

    companion object {
        /** Cap for `/v1/attachments/...` bytes (px on either axis). */
        const val ATTACHMENT_MAX_EDGE: Int = 4096

        /** Cap for `/v1/avatar` bytes (px on either axis). */
        const val AVATAR_MAX_EDGE: Int = 1024
    }
}
