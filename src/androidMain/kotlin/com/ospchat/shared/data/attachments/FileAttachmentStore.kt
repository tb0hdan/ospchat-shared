package com.ospchat.shared.data.attachments

import java.io.File

/**
 * File-system backed [AttachmentStore]. [parentDir] is the platform-supplied
 * root (Android: `context.filesDir`; Desktop: per-OS user-data dir). The
 * `attachments/` subdirectory is created lazily on first use.
 */
class FileAttachmentStore(
    private val parentDir: File,
) : AttachmentStore {
    private val root: File by lazy {
        File(parentDir, "attachments").also { it.mkdirs() }
    }

    override fun pathFor(messageId: String): String = File(root, "$messageId.bin").absolutePath

    override fun exists(messageId: String): Boolean = File(root, "$messageId.bin").isFile

    override fun writeBytes(
        messageId: String,
        bytes: ByteArray,
    ): String {
        val target = File(root, "$messageId.bin")
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun readBytes(messageId: String): ByteArray? {
        val target = File(root, "$messageId.bin")
        return if (target.isFile) target.readBytes() else null
    }
}
