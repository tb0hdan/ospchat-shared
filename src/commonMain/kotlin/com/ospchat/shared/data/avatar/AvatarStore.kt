package com.ospchat.shared.data.avatar

/**
 * On-disk user-avatar store. Filenames embed the SHA-256 hash of the JPEG so
 * content changes surface as path changes — important because image-loader
 * caches typically key on path, and a stable path would let stale bitmaps
 * linger after the disk bytes are overwritten.
 *
 * `self-<hash>.jpg` is the local user's avatar; `peer-<uuid>-<hash>.jpg` is
 * a remote peer's cached avatar.
 */
interface AvatarStore {
    fun selfPath(hash: String): String

    fun peerPath(
        uuid: String,
        hash: String,
    ): String

    fun selfExists(hash: String): Boolean

    fun peerExists(
        uuid: String,
        hash: String,
    ): Boolean

    fun writeSelf(
        bytes: ByteArray,
        hash: String,
    ): String

    fun writePeer(
        uuid: String,
        hash: String,
        bytes: ByteArray,
    ): String

    /** Read the local user's avatar JPEG bytes for [hash], or `null` if not stored. */
    fun readSelf(hash: String): ByteArray?

    /**
     * Read the cached avatar JPEG bytes for peer [uuid] at [hash], or `null`
     * if no file matches. Phase 4 multi-network bridging: a relay-enabled
     * bridge serves these to phantom-peer consumers via
     * `GET /v1/peer-avatar/{uuid}` when the consumer can't reach the
     * original peer directly.
     */
    fun readPeer(
        uuid: String,
        hash: String,
    ): ByteArray?

    /** Delete any local self-avatar files except the one for [keepHash] (if any). */
    fun cleanupSelfExcept(keepHash: String?)

    /** Delete any cached avatar files for [uuid] except the one for [keepHash] (if any). */
    fun cleanupPeerExcept(
        uuid: String,
        keepHash: String?,
    )
}
