package com.ospchat.shared.data.attachments

import com.ospchat.shared.platform.dataDir
import com.ospchat.shared.util.requireSafeFileComponent
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
    private val rootCanonical: File by lazy { root.canonicalFile }

    override fun pathFor(messageId: String): String = targetFor(messageId).absolutePath

    override fun exists(messageId: String): Boolean = targetFor(messageId).isFile

    override fun writeBytes(
        messageId: String,
        bytes: ByteArray,
    ): String {
        val target = targetFor(messageId)
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun readBytes(messageId: String): ByteArray? {
        val target = targetFor(messageId)
        return if (target.isFile) target.readBytes() else null
    }

    /**
     * Resolve the on-disk file for [messageId]. The character check rules out
     * path-traversal sequences; the canonical-parent check is belt-and-suspenders
     * against any future relaxation of the allowed set. Cf. docs/SECURITY.md F1.
     */
    private fun targetFor(messageId: String): File {
        requireSafeFileComponent(messageId, "messageId")
        val target = File(root, "$messageId.bin")
        require(target.canonicalFile.parentFile == rootCanonical) {
            "messageId resolves outside attachment root"
        }
        return target
    }
}
