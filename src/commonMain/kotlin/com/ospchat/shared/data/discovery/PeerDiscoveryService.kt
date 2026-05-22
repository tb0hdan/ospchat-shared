package com.ospchat.shared.data.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

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

/**
 * Hard cap on the size of [PeerDiscoveryService.peers]. Existing entries
 * can always be refreshed (a peer's port/nickname update); new peers are
 * silently dropped once the snapshot is full. A LAN with this many
 * legitimate OSPChat peers is unrealistic — the cap exists to bound the
 * map under an mDNS-flood attack. See docs/SECURITY.md D3.
 */
const val MAX_PEERS: Int = 256

/** Outcome of a [protectedInsert] call. */
enum class PeerInsertResult {
    /** Insert / update was applied to the snapshot. */
    ACCEPTED,

    /** Dropped because the snapshot was already at [MAX_PEERS]. D3. */
    DROPPED_AT_CAP,

    /**
     * Dropped because an entry for the same uuid already exists at a
     * different host — likely an attacker on the LAN advertising the same
     * uuid TXT from a different IP to hijack the victim's identity. The
     * legitimate IP-change path goes through `serviceRemoved` / `forgetPeer`
     * first, which empties the entry before the new resolve lands. F9.
     */
    DROPPED_HIJACK,
}

/**
 * Atomically insert [uuid]→[peer] into the snapshot with the combined
 * D3 cap and F9 identity-hijack defence. Updates of an existing uuid at
 * the SAME host (typical: port or nickname changed across restart) are
 * always allowed. See docs/SECURITY.md D3 / F9.
 */
fun MutableStateFlow<Map<String, Peer>>.protectedInsert(
    uuid: String,
    peer: Peer,
    max: Int = MAX_PEERS,
): PeerInsertResult {
    var result = PeerInsertResult.ACCEPTED
    update { current ->
        val existing = current[uuid]
        when {
            existing != null && existing.host != peer.host -> {
                result = PeerInsertResult.DROPPED_HIJACK
                current
            }

            existing == null && current.size >= max -> {
                result = PeerInsertResult.DROPPED_AT_CAP
                current
            }

            else -> {
                current + (uuid to peer)
            }
        }
    }
    return result
}
