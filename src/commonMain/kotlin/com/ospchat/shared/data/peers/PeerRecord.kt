package com.ospchat.shared.data.peers

import com.ospchat.shared.data.discovery.Peer

/**
 * UI-facing peer record: persisted identity (UUID + last-known address)
 * joined with the live NSD state and an unread-message count.
 */
data class PeerRecord(
    val uuid: String,
    val nickname: String,
    val host: String,
    val port: Int,
    val isOnline: Boolean,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val unreadCount: Int,
    val avatarLocalPath: String?,
    val isContact: Boolean,
    /**
     * Phase 4 multi-network bridging — when this peer is currently
     * reachable only via a bridge (not in direct discovery), this is
     * the bridge peer's nickname for display. `null` for direct peers
     * and for unreachable peers.
     */
    val bridgeNickname: String? = null,
) {
    fun toPeer(): Peer = Peer(uuid = uuid, nickname = nickname, host = host, port = port)

    /**
     * Phase 4 multi-network bridging — address string for the contacts /
     * peer-info UI:
     *  - `"via <bridge-nickname>"` when reachable via a known bridge.
     *  - `"via bridge"` when reachable via a bridge whose nickname we
     *    couldn't resolve (rare; fallback).
     *  - `"host:port"` for direct peers.
     */
    fun displayAddress(): String =
        when {
            bridgeNickname != null -> "via $bridgeNickname"
            host.isEmpty() -> "via bridge"
            else -> "$host:$port"
        }
}
