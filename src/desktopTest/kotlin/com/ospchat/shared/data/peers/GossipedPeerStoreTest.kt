package com.ospchat.shared.data.peers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4 multi-network bridging — coverage for the in-memory gossip
 * cache. The interesting invariants:
 *
 *  - First-sight pubkey is TOFU-pinned.
 *  - Conflicting pubkey from a different bridge is rejected, original
 *    keeps its pin.
 *  - Multiple bridges vouching for the same UUID merge bridge sets.
 *  - Pruning: a UUID that disappears from a bridge's vouching list has
 *    that bridge removed from its `bridges`; the entry is dropped only
 *    if no bridge is left.
 */
class GossipedPeerStoreTest {
    private fun gossiped(
        uuid: String,
        pubkey: String,
        nickname: String = "n-$uuid",
        bridge: String = "bridge-1",
    ) = GossipedPeer(uuid = uuid, nickname = nickname, publicKey = pubkey, bridges = setOf(bridge))

    @Test
    fun firstGossipPinsPubkey() {
        val store = GossipedPeerStore()
        val result =
            store.applyGossip(
                bridgeUuid = "bridge-1",
                gossiped = listOf(gossiped("alice", "PK_A")),
                previousFromBridge = emptySet(),
            )
        assertTrue(result.rejectedHijacks.isEmpty())
        val pinned = store.find("alice") ?: error("missing")
        assertEquals("PK_A", pinned.publicKey)
        assertEquals(setOf("bridge-1"), pinned.bridges)
    }

    @Test
    fun secondBridgeWithMatchingPubkeyMergesBridges() {
        val store = GossipedPeerStore()
        store.applyGossip("bridge-1", listOf(gossiped("alice", "PK_A")), emptySet())
        val result =
            store.applyGossip(
                bridgeUuid = "bridge-2",
                gossiped = listOf(gossiped("alice", "PK_A", bridge = "bridge-2")),
                previousFromBridge = emptySet(),
            )
        assertTrue(result.rejectedHijacks.isEmpty())
        val merged = store.find("alice") ?: error("missing")
        assertEquals(setOf("bridge-1", "bridge-2"), merged.bridges)
    }

    @Test
    fun secondBridgeWithMismatchedPubkeyIsRejected() {
        val store = GossipedPeerStore()
        store.applyGossip("bridge-1", listOf(gossiped("alice", "PK_A")), emptySet())
        val result =
            store.applyGossip(
                bridgeUuid = "bridge-2",
                gossiped = listOf(gossiped("alice", "PK_ATTACKER", bridge = "bridge-2")),
                previousFromBridge = emptySet(),
            )
        assertEquals(listOf("alice"), result.rejectedHijacks)
        // Original pin unchanged.
        val pinned = store.find("alice") ?: error("missing")
        assertEquals("PK_A", pinned.publicKey)
        assertEquals(setOf("bridge-1"), pinned.bridges)
    }

    @Test
    fun bridgeStopsVouchingPrunesItFromBridges() {
        val store = GossipedPeerStore()
        // bridge-1 and bridge-2 both vouch.
        store.applyGossip("bridge-1", listOf(gossiped("alice", "PK_A")), emptySet())
        store.applyGossip(
            "bridge-2",
            listOf(gossiped("alice", "PK_A", bridge = "bridge-2")),
            emptySet(),
        )
        assertEquals(setOf("bridge-1", "bridge-2"), store.find("alice")?.bridges)
        // bridge-1 stops vouching (empty new gossip list, previous had alice).
        store.applyGossip(
            bridgeUuid = "bridge-1",
            gossiped = emptyList(),
            previousFromBridge = setOf("alice"),
        )
        // bridge-2 still vouches.
        assertEquals(setOf("bridge-2"), store.find("alice")?.bridges)
    }

    @Test
    fun bridgeStopsVouchingAndNoOneElseDropsEntry() {
        val store = GossipedPeerStore()
        store.applyGossip("bridge-1", listOf(gossiped("alice", "PK_A")), emptySet())
        // Same bridge re-gossips but no longer includes alice.
        store.applyGossip(
            bridgeUuid = "bridge-1",
            gossiped = emptyList(),
            previousFromBridge = setOf("alice"),
        )
        assertNull(store.find("alice"))
    }

    @Test
    fun applyGossipReturnsCurrentSetForNextPruneInput() {
        val store = GossipedPeerStore()
        val result =
            store.applyGossip(
                bridgeUuid = "bridge-1",
                gossiped =
                    listOf(
                        gossiped("alice", "PK_A"),
                        gossiped("bob", "PK_B"),
                    ),
                previousFromBridge = emptySet(),
            )
        assertEquals(setOf("alice", "bob"), result.currentFromBridge)
    }

    @Test
    fun nicknameRefreshOnSamePubkey() {
        val store = GossipedPeerStore()
        store.applyGossip(
            "bridge-1",
            listOf(gossiped("alice", "PK_A", nickname = "old")),
            emptySet(),
        )
        store.applyGossip(
            "bridge-1",
            listOf(gossiped("alice", "PK_A", nickname = "new")),
            previousFromBridge = setOf("alice"),
        )
        assertEquals("new", store.find("alice")?.nickname)
    }

    @Test
    fun multipleEntriesInOneGossipRoundtrip() {
        val store = GossipedPeerStore()
        store.applyGossip(
            "bridge-1",
            listOf(
                gossiped("alice", "PK_A"),
                gossiped("bob", "PK_B"),
                gossiped("carol", "PK_C"),
            ),
            emptySet(),
        )
        assertEquals(3, store.peers.value.size)
        assertEquals("PK_A", store.find("alice")?.publicKey)
        assertEquals("PK_B", store.find("bob")?.publicKey)
        assertEquals("PK_C", store.find("carol")?.publicKey)
    }
}
