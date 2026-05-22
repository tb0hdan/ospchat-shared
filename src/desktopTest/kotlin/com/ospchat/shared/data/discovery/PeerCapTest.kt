package com.ospchat.shared.data.discovery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Direct coverage for the [protectedInsert] helper used by both platform
 * discovery services. Combines the D3 cap (snapshot size) with the F9
 * identity-hijack defence (same uuid from a different host).
 */
class PeerCapTest {
    private fun newFlow(initial: Map<String, Peer> = emptyMap()) = MutableStateFlow(initial)

    private fun peer(
        uuid: String,
        host: String,
        port: Int = 8080,
    ): Peer = Peer(uuid = uuid, nickname = "n-$uuid", host = host, port = port)

    @Test
    fun acceptsInsertBelowCap() {
        val flow = newFlow()
        val r = flow.protectedInsert("a", peer("a", "10.0.0.1"), max = 3)
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals(1, flow.value.size)
    }

    @Test
    fun acceptsUpdateOfSameUuidAndHost() {
        val flow = newFlow(mapOf("a" to peer("a", "10.0.0.1", port = 8080)))
        // Same host, new port (typical restart) — must be allowed.
        val r = flow.protectedInsert("a", peer("a", "10.0.0.1", port = 9090), max = 3)
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals(9090, flow.value["a"]?.port)
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
        val r = flow.protectedInsert("d", peer("d", "10.0.0.4"), max = 3)
        assertEquals(PeerInsertResult.DROPPED_AT_CAP, r)
        assertEquals(3, flow.value.size)
    }

    @Test
    fun rejectsIdentityHijackFromDifferentHost() {
        // F9: same uuid is already advertised from 10.0.0.1; a new resolve
        // with the same uuid from 10.0.0.99 must NOT overwrite the entry.
        val flow = newFlow(mapOf("victim" to peer("victim", "10.0.0.1")))
        val r = flow.protectedInsert("victim", peer("victim", "10.0.0.99"), max = 256)
        assertEquals(PeerInsertResult.DROPPED_HIJACK, r)
        assertEquals("10.0.0.1", flow.value["victim"]?.host)
    }

    @Test
    fun afterForgetPeerNewHostIsAccepted() {
        // Models the legitimate IP-change flow: serviceRemoved (or
        // forgetPeer) empties the entry first, then a new resolve at a
        // new host arrives. With the entry gone, the new host is accepted.
        val flow = newFlow(mapOf("alice" to peer("alice", "10.0.0.1")))
        flow.value = flow.value - "alice"
        val r = flow.protectedInsert("alice", peer("alice", "10.0.0.2"), max = 256)
        assertEquals(PeerInsertResult.ACCEPTED, r)
        assertEquals("10.0.0.2", flow.value["alice"]?.host)
    }
}
