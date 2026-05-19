package com.ospchat.shared.data.discovery

import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton facade over [PeerDiscoveryService]. The foreground service / app
 * shell drives [start] / [stop]; downstream repositories subscribe to
 * [peerSnapshot]; the HTTP route layer uses [findPeer] for the source-IP
 * trust check.
 */
class DiscoveryRepository(
    private val discovery: PeerDiscoveryService,
) {
    /** Live snapshot of peers currently visible via mDNS, keyed by UUID. */
    val peerSnapshot: StateFlow<Map<String, Peer>> = discovery.peers

    /** Synchronous snapshot lookup for hot paths like inbound HTTP requests. */
    fun findPeer(uuid: String): Peer? = discovery.peers.value[uuid]

    fun start(
        nickname: String,
        uuid: String,
        port: Int,
    ) = discovery.start(nickname, uuid, port)

    fun stop() = discovery.stop()

    /** Drop the cached resolution for [uuid] and trigger a re-resolve. */
    fun forgetPeer(uuid: String) = discovery.forgetPeer(uuid)
}
