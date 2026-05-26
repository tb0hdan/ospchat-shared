package com.ospchat.shared.data.avatar

import com.ospchat.shared.util.requireSafeFileComponent
import java.io.File

/**
 * File-system backed [AvatarStore]. Filenames embed the SHA-256 hash so
 * content changes surface as path changes, defeating any image-loader
 * memory cache that keys on path.
 */
class FileAvatarStore(
    private val parentDir: File,
) : AvatarStore {
    private val root: File by lazy {
        File(parentDir, "avatar").also { it.mkdirs() }
    }
    private val rootCanonical: File by lazy { root.canonicalFile }

    override fun selfPath(hash: String): String = selfTarget(hash).absolutePath

    override fun peerPath(
        uuid: String,
        hash: String,
    ): String = peerTarget(uuid, hash).absolutePath

    override fun selfExists(hash: String): Boolean = selfTarget(hash).isFile

    override fun peerExists(
        uuid: String,
        hash: String,
    ): Boolean = peerTarget(uuid, hash).isFile

    override fun writeSelf(
        bytes: ByteArray,
        hash: String,
    ): String {
        val target = selfTarget(hash)
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun writePeer(
        uuid: String,
        hash: String,
        bytes: ByteArray,
    ): String {
        val target = peerTarget(uuid, hash)
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun readSelf(hash: String): ByteArray? {
        val target = selfTarget(hash)
        return if (target.isFile) target.readBytes() else null
    }

    override fun readPeer(
        uuid: String,
        hash: String,
    ): ByteArray? {
        val target = peerTarget(uuid, hash)
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
        requireSafeFileComponent(uuid, "uuid")
        root.listFiles { f -> f.name.startsWith("peer-$uuid-") }?.forEach { file ->
            val keep = keepHash != null && file.name == "peer-$uuid-$keepHash.jpg"
            if (!keep) file.delete()
        }
    }

    private fun selfTarget(hash: String): File {
        requireSafeFileComponent(hash, "hash")
        return verifyInsideRoot(File(root, "self-$hash.jpg"))
    }

    private fun peerTarget(
        uuid: String,
        hash: String,
    ): File {
        requireSafeFileComponent(uuid, "uuid")
        requireSafeFileComponent(hash, "hash")
        return verifyInsideRoot(File(root, "peer-$uuid-$hash.jpg"))
    }

    /**
     * Belt-and-suspenders: after the character check, confirm the resolved
     * file is a direct child of [root]. Cf. docs/SECURITY.md F2.
     */
    private fun verifyInsideRoot(target: File): File {
        require(target.canonicalFile.parentFile == rootCanonical) {
            "avatar path resolves outside avatar root"
        }
        return target
    }
}
