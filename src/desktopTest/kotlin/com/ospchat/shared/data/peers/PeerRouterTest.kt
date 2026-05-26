package com.ospchat.shared.data.peers

import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Endpoint
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.discovery.PeerDiscoveryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phase 4 multi-network bridging — coverage for [PeerRouter]'s routing
 * matrix. The invariants worth nailing down:
 *
 *  - Direct discovery wins over gossip (no `toUuid` set on direct sends).
 *  - Gossip resolves only when the vouching bridge is currently
 *    relay-enabled AND directly reachable.
 *  - No route ⇒ null (the caller surfaces "not reachable" to the user).
 */
class PeerRouterTest {
    private class FakeDiscovery : PeerDiscoveryService {
        val flow = MutableStateFlow<Map<String, Peer>>(emptyMap())
        override val peers: StateFlow<Map<String, Peer>> = flow

        override fun start(
            nickname: String,
            uuid: String,
            port: Int,
            publicKeyB64: String?,
        ) {}

        override fun stop() {}

        override fun forgetPeer(uuid: String) {}

        fun put(peer: Peer) {
            flow.value = flow.value + (peer.uuid to peer)
        }
    }

    private fun peer(
        uuid: String,
        host: String = "192.168.1.10",
        port: Int = 8080,
    ) = Peer(uuid = uuid, nickname = "n-$uuid", candidates = listOf(Endpoint(host, port)), publicKey = "PK_$uuid")

    private fun gossipedPeer(
        uuid: String,
        bridge: String,
    ) = GossipedPeer(uuid = uuid, nickname = "n-$uuid", publicKey = "PK_$uuid", bridges = setOf(bridge))

    private fun newSetup(): Triple<FakeDiscovery, GossipedPeerStore, RelayBridgeRegistry> {
        val disc = FakeDiscovery()
        val gossip = GossipedPeerStore()
        val bridges = RelayBridgeRegistry()
        return Triple(disc, gossip, bridges)
    }

    @Test
    fun directDiscoveryReturnsDirectRoute() {
        val (disc, gossip, bridges) = newSetup()
        disc.put(peer("alice"))
        val router = PeerRouter(DiscoveryRepository(disc), gossip, bridges)
        val hop = router.routeTo("alice") ?: error("missing")
        assertEquals("alice", hop.nextHop.uuid)
        assertNull(hop.toUuid, "direct route must not set toUuid")
    }

    @Test
    fun gossipOnlyTargetRoutedViaRelayEnabledBridge() {
        val (disc, gossip, bridges) = newSetup()
        disc.put(peer("desktop"))
        bridges.applyAdvertisement("desktop", relayEnabled = true)
        gossip.applyGossip(
            bridgeUuid = "desktop",
            gossiped = listOf(gossipedPeer("a2", bridge = "desktop")),
            previousFromBridge = emptySet(),
        )
        val router = PeerRouter(DiscoveryRepository(disc), gossip, bridges)
        val hop = router.routeTo("a2") ?: error("missing")
        assertEquals("desktop", hop.nextHop.uuid)
        assertEquals("a2", hop.toUuid)
    }

    @Test
    fun gossipOnlyTargetWithRelayDisabledBridgeIsUnroutable() {
        val (disc, gossip, bridges) = newSetup()
        disc.put(peer("desktop"))
        // Desktop is in discovery but not relay-enabled.
        gossip.applyGossip(
            bridgeUuid = "desktop",
            gossiped = listOf(gossipedPeer("a2", bridge = "desktop")),
            previousFromBridge = emptySet(),
        )
        val router = PeerRouter(DiscoveryRepository(disc), gossip, bridges)
        assertNull(router.routeTo("a2"))
    }

    @Test
    fun gossipOnlyTargetWithBridgeOfflineIsUnroutable() {
        val (disc, gossip, bridges) = newSetup()
        // No desktop in discovery — bridge is offline.
        bridges.applyAdvertisement("desktop", relayEnabled = true)
        gossip.applyGossip(
            bridgeUuid = "desktop",
            gossiped = listOf(gossipedPeer("a2", bridge = "desktop")),
            previousFromBridge = emptySet(),
        )
        val router = PeerRouter(DiscoveryRepository(disc), gossip, bridges)
        assertNull(router.routeTo("a2"))
    }

    @Test
    fun directRoutePreferredEvenWhenGossipAlsoVouches() {
        val (disc, gossip, bridges) = newSetup()
        // Same UUID known both directly and via gossip — should pick direct.
        disc.put(peer("alice"))
        disc.put(peer("desktop"))
        bridges.applyAdvertisement("desktop", relayEnabled = true)
        gossip.applyGossip(
            bridgeUuid = "desktop",
            gossiped = listOf(gossipedPeer("alice", bridge = "desktop")),
            previousFromBridge = emptySet(),
        )
        val router = PeerRouter(DiscoveryRepository(disc), gossip, bridges)
        val hop = router.routeTo("alice") ?: error("missing")
        assertEquals("alice", hop.nextHop.uuid)
        assertNull(hop.toUuid)
    }

    @Test
    fun unknownTargetIsUnroutable() {
        val (disc, gossip, bridges) = newSetup()
        val router = PeerRouter(DiscoveryRepository(disc), gossip, bridges)
        assertNull(router.routeTo("stranger"))
    }

    @Test
    fun multipleBridgesPicksFirstRelayEnabledOne() {
        val (disc, gossip, bridges) = newSetup()
        disc.put(peer("desktop1"))
        disc.put(peer("desktop2"))
        // Only desktop2 is relay-enabled.
        bridges.applyAdvertisement("desktop2", relayEnabled = true)
        gossip.applyGossip(
            bridgeUuid = "desktop1",
            gossiped = listOf(gossipedPeer("a2", bridge = "desktop1")),
            previousFromBridge = emptySet(),
        )
        gossip.applyGossip(
            bridgeUuid = "desktop2",
            gossiped = listOf(gossipedPeer("a2", bridge = "desktop2")),
            previousFromBridge = emptySet(),
        )
        val router = PeerRouter(DiscoveryRepository(disc), gossip, bridges)
        val hop = router.routeTo("a2") ?: error("missing")
        // Should skip desktop1 (relay disabled) and pick desktop2.
        assertEquals("desktop2", hop.nextHop.uuid)
    }
}
