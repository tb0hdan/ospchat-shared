package com.ospchat.shared.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [StunCodec]. Coverage:
 *  - Message header round-trip (encode → decode → equal)
 *  - Class/method bit packing (RFC 5389 §6 Fig. 3)
 *  - XOR-MAPPED-ADDRESS encoding (RFC 5389 §15.2 — both ports and IPv4 are XOR-masked with the magic cookie)
 *  - Attribute padding to 4-byte boundary
 *  - MESSAGE-INTEGRITY and FINGERPRINT generation (RFC 5389 §15.4, §15.5)
 *  - Rejection of bad magic cookie and unaligned attribute lengths
 *  - CRC32 baseline against a known input
 */
class StunCodecTest {
    @Test
    fun typeEncodingPacksClassAndMethod() {
        // Method = Binding (0x001), class = Request (00) → 0x0001 (RFC 5389 Fig. 3)
        assertEquals(0x0001, StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST))
        // Method = Binding, class = Success Response (10) → 0x0101
        assertEquals(0x0101, StunMessage.encodeType(StunMethod.BINDING, StunClass.SUCCESS_RESPONSE))
        // Method = Allocate (0x003), class = Request → 0x0003
        assertEquals(0x0003, StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.REQUEST))
        // Method = Allocate, class = Error Response (11) → 0x0113
        assertEquals(0x0113, StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.ERROR_RESPONSE))
        // Method = Send (0x006), class = Indication (01) → 0x0016
        assertEquals(0x0016, StunMessage.encodeType(StunMethod.SEND, StunClass.INDICATION))
        // Method = Data (0x007), class = Indication → 0x0017
        assertEquals(0x0017, StunMessage.encodeType(StunMethod.DATA, StunClass.INDICATION))
    }

    @Test
    fun typeDecodingExtractsClassAndMethod() {
        assertEquals(StunMethod.BINDING, StunMessage.decodeMethod(0x0001))
        assertEquals(StunClass.REQUEST, StunMessage.decodeClass(0x0001))
        assertEquals(StunClass.SUCCESS_RESPONSE, StunMessage.decodeClass(0x0101))
        assertEquals(StunMethod.ALLOCATE, StunMessage.decodeMethod(0x0113))
        assertEquals(StunClass.ERROR_RESPONSE, StunMessage.decodeClass(0x0113))
        assertEquals(StunMethod.SEND, StunMessage.decodeMethod(0x0016))
        assertEquals(StunClass.INDICATION, StunMessage.decodeClass(0x0016))
    }

    @Test
    fun roundTripPreservesAttributes() {
        val txId = ByteArray(12) { (it + 7).toByte() }
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.REQUEST),
                transactionId = txId,
                attributes =
                    listOf(
                        StunAttribute.Username("alice@example.com"),
                        StunAttribute.RequestedTransport(TURN_TRANSPORT_UDP),
                        StunAttribute.Lifetime(600),
                        StunAttribute.Software("test"),
                    ),
            )
        val bytes = StunCodec.encode(msg)
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun xorMappedAddressMasksWithMagicCookie() {
        // Known answer: address 192.168.1.5, port 49152 (= 0xC000).
        // XOR port = 0xC000 ^ 0x2112 = 0xE112
        // XOR addr = [192,168,1,5] ^ [0x21,0x12,0xA4,0x42] = [0xE1,0xBA,0xA5,0x47]
        val txId = ByteArray(12)
        val addr =
            TransportAddress(
                family = StunAddressFamily.IPV4,
                address = byteArrayOf(192.toByte(), 168.toByte(), 1, 5),
                port = 49152,
            )
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.SUCCESS_RESPONSE),
                transactionId = txId,
                attributes = listOf(StunAttribute.XorMappedAddress(addr)),
            )
        val bytes = StunCodec.encode(msg)
        // Header (20) + attr header (4) + attr value (8) = 32
        assertEquals(32, bytes.size)
        // Attribute value at offset 24..31:
        //   reserved(0), family(0x01), xor-port(0xE1,0x12), xor-addr(0xE1,0xBA,0xA5,0x47)
        assertEquals(0x00.toByte(), bytes[24])
        assertEquals(0x01.toByte(), bytes[25])
        assertEquals(0xE1.toByte(), bytes[26])
        assertEquals(0x12.toByte(), bytes[27])
        assertEquals(0xE1.toByte(), bytes[28])
        assertEquals(0xBA.toByte(), bytes[29])
        assertEquals(0xA5.toByte(), bytes[30])
        assertEquals(0x47.toByte(), bytes[31])
        // Decode round-trip
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        val xma = decoded.findAttribute<StunAttribute.XorMappedAddress>()
        assertNotNull(xma)
        assertEquals(addr, xma.address)
    }

    @Test
    fun attributesPadToFourByteBoundary() {
        val txId = ByteArray(12)
        // USERNAME of length 3 → needs 1 byte pad
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST),
                transactionId = txId,
                attributes = listOf(StunAttribute.Username("abc"), StunAttribute.RequestedTransport(17)),
            )
        val bytes = StunCodec.encode(msg)
        // Header (20) + USERNAME hdr (4) + 3 bytes + 1 pad + REQUESTED-TRANSPORT (8) = 36
        assertEquals(36, bytes.size)
        // Pad byte at offset 27 must be zero
        assertEquals(0.toByte(), bytes[27])
        // Decode preserves the original string (no padding leakage)
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        val username = decoded.findAttribute<StunAttribute.Username>()
        assertEquals("abc", username?.value)
    }

    @Test
    fun decodeRejectsBadMagicCookie() {
        val bytes = ByteArray(20)
        // type=0x0001, length=0, magic=0xDEADBEEF
        bytes[0] = 0
        bytes[1] = 1
        bytes[4] = 0xDE.toByte()
        bytes[5] = 0xAD.toByte()
        bytes[6] = 0xBE.toByte()
        bytes[7] = 0xEF.toByte()
        assertNull(StunCodec.decode(bytes))
    }

    @Test
    fun decodeRejectsTopTwoBitsSet() {
        val bytes = ByteArray(20)
        bytes[0] = 0xC0.toByte() // top bits 11 — not a STUN message
        assertNull(StunCodec.decode(bytes))
    }

    @Test
    fun decodeRejectsTooShort() {
        assertNull(StunCodec.decode(ByteArray(10)))
        assertNull(StunCodec.decode(ByteArray(19)))
    }

    @Test
    fun messageIntegrityRoundTripsAndVerifies() {
        val txId = ByteArray(12) { it.toByte() }
        val key = "secret-credential".encodeToByteArray()
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST),
                transactionId = txId,
                attributes = listOf(StunAttribute.Username("alice")),
            )
        val signed = StunCodec.encodeWithMacAndFingerprint(msg, key)
        val decoded = StunCodec.decode(signed)
        assertNotNull(decoded)
        // Verifier accepts the signature
        assertTrue(StunCodec.verifyMessageIntegrity(signed, decoded, key))
        // Wrong key rejected
        assertFalse(StunCodec.verifyMessageIntegrity(signed, decoded, "wrong-key".encodeToByteArray()))
    }

    @Test
    fun messageIntegrityVerificationRejectsTamperedBody() {
        val txId = ByteArray(12) { (it + 1).toByte() }
        val key = "k".encodeToByteArray()
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST),
                transactionId = txId,
                attributes = listOf(StunAttribute.Username("alice")),
            )
        val signed = StunCodec.encodeWithMacAndFingerprint(msg, key)
        // Flip a bit in the USERNAME value (after the 20-byte header + 4-byte attr header).
        signed[24] = (signed[24].toInt() xor 0x01).toByte()
        val decoded = StunCodec.decode(signed)
        assertNotNull(decoded)
        assertFalse(StunCodec.verifyMessageIntegrity(signed, decoded, key))
    }

    @Test
    fun fingerprintAppendsCorrectCrc() {
        val txId = ByteArray(12)
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST),
                transactionId = txId,
                attributes = emptyList(),
            )
        val bytes = StunCodec.encodeWithMacAndFingerprint(msg, ByteArray(16))
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        val fp = decoded.findAttribute<StunAttribute.Fingerprint>()
        assertNotNull(fp)
        // CRC must be over bytes [0, fp_attr_start) XOR 0x5354554E.
        val fpStart = bytes.size - 8 // FP is the last 8 bytes (4 hdr + 4 value)
        val crcInput = bytes.copyOf(fpStart)
        val expected = crc32(crcInput) xor STUN_FINGERPRINT_XOR
        assertEquals(expected, fp.crc32)
    }

    @Test
    fun crc32MatchesKnownVector() {
        // "123456789" → CRC32 = 0xCBF43926 (ISO/HDLC)
        val input = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926.toInt(), crc32(input))
    }

    @Test
    fun errorCodeRoundTrip() {
        val txId = ByteArray(12)
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.ERROR_RESPONSE),
                transactionId = txId,
                attributes = listOf(StunAttribute.ErrorCode(401, "Unauthorized")),
            )
        val bytes = StunCodec.encode(msg)
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        val ec = decoded.findAttribute<StunAttribute.ErrorCode>()
        assertEquals(401, ec?.code)
        assertEquals("Unauthorized", ec?.reason)
    }

    @Test
    fun unknownAttributePreservedAsRaw() {
        // Build an attribute payload manually: type=0x9999 (comprehension-optional),
        // length=4, value=[1,2,3,4]
        val txId = ByteArray(12)
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.BINDING, StunClass.REQUEST),
                transactionId = txId,
                attributes = listOf(StunAttribute.Raw(0x9999, byteArrayOf(1, 2, 3, 4))),
            )
        val bytes = StunCodec.encode(msg)
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        val raw = decoded.attributes.first() as StunAttribute.Raw
        assertEquals(0x9999, raw.type)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), raw.value)
    }

    @Test
    fun lifetimeAndRequestedTransportEncode() {
        val txId = ByteArray(12)
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.REQUEST),
                transactionId = txId,
                attributes =
                    listOf(
                        StunAttribute.Lifetime(300),
                        StunAttribute.RequestedTransport(TURN_TRANSPORT_UDP),
                    ),
            )
        val bytes = StunCodec.encode(msg)
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        assertEquals(300, decoded.findAttribute<StunAttribute.Lifetime>()?.seconds)
        assertEquals(TURN_TRANSPORT_UDP, decoded.findAttribute<StunAttribute.RequestedTransport>()?.protocol)
    }

    @Test
    fun dontFragmentEncodesAsZeroLengthAttribute() {
        val txId = ByteArray(12)
        val msg =
            StunMessage(
                type = StunMessage.encodeType(StunMethod.ALLOCATE, StunClass.REQUEST),
                transactionId = txId,
                attributes = listOf(StunAttribute.DontFragment),
            )
        val bytes = StunCodec.encode(msg)
        // Header (20) + attr hdr (4) + 0 value = 24
        assertEquals(24, bytes.size)
        val decoded = StunCodec.decode(bytes)
        assertNotNull(decoded)
        assertEquals(StunAttribute.DontFragment, decoded.attributes.first())
    }

    private fun assertContentEquals(
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals(expected[i], actual[i], "byte $i")
    }
}
