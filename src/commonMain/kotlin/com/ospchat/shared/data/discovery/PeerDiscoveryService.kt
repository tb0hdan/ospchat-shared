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

    /**
     * Drop the cached resolution for [uuid] and ask the framework to
     * re-resolve. Used by the send pipeline when a POST to the cached
     * address fails — the peer may have restarted on a fresh port, and
     * the platform NSD layers (Android NSD's framework cache especially)
     * don't fire onServiceFound for a port-only change.
     *
     * Implementations should remove the peer from [peers] eagerly so the
     * UI reflects the offline-until-rediscovered state, then trigger a
     * fresh resolve. A no-op is acceptable if the implementation has no
     * way to force re-resolution.
     */
    fun forgetPeer(uuid: String)
}
