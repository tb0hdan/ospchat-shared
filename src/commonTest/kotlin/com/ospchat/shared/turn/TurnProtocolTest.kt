package com.ospchat.shared.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TurnProtocol]'s pure handlers. The platform server is
 * stubbed out — the tests assert on the [TurnAction] stream the protocol
 * layer emits in response to canned STUN requests.
 */
class TurnProtocolTest {
    private val secret = "test-secret".encodeToByteArray()
    private val realm = "ospchat-test"
    private val serverAddr =
        TransportAddress(
            family = StunAddressFamily.IPV4,
            address = byteArrayOf(10, 0, 0, 1),
            port = 3478,
        )
    private val client =
        TransportAddress(
            family = StunAddressFamily.IPV4,
            address = byteArrayOf(192.toByte(), 168.toByte(), 1, 100),
            port = 50000,
        )
    private val peerAddr =
        TransportAddress(
            family = StunAddressFamily.IPV4,
            address = byteArrayOf(10, 0, 0, 50),
            port = 30000,
        )

    private var now: Long = 1_700_000_000_000L
    private var lastNonce: String = "deadbeefdeadbeefdeadbeefdeadbeef"

    private fun ctx(store: AllocationStore = InMemoryAllocationStore()): TurnContext =
        TurnContext(
            store = store,
            secret = secret,
            realm = realm,
            serverAddress = serverAddr,
            nonceFactory = { lastNonce },
            nonceValidator = { it == lastNonce },
            nowMs = { now },
        )

    @Test
    fun bindingRequestReturnsMappedAddress() {
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST),
                transactionId = ByteArray(12) { it.toByte() },
                attributes = emptyList(),
            )
        val raw = StunCodec.encode(msg)
        val actions = TurnProtocol.handle(client, raw, msg, ctx())
        assertEquals(1, actions.size)
        val send = actions[0] as TurnAction.SendStun
        assertEquals(client, send.to)
        assertEquals(client, send.message.findAttribute<StunAttribute.XorMappedAddress>()?.address)
    }

    @Test
    fun unauthenticatedAllocateGets401() {
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.REQUEST),
                transactionId = ByteArray(12) { (it + 5).toByte() },
                attributes = listOf(StunAttribute.RequestedTransport(TURN_TRANSPORT_UDP)),
            )
        val raw = StunCodec.encode(msg)
        val actions = TurnProtocol.handle(client, raw, msg, ctx())
        val send = actions.single() as TurnAction.SendStun
        val ec = send.message.findAttribute<StunAttribute.ErrorCode>()
        assertEquals(StunError.UNAUTHORIZED, ec?.code)
        // Server MUST include REALM + NONCE so the client can retry
        assertNotNull(send.message.findAttribute<StunAttribute.Realm>())
        assertNotNull(send.message.findAttribute<StunAttribute.Nonce>())
    }

    @Test
    fun authenticatedAllocateRequestsRelayFromPlatform() {
        val now0 = now
        val expirySec = (now0 / 1000L) + 300
        val username = TurnCredentials.buildUsername(expirySec, "alice-uuid")
        val key = TurnCredentials.derivePassword(secret, username)

        val request =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.REQUEST),
                transactionId = ByteArray(12) { (it + 10).toByte() },
                attributes =
                    listOf(
                        StunAttribute.Username(username),
                        StunAttribute.Realm(realm),
                        StunAttribute.Nonce(lastNonce),
                        StunAttribute.RequestedTransport(TURN_TRANSPORT_UDP),
                        StunAttribute.Lifetime(600),
                    ),
            )
        val raw = StunCodec.encodeWithMacAndFingerprint(request, key)
        val decoded = StunCodec.decode(raw)!!
        val actions = TurnProtocol.handle(client, raw, decoded, ctx())
        assertEquals(1, actions.size)
        val allocate = actions[0] as TurnAction.AllocateRelay
        assertEquals(client, allocate.client)
        assertEquals(username, allocate.username)
        assertEquals(600, allocate.lifetimeSec)
    }

    @Test
    fun expiredCredentialsAreRejected() {
        val expirySec = (now / 1000L) - 60 // expired one minute ago
        val username = TurnCredentials.buildUsername(expirySec, "alice")
        val key = TurnCredentials.derivePassword(secret, username)

        val request =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.REQUEST),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.Username(username),
                        StunAttribute.Realm(realm),
                        StunAttribute.Nonce(lastNonce),
                        StunAttribute.RequestedTransport(TURN_TRANSPORT_UDP),
                    ),
            )
        val raw = StunCodec.encodeWithMacAndFingerprint(request, key)
        val decoded = StunCodec.decode(raw)!!
        val actions = TurnProtocol.handle(client, raw, decoded, ctx())
        // Expired credentials → treated as no auth → 401 challenge
        val send = actions.single() as TurnAction.SendStun
        assertEquals(StunError.UNAUTHORIZED, send.message.findAttribute<StunAttribute.ErrorCode>()?.code)
    }

    @Test
    fun createPermissionRequiresExistingAllocation() {
        val expirySec = (now / 1000L) + 300
        val username = TurnCredentials.buildUsername(expirySec, "alice")
        val key = TurnCredentials.derivePassword(secret, username)

        val request =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.CREATE_PERMISSION, StunClass.REQUEST),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.Username(username),
                        StunAttribute.Realm(realm),
                        StunAttribute.Nonce(lastNonce),
                        StunAttribute.XorPeerAddress(peerAddr),
                    ),
            )
        val raw = StunCodec.encodeWithMacAndFingerprint(request, key)
        val decoded = StunCodec.decode(raw)!!
        val actions = TurnProtocol.handle(client, raw, decoded, ctx())
        val send = actions.single() as TurnAction.SendStun
        assertEquals(StunError.ALLOCATION_MISMATCH, send.message.findAttribute<StunAttribute.ErrorCode>()?.code)
    }

    @Test
    fun createPermissionGrantsAccessAfterAllocate() {
        val expirySec = (now / 1000L) + 300
        val username = TurnCredentials.buildUsername(expirySec, "alice")
        val key = TurnCredentials.derivePassword(secret, username)
        val store = InMemoryAllocationStore()
        store.put(
            client,
            TurnAllocation(
                client = client,
                relayed = serverAddr.copy(port = 49152),
                username = username,
                expiresAtMs = now + 600_000,
            ),
        )

        val request =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.CREATE_PERMISSION, StunClass.REQUEST),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.Username(username),
                        StunAttribute.Realm(realm),
                        StunAttribute.Nonce(lastNonce),
                        StunAttribute.XorPeerAddress(peerAddr),
                    ),
            )
        val raw = StunCodec.encodeWithMacAndFingerprint(request, key)
        val decoded = StunCodec.decode(raw)!!
        TurnProtocol.handle(client, raw, decoded, ctx(store))

        val alloc = store.get(client)!!
        assertTrue(alloc.hasPermission(peerAddr, now))
        // A peer at the same IP but different port shares permission (RFC 5766 §8).
        assertTrue(alloc.hasPermission(peerAddr.copy(port = 12345), now))
    }

    @Test
    fun sendIndicationWithoutPermissionDoesNotRelay() {
        val username = TurnCredentials.buildUsername((now / 1000L) + 300, "alice")
        val store = InMemoryAllocationStore()
        store.put(
            client,
            TurnAllocation(
                client = client,
                relayed = serverAddr.copy(port = 49152),
                username = username,
                expiresAtMs = now + 600_000,
            ),
        )
        // No permission was granted; the indication is silently dropped (RFC 5766 §10.2).
        val indication =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.SEND, StunClass.INDICATION),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.XorPeerAddress(peerAddr),
                        StunAttribute.Data(byteArrayOf(1, 2, 3, 4)),
                    ),
            )
        val raw = StunCodec.encode(indication)
        val actions = TurnProtocol.handle(client, raw, indication, ctx(store))
        assertTrue(actions.isEmpty())
    }

    @Test
    fun sendIndicationWithPermissionRelaysPayload() {
        val username = TurnCredentials.buildUsername((now / 1000L) + 300, "alice")
        val store = InMemoryAllocationStore()
        val alloc =
            TurnAllocation(
                client = client,
                relayed = serverAddr.copy(port = 49152),
                username = username,
                expiresAtMs = now + 600_000,
            )
        alloc.grantPermission(peerAddr, now)
        store.put(client, alloc)
        val payload = byteArrayOf(10, 20, 30, 40)
        val indication =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.SEND, StunClass.INDICATION),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.XorPeerAddress(peerAddr),
                        StunAttribute.Data(payload),
                    ),
            )
        val raw = StunCodec.encode(indication)
        val actions = TurnProtocol.handle(client, raw, indication, ctx(store))
        val egress = actions.single() as TurnAction.RelayEgress
        assertEquals(client, egress.client)
        assertEquals(peerAddr, egress.peer)
        assertEquals(payload.toList(), egress.payload.toList())
    }

    @Test
    fun refreshLifetimeZeroReleasesAllocation() {
        val username = TurnCredentials.buildUsername((now / 1000L) + 300, "alice")
        val key = TurnCredentials.derivePassword(secret, username)
        val store = InMemoryAllocationStore()
        store.put(
            client,
            TurnAllocation(
                client = client,
                relayed = serverAddr.copy(port = 49152),
                username = username,
                expiresAtMs = now + 600_000,
            ),
        )
        val request =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.REFRESH, StunClass.REQUEST),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.Username(username),
                        StunAttribute.Realm(realm),
                        StunAttribute.Nonce(lastNonce),
                        StunAttribute.Lifetime(0),
                    ),
            )
        val raw = StunCodec.encodeWithMacAndFingerprint(request, key)
        val decoded = StunCodec.decode(raw)!!
        val actions = TurnProtocol.handle(client, raw, decoded, ctx(store))
        assertTrue(actions.any { it is TurnAction.ReleaseRelay })
        assertNull(store.get(client))
    }

    @Test
    fun channelBindRequiresAllocationAndRespects0x4000_0x7FFFRange() {
        val expirySec = (now / 1000L) + 300
        val username = TurnCredentials.buildUsername(expirySec, "alice")
        val key = TurnCredentials.derivePassword(secret, username)
        val store = InMemoryAllocationStore()
        store.put(
            client,
            TurnAllocation(
                client = client,
                relayed = serverAddr.copy(port = 49152),
                username = username,
                expiresAtMs = now + 600_000,
            ),
        )
        val request =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.CHANNEL_BIND, StunClass.REQUEST),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.Username(username),
                        StunAttribute.Realm(realm),
                        StunAttribute.Nonce(lastNonce),
                        StunAttribute.ChannelNumber(0x4001),
                        StunAttribute.XorPeerAddress(peerAddr),
                    ),
            )
        val raw = StunCodec.encodeWithMacAndFingerprint(request, key)
        val decoded = StunCodec.decode(raw)!!
        TurnProtocol.handle(client, raw, decoded, ctx(store))

        val alloc = store.get(client)!!
        assertEquals(peerAddr, alloc.peerForChannel(0x4001))
        // The implicit permission was created (RFC 5766 §11.2)
        assertTrue(alloc.hasPermission(peerAddr, now))
    }

    @Test
    fun channelBindOutsideRangeRejected() {
        val expirySec = (now / 1000L) + 300
        val username = TurnCredentials.buildUsername(expirySec, "alice")
        val key = TurnCredentials.derivePassword(secret, username)
        val store = InMemoryAllocationStore()
        store.put(
            client,
            TurnAllocation(
                client = client,
                relayed = serverAddr.copy(port = 49152),
                username = username,
                expiresAtMs = now + 600_000,
            ),
        )
        val request =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.CHANNEL_BIND, StunClass.REQUEST),
                transactionId = ByteArray(12),
                attributes =
                    listOf(
                        StunAttribute.Username(username),
                        StunAttribute.Realm(realm),
                        StunAttribute.Nonce(lastNonce),
                        StunAttribute.ChannelNumber(0x8000), // out of range
                        StunAttribute.XorPeerAddress(peerAddr),
                    ),
            )
        val raw = StunCodec.encodeWithMacAndFingerprint(request, key)
        val decoded = StunCodec.decode(raw)!!
        val actions = TurnProtocol.handle(client, raw, decoded, ctx(store))
        val send = actions.single() as TurnAction.SendStun
        assertEquals(StunError.BAD_REQUEST, send.message.findAttribute<StunAttribute.ErrorCode>()?.code)
    }

    @Test
    fun channelDataFrameRoundTrip() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val frame = ChannelData.encode(0x4002, payload)
        // 4-byte header + 5 payload + 3 pad
        assertEquals(12, frame.size)
        val decoded = ChannelData.decode(frame)!!
        assertEquals(0x4002, decoded.channel)
        assertEquals(payload.toList(), decoded.data.toList())
    }

    @Test
    fun channelDataDemultiplexedFromStun() {
        val frame = ChannelData.encode(0x4001, byteArrayOf(9, 9))
        assertTrue(ChannelData.isChannelData(frame))
        val stunBytes =
            StunCodec.encode(
                StunMessage(
                    type = StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST),
                    transactionId = ByteArray(12),
                    attributes = emptyList(),
                ),
            )
        assertFalse(ChannelData.isChannelData(stunBytes))
    }

    @Test
    fun base64MiniRoundTrip() {
        val cases =
            listOf(
                byteArrayOf(),
                byteArrayOf(0x66),
                byteArrayOf(0x66, 0x6F),
                byteArrayOf(0x66, 0x6F, 0x6F),
                byteArrayOf(0x66, 0x6F, 0x6F, 0x62),
                byteArrayOf(0x66, 0x6F, 0x6F, 0x62, 0x61, 0x72),
            )
        for (data in cases) {
            val encoded = Base64Mini.encode(data)
            val decoded = Base64Mini.decode(encoded) ?: error("decode failed: $encoded")
            assertEquals(data.toList(), decoded.toList(), "round-trip: ${data.size} bytes")
        }
        // Known values from RFC 4648 §10
        assertEquals("Zg==", Base64Mini.encode("f".encodeToByteArray()))
        assertEquals("Zm8=", Base64Mini.encode("fo".encodeToByteArray()))
        assertEquals("Zm9v", Base64Mini.encode("foo".encodeToByteArray()))
        assertEquals("Zm9vYg==", Base64Mini.encode("foob".encodeToByteArray()))
        assertEquals("Zm9vYmFy", Base64Mini.encode("foobar".encodeToByteArray()))
    }

    @Test
    fun credentialDerivationIsStable() {
        // Same secret + username yields same password — required for stateless
        // verification of long-term credentials.
        val u = TurnCredentials.buildUsername(1700000000L, "alice")
        val p1 = TurnCredentials.derivePassword(secret, u)
        val p2 = TurnCredentials.derivePassword(secret, u)
        assertEquals(p1.toList(), p2.toList())
        // Different username → different password
        val u2 = TurnCredentials.buildUsername(1700000000L, "bob")
        val p3 = TurnCredentials.derivePassword(secret, u2)
        assertFalse(p1.contentEquals(p3))
    }

    @Test
    fun parseExpiryHandlesMalformed() {
        assertNull(TurnCredentials.parseExpiry("no-colon"))
        assertNull(TurnCredentials.parseExpiry(":no-prefix"))
        assertNull(TurnCredentials.parseExpiry("notanumber:uuid"))
        assertEquals(1700000000L, TurnCredentials.parseExpiry("1700000000:alice"))
    }

    @Test
    fun transportAddressRequiresIpv4() {
        assertFails {
            TransportAddress(family = StunAddressFamily.IPV6, address = byteArrayOf(0, 0, 0, 0), port = 80)
        }
        assertFails {
            TransportAddress(address = byteArrayOf(1, 2, 3), port = 80) // 3-byte address
        }
    }
}
