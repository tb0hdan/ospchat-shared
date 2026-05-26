package com.ospchat.shared.data.peers

import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Peer

/**
 * Phase 4 multi-network bridging — resolves a target UUID to the next hop
 * the consumer should POST to.
 *
 * Three outcomes:
 *  - **Direct**: the target is in the live discovery snapshot. The
 *    caller POSTs straight to them with `toUuid = null` — same as
 *    pre-phase-4 behaviour.
 *  - **Bridged**: the target is only known via [GossipedPeerStore] (a
 *    bridge vouched for them in its `/v1/info.peers`). The caller picks
 *    a relay-enabled bridge from the gossiped peer's `bridges` set,
 *    sets `toUuid = <target>` on the body, and POSTs to the bridge.
 *  - **Unreachable**: target is neither directly discovered nor
 *    vouched-for by any relay-enabled bridge. The caller surfaces a
 *    "not reachable" error to the user.
 *
 * The router is intentionally side-effect-free and synchronous — it
 * just reads from the three flows that back its inputs. Caching /
 * latency-aware bridge selection / sticky-route preferences are
 * deliberately out of scope for the phase 4 MVP.
 */
class PeerRouter(
    private val discoveryRepository: DiscoveryRepository,
    private val gossipedPeerStore: GossipedPeerStore,
    private val relayBridgeRegistry: RelayBridgeRegistry,
) {
    /**
     * Returns the route to [targetUuid], or `null` if no route is
     * currently known. The caller should treat `null` as "ask the user
     * to wait for discovery / gossip to populate" rather than as a hard
     * error — discovery snapshots and gossip caches refresh
     * continuously.
     */
    fun routeTo(targetUuid: String): RouteHop? {
        discoveryRepository.findPeer(targetUuid)?.let { direct ->
            return RouteHop(nextHop = direct, toUuid = null)
        }
        val gossiped = gossipedPeerStore.find(targetUuid) ?: return null
        for (bridgeUuid in gossiped.bridges) {
            if (!relayBridgeRegistry.isBridge(bridgeUuid)) continue
            val bridge = discoveryRepository.findPeer(bridgeUuid) ?: continue
            return RouteHop(nextHop = bridge, toUuid = targetUuid)
        }
        return null
    }
}

/**
 * Phase 4 multi-network bridging — one hop on a routed send.
 *
 * [nextHop] is the [Peer] the caller actually POSTs to (its
 * `candidates` give the HTTP endpoints to try). [toUuid] is what the
 * caller should set on the outgoing DTO's `toUuid` field:
 *  - `null` → direct send; receiver consumes locally.
 *  - non-null → relay request; receiver forwards via [nextHop]'s relay
 *    branch (subject to its `relayEnabled` opt-in).
 */
data class RouteHop(
    val nextHop: Peer,
    val toUuid: String?,
)
