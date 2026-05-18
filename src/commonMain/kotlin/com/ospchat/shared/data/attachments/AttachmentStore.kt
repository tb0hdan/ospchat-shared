package com.ospchat.shared.data.attachments

/**
 * On-disk attachment binary store. Each message's bytes live at
 * `<root>/attachments/<messageId>.bin`. [pathFor] returns the absolute path so
 * image loaders (Coil, Skia/Compose Image) can read directly from disk.
 *
 * Implementations are file-system backed; the root is platform-determined
 * (Android: `Context.filesDir`; Desktop: per-OS user-data dir).
 */
interface AttachmentStore {
    fun pathFor(messageId: String): String

    fun exists(messageId: String): Boolean

    fun writeBytes(
        messageId: String,
        bytes: ByteArray,
    ): String

    /** Read the attachment bytes for [messageId], or `null` if not stored. */
    fun readBytes(messageId: String): ByteArray?
}
