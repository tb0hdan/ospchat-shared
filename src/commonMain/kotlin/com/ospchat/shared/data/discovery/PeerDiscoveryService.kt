package com.ospchat.shared.data.discovery

import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic mDNS / DNS-SD peer discovery. Android backs it with
 * [android.net.nsd.NsdManager]; desktop backs it with JmDNS. Both publish the
 * same `_ospchat._tcp.` service with a `uuid=` TXT attribute, so cross-platform
 * peers see each other.
 *
 * Lifecycle: [start] is called once the local server has bound a port and
 * we know our identity; [stop] tears down advertising + discovery.
 *
 * Implementations expose [peers] as a snapshot keyed by per-install UUID. The
 * UUID dedup is essential because a peer's nickname or address can change
 * mid-session while UUID stays stable.
 */
interface PeerDiscoveryService {
    /** Live snapshot of peers currently visible on the LAN, keyed by UUID. */
    val peers: StateFlow<Map<String, Peer>>

    fun start(
        nickname: String,
        uuid: String,
        port: Int,
    )

    fun stop()
}
