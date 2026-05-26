package com.ospchat.shared.data.peers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Phase 4 multi-network bridging — a peer learned about indirectly via
 * `GET /v1/info.peers` gossip from a bridge node. We don't have a route
 * to them ourselves (no mDNS resolution), but a bridge claims to see them
 * and has vouched for their public key (verified end-to-end via signed
 * DTOs). The consumer routes outbound to such a peer by sending to a
 * bridge with `toUuid=<this uuid>`.
 */
data class GossipedPeer(
    /** The gossiped peer's stable UUID. */
    val uuid: String,
    /** The gossiped peer's most-recently-seen nickname across all gossip sources. */
    val nickname: String,
    /**
     * Base64-encoded Ed25519 public key. TOFU-pinned: the first non-null
     * value we see for [uuid] is locked in; subsequent gossip with a
     * mismatching key is rejected (the source bridge is either lying or
     * compromised).
     */
    val publicKey: String,
    /**
     * Bridges that vouched for this peer (i.e. returned them in their
     * `/v1/info.peers`). The consumer picks one to forward through;
     * favouring lower-latency or relay-enabled bridges is left to the
     * routing layer. UUIDs only — the consumer looks up live host/port
     * via their own discovery snapshot.
     */
    val bridges: Set<String>,
)

/**
 * Phase 4 multi-network bridging — in-memory cache of peers learned via
 * bridge gossip. Survives within a session; not persisted to Room in the
 * phase 4 MVP (the gossip will be re-pulled on next `/v1/info` fetch).
 *
 * Thread-safe via the underlying [MutableStateFlow]; reads are sync, writes
 * happen in the snapshot-collection coroutine that owns the
 * [PeerAvatarSync] flow.
 */
class GossipedPeerStore {
    private val _peers = MutableStateFlow<Map<String, GossipedPeer>>(emptyMap())
    val peers: StateFlow<Map<String, GossipedPeer>> = _peers.asStateFlow()

    /** Sync lookup; null if the peer isn't in our gossip cache. */
    fun find(uuid: String): GossipedPeer? = peers.value[uuid]

    /**
     * Apply a freshly-received gossip list from [bridgeUuid]. For each
     * gossiped entry:
     *  - Brand-new UUID → record with [bridgeUuid] as its sole bridge.
     *  - Existing UUID, matching pubkey → add [bridgeUuid] to bridges
     *    (set semantics, no duplicates).
     *  - Existing UUID, **different** pubkey → reject. We trust the first
     *    pubkey we saw; the second bridge is either lying or compromised.
     *    The caller's logger should surface this so dev can investigate.
     *
     * UUIDs that appear in [previousFromBridge] but NOT in the new list
     * have their bridges set pruned of [bridgeUuid]; if the resulting set
     * is empty, the gossiped peer is removed entirely.
     */
    fun applyGossip(
        bridgeUuid: String,
        gossiped: List<GossipedPeer>,
        previousFromBridge: Set<String>,
    ): GossipApplyResult {
        val rejectedHijacks = mutableListOf<String>()
        val gossipedUuids = gossiped.map { it.uuid }.toSet()
        _peers.update { current ->
            val mutated = current.toMutableMap()
            // Add / update incoming entries.
            for (g in gossiped) {
                val existing = mutated[g.uuid]
                if (existing == null) {
                    mutated[g.uuid] = g.copy(bridges = setOf(bridgeUuid))
                } else if (existing.publicKey != g.publicKey) {
                    rejectedHijacks.add(g.uuid)
                } else {
                    mutated[g.uuid] =
                        existing.copy(
                            nickname = g.nickname,
                            bridges = existing.bridges + bridgeUuid,
                        )
                }
            }
            // Prune entries that this bridge previously vouched for but
            // no longer does. If we had been relying on this bridge as
            // the sole route and another bridge also vouches, that other
            // bridge keeps us in the cache; if no one's vouching anymore,
            // drop the entry.
            for (staleUuid in previousFromBridge - gossipedUuids) {
                val existing = mutated[staleUuid] ?: continue
                val remainingBridges = existing.bridges - bridgeUuid
                if (remainingBridges.isEmpty()) {
                    mutated.remove(staleUuid)
                } else {
                    mutated[staleUuid] = existing.copy(bridges = remainingBridges)
                }
            }
            mutated
        }
        return GossipApplyResult(
            rejectedHijacks = rejectedHijacks,
            currentFromBridge = gossipedUuids,
        )
    }
}

/**
 * Outcome of one [GossipedPeerStore.applyGossip] call.
 *
 * [rejectedHijacks] lists UUIDs whose gossiped pubkey conflicted with the
 * locally-pinned one. These are not added; the caller should log at WARN
 * so dev visibility of the F9-class attack pattern is preserved.
 *
 * [currentFromBridge] is the UUID set the bridge just vouched for, in a
 * form the caller can pass back as [GossipedPeerStore.applyGossip.previousFromBridge]
 * on the next gossip fetch from the same bridge.
 */
data class GossipApplyResult(
    val rejectedHijacks: List<String>,
    val currentFromBridge: Set<String>,
)
