package com.ospchat.shared.data.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct coverage for the [protectedInsert] helper used by both platform
 * discovery services. Covers the D3 snapshot-size cap, phase 1
 * multi-network bridging (candidate merge + preference sort), and the
 * per-peer candidate cap. F9 hijack rejection is deliberately relaxed
 * in phase 1; signed-advertisement F9 restoration belongs in phase 2 and
 * gets its own tests then.
 */
class PeerCapTest {
    private fun newFlow(initial: Map<String, Peer> = emptyMap()) = MutableStateFlow(initial)

    private fun peer(
        uuid: String,
        host: String,
        port: Int = 8080,
    ): Peer = Peer(uuid = uuid, nickname = "n-$uuid", host = host, port = port)

    private fun ep(
        host: String,
        port: Int = 8080,
    ) = Endpoint(host = host, port = port)

    @Test
    fun acceptsInsertBelowCap() {
        val flow = newFlow()
        val r = flow.protectedInsert("a", "n-a", ep("10.0.0.1"), max = 3)
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals(1, flow.value.size)
    }

    @Test
    fun acceptsUpdateOfSameUuidAndHost() {
        val flow = newFlow(mapOf("a" to peer("a", "10.0.0.1", port = 8080)))
        // Same host, new port (typical restart) — appended as a new candidate.
        val r = flow.protectedInsert("a", "n-a", ep("10.0.0.1", port = 9090), max = 3)
        assertEquals(PeerInsertResult.ACCEPTED, r)
        val candidates = flow.value["a"]?.candidates ?: error("peer missing")
        assertTrue(candidates.contains(ep("10.0.0.1", 9090)))
    }

    @Test
    fun rejectsNewUuidAtCap() {
        val flow =
            newFlow(
                mapOf(
                    "a" to peer("a", "10.0.0.1"),
                    "b" to peer("b", "10.0.0.2"),
                    "c" to peer("c", "10.0.0.3"),
                ),
            )
        val r = flow.protectedInsert("d", "n-d", ep("10.0.0.4"), max = 3)
        assertEquals(PeerInsertResult.DROPPED_AT_CAP, r)
        assertEquals(3, flow.value.size)
    }

    @Test
    fun phase1MergesSecondCandidateFromDifferentHost() {
        // Phase 1 relaxation: same uuid at a different host is accepted as an
        // additional candidate (was DROPPED_HIJACK pre-phase-1). The peer's
        // primary endpoint is determined by preference sort, not insertion
        // order, so both endpoints are reachable.
        val flow = newFlow(mapOf("alice" to peer("alice", "10.0.0.1")))
        val r = flow.protectedInsert("alice", "n-alice", ep("100.64.1.5"), max = 256)
        assertEquals(PeerInsertResult.ACCEPTED, r)
        val candidates = flow.value["alice"]?.candidates ?: error("peer missing")
        assertEquals(2, candidates.size)
        // RFC1918 (10.0.0.1, tier 0) sorts before CGNAT (100.64.1.5, tier 1).
        assertEquals("10.0.0.1", candidates[0].host)
        assertEquals("100.64.1.5", candidates[1].host)
    }

    @Test
    fun preferenceSortPromotesLanOverVpn() {
        // Resolution order: Tailscale-first, LAN-second. Preference sort
        // still puts LAN as the primary so MessageClient tries the
        // RFC1918 address first.
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("100.64.1.5"))
        flow.protectedInsert("alice", "n-alice", ep("192.168.1.5"))
        val candidates = flow.value["alice"]?.candidates ?: error("peer missing")
        assertEquals("192.168.1.5", candidates[0].host)
        assertEquals("100.64.1.5", candidates[1].host)
    }

    @Test
    fun stableOrderWithinSameTier() {
        // Two RFC1918 addresses: insertion order wins within the tier.
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("192.168.1.5"))
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.5"))
        val candidates = flow.value["alice"]?.candidates ?: error("peer missing")
        assertEquals("192.168.1.5", candidates[0].host)
        assertEquals("10.0.0.5", candidates[1].host)
    }

    @Test
    fun candidateCapEnforced() {
        val flow = newFlow()
        // Fill up to MAX_CANDIDATES_PER_PEER distinct endpoints.
        for (i in 0 until MAX_CANDIDATES_PER_PEER) {
            val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.$i"))
            assertEquals(PeerInsertResult.ACCEPTED, r)
        }
        // One more endpoint -> DROPPED_CANDIDATE_CAP.
        val overflow = flow.protectedInsert("alice", "n-alice", ep("10.0.0.99"))
        assertEquals(PeerInsertResult.DROPPED_CANDIDATE_CAP, overflow)
        assertEquals(MAX_CANDIDATES_PER_PEER, flow.value["alice"]?.candidates?.size)
    }

    @Test
    fun duplicateCandidateIsIdempotent() {
        val flow = newFlow(mapOf("a" to peer("a", "10.0.0.1")))
        val first = flow.protectedInsert("a", "n-a", ep("10.0.0.1"))
        val second = flow.protectedInsert("a", "n-a", ep("10.0.0.1"))
        assertEquals(PeerInsertResult.ACCEPTED, first)
        assertEquals(PeerInsertResult.ACCEPTED, second)
        assertEquals(1, flow.value["a"]?.candidates?.size)
    }

    @Test
    fun nicknameRefreshOnExistingCandidate() {
        val flow = newFlow(mapOf("a" to peer("a", "10.0.0.1").copy(nickname = "old")))
        val r = flow.protectedInsert("a", "new", ep("10.0.0.1"))
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals("new", flow.value["a"]?.nickname)
    }

    @Test
    fun afterForgetPeerNewHostIsAccepted() {
        // Models the legitimate IP-change flow: serviceRemoved (or
        // forgetPeer) empties the entry first, then a new resolve at a
        // new host arrives. With the entry gone, the new host is accepted
        // as a fresh peer.
        val flow = newFlow(mapOf("alice" to peer("alice", "10.0.0.1")))
        flow.value = flow.value - "alice"
        val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.2"))
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals("10.0.0.2", flow.value["alice"]?.host)
    }

    // --- Phase 2a pubkey pinning matrix --------------------------------

    @Test
    fun firstSeenPubkeyIsPinned() {
        val flow = newFlow()
        val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = "PK_A")
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals("PK_A", flow.value["alice"]?.publicKey)
    }

    @Test
    fun samePubkeyMergesCandidate() {
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = "PK_A")
        val r = flow.protectedInsert("alice", "n-alice", ep("100.64.1.5"), publicKey = "PK_A")
        assertEquals(PeerInsertResult.ACCEPTED, r)
        val peer = flow.value["alice"] ?: error("peer missing")
        assertEquals("PK_A", peer.publicKey)
        assertEquals(2, peer.candidates.size)
    }

    @Test
    fun differentPubkeyRejectedAsHijack() {
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = "PK_A")
        val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.99"), publicKey = "PK_B")
        assertEquals(PeerInsertResult.DROPPED_PKH_MISMATCH, r)
        // Pinned key + candidate set unchanged.
        val peer = flow.value["alice"] ?: error("peer missing")
        assertEquals("PK_A", peer.publicKey)
        assertEquals(1, peer.candidates.size)
        assertEquals("10.0.0.1", peer.candidates[0].host)
    }

    @Test
    fun nullPubkeyAfterPinIsRejectedAsDowngrade() {
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = "PK_A")
        // Same UUID but the new resolution silently dropped pk= — attacker
        // trying to coexist as an alternate candidate without their own key.
        val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.99"), publicKey = null)
        assertEquals(PeerInsertResult.DROPPED_PKH_MISMATCH, r)
        assertEquals("PK_A", flow.value["alice"]?.publicKey)
        assertEquals(1, flow.value["alice"]?.candidates?.size)
    }

    @Test
    fun nullPubkeyOnUnpinnedExistingIsLegacyCompat() {
        // Both pre-phase-2a: existing entry has null pubkey, new has null.
        // Should still merge as plain phase-1 multi-NIC.
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = null)
        val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.2"), publicKey = null)
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals(null, flow.value["alice"]?.publicKey)
        assertEquals(2, flow.value["alice"]?.candidates?.size)
    }

    @Test
    fun nullExistingUpgradesOnFirstPubkey() {
        // Peer was first seen pre-phase-2a (no pk=) then re-resolved with a
        // pk= TXT (peer was upgraded). The pubkey is now pinned.
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = null)
        val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = "PK_A")
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals("PK_A", flow.value["alice"]?.publicKey)
    }

    @Test
    fun pubkeyUpgradeThenMismatchIsRejected() {
        // After upgrade, the now-pinned pubkey must still reject mismatches.
        val flow = newFlow()
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = null)
        flow.protectedInsert("alice", "n-alice", ep("10.0.0.1"), publicKey = "PK_A")
        val r = flow.protectedInsert("alice", "n-alice", ep("10.0.0.2"), publicKey = "PK_B")
        assertEquals(PeerInsertResult.DROPPED_PKH_MISMATCH, r)
    }

    @Test
    fun duplicateCandidateRefreshesNickname() {
        val flow = newFlow()
        flow.protectedInsert("alice", "old", ep("10.0.0.1"), publicKey = "PK_A")
        flow.protectedInsert("alice", "new", ep("10.0.0.1"), publicKey = "PK_A")
        assertEquals("new", flow.value["alice"]?.nickname)
        assertEquals("PK_A", flow.value["alice"]?.publicKey)
    }

    // --- Phase 2b persistent pubkey pin --------------------------------

    @Test
    fun persistentPinRejectsMismatchEvenWithoutLiveEntry() {
        // Discovery just started: no in-memory peers yet, but the persistent
        // map carries pins loaded from Room. An attacker who wins the
        // post-restart mDNS race with a different pubkey must be rejected.
        val flow = newFlow()
        val result =
            flow.protectedInsert(
                uuid = "alice",
                nickname = "alice",
                candidate = ep("192.168.1.99"),
                publicKey = "PK_ATTACKER",
                pinnedPubkey = "PK_ALICE_FROM_DB",
            )
        assertEquals(PeerInsertResult.DROPPED_PKH_MISMATCH, result)
        assertEquals(0, flow.value.size, "rejected insert must not enter the snapshot")
    }

    @Test
    fun persistentPinAcceptsMatchingFirstSight() {
        val flow = newFlow()
        val result =
            flow.protectedInsert(
                uuid = "alice",
                nickname = "alice",
                candidate = ep("192.168.1.5"),
                publicKey = "PK_ALICE",
                pinnedPubkey = "PK_ALICE",
            )
        assertEquals(PeerInsertResult.ACCEPTED, result)
        val peer = flow.value["alice"] ?: error("peer missing")
        assertEquals("PK_ALICE", peer.publicKey)
    }

    @Test
    fun persistentPinAppliedWhenLiveResolutionDropsPubkey() {
        // Pre-2b peer that we'd previously seen WITH a pubkey — DB has the
        // pin, but the current resolution arrives without one (mDNS TXT
        // missing pk=). Must reject as a downgrade attempt.
        val flow = newFlow()
        val result =
            flow.protectedInsert(
                uuid = "alice",
                nickname = "alice",
                candidate = ep("192.168.1.5"),
                publicKey = null,
                pinnedPubkey = "PK_ALICE_FROM_DB",
            )
        assertEquals(PeerInsertResult.DROPPED_PKH_MISMATCH, result)
    }

    @Test
    fun inMemoryPinTakesPrecedenceOverPersistentPin() {
        // If an in-memory peer entry exists, its publicKey wins over the
        // persistent map (the in-memory pin is at least as fresh as the DB).
        val flow = newFlow()
        flow.protectedInsert("alice", "alice", ep("192.168.1.5"), publicKey = "PK_LIVE")
        val secondResolveSameKey =
            flow.protectedInsert(
                uuid = "alice",
                nickname = "alice",
                candidate = ep("100.64.1.5"),
                publicKey = "PK_LIVE",
                pinnedPubkey = "PK_STALE_FROM_DB",
            )
        assertEquals(PeerInsertResult.ACCEPTED, secondResolveSameKey)
        assertEquals("PK_LIVE", flow.value["alice"]?.publicKey)
    }

    // --- Endpoint tier classification ----------------------------------

    @Test
    fun endpointTierClassification() {
        // RFC 1918
        assertEquals(0, endpointTier("10.0.0.1"))
        assertEquals(0, endpointTier("172.16.0.1"))
        assertEquals(0, endpointTier("172.31.255.255"))
        assertEquals(0, endpointTier("192.168.1.1"))
        // Outside RFC 1918 172/16 sub-range
        assertEquals(2, endpointTier("172.15.0.1"))
        assertEquals(2, endpointTier("172.32.0.1"))
        // RFC 6598 CGNAT
        assertEquals(1, endpointTier("100.64.0.1"))
        assertEquals(1, endpointTier("100.127.255.255"))
        // Outside CGNAT range
        assertEquals(2, endpointTier("100.128.0.1"))
        assertEquals(2, endpointTier("100.63.255.255"))
        // Public
        assertEquals(2, endpointTier("8.8.8.8"))
        // Non-IPv4 / malformed → tier 2 (public bucket)
        assertEquals(2, endpointTier("fe80::1%eth0"))
        assertEquals(2, endpointTier("peer.local"))
        assertEquals(2, endpointTier("garbage"))
        assertEquals(2, endpointTier("999.999.999.999"))
    }
}
