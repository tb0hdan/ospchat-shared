package com.ospchat.shared.data.avatar

import com.ospchat.shared.platform.dataDir
import java.io.File

/**
 * Desktop file-system backed [AvatarStore]. Defaults to `<user-data>/avatar/`;
 * tests can pass an explicit [parentDir].
 */
class FileAvatarStore(
    private val parentDir: File = dataDir(),
) : AvatarStore {
    private val root: File by lazy {
        File(parentDir, "avatar").also { it.mkdirs() }
    }

    override fun selfPath(hash: String): String = File(root, "self-$hash.jpg").absolutePath

    override fun peerPath(
        uuid: String,
        hash: String,
    ): String = File(root, "peer-$uuid-$hash.jpg").absolutePath

    override fun selfExists(hash: String): Boolean = File(root, "self-$hash.jpg").isFile

    override fun peerExists(
        uuid: String,
        hash: String,
    ): Boolean = File(root, "peer-$uuid-$hash.jpg").isFile

    override fun writeSelf(
        bytes: ByteArray,
        hash: String,
    ): String {
        val target = File(root, "self-$hash.jpg")
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun writePeer(
        uuid: String,
        hash: String,
        bytes: ByteArray,
    ): String {
        val target = File(root, "peer-$uuid-$hash.jpg")
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun readSelf(hash: String): ByteArray? {
        val target = File(root, "self-$hash.jpg")
        return if (target.isFile) target.readBytes() else null
    }

    override fun cleanupSelfExcept(keepHash: String?) {
        root.listFiles { f -> f.name.startsWith("self-") }?.forEach { file ->
            val keep = keepHash != null && file.name == "self-$keepHash.jpg"
            if (!keep) file.delete()
        }
    }

    override fun cleanupPeerExcept(
        uuid: String,
        keepHash: String?,
    ) {
        root.listFiles { f -> f.name.startsWith("peer-$uuid-") }?.forEach { file ->
            val keep = keepHash != null && file.name == "peer-$uuid-$keepHash.jpg"
            if (!keep) file.delete()
        }
    }
}
