package com.ospchat.shared.data.attachments

import com.ospchat.shared.platform.dataDir
import java.io.File

/**
 * Desktop file-system backed [AttachmentStore]. Defaults to `<user-data>/attachments/`
 * via [dataDir]; tests can pass an explicit [parentDir].
 */
class FileAttachmentStore(
    private val parentDir: File = dataDir(),
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
