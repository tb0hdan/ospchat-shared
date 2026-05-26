package com.ospchat.shared.data.peers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 4 multi-network bridging — in-memory record of directly-discovered
 * peers that advertised `relayEnabled = true` in their `GET /v1/info`
 * response. Populated by [PeerAvatarSync] each time it fetches `/v1/info`;
 * read by `PeerRouter` to pick a bridge for cross-LAN sends.
 *
 * In-memory only by design: every session re-fetches `/v1/info` from
 * directly-discovered peers anyway, so persisting the relay flag would
 * just risk acting on stale data after a bridge revoked its opt-in.
 *
 * Thread-safe via the underlying [MutableStateFlow]; reads are sync,
 * writes happen in the snapshot-collection coroutine.
 */
class RelayBridgeRegistry {
    private val _bridges = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Live snapshot of UUIDs whose most-recent `/v1/info` response said
     * `relayEnabled = true`. Consumers can `collectAsState` to react in
     * the UI (e.g. show a relay-available badge on a peer row).
     */
    val bridges: StateFlow<Set<String>> = _bridges.asStateFlow()

    /** Sync membership test for the routing fast path. */
    fun isBridge(uuid: String): Boolean = _bridges.value.contains(uuid)

    /** Record an `/v1/info.relayEnabled` flag from [bridgeUuid]. */
    fun applyAdvertisement(
        bridgeUuid: String,
        relayEnabled: Boolean,
    ) {
        _bridges.update { current ->
            if (relayEnabled) {
                if (bridgeUuid in current) current else current + bridgeUuid
            } else {
                if (bridgeUuid in current) current - bridgeUuid else current
            }
        }
    }

    /**
     * Drop [bridgeUuid] entirely (e.g. when the peer leaves the discovery
     * snapshot). Distinct from `applyAdvertisement(_, false)` only for
     * caller clarity; both end in the same state.
     */
    fun forget(bridgeUuid: String) {
        _bridges.update { it - bridgeUuid }
    }
}
