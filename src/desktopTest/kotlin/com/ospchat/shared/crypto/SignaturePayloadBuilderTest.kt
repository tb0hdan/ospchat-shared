package com.ospchat.shared.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 2b multi-network bridging — unit tests for the canonical
 * signature-payload encoder. Determinism is the load-bearing property:
 * the same DTO must produce the same bytes on sender and receiver and
 * across runs, otherwise signatures can't verify.
 */
class SignaturePayloadBuilderTest {
    @Test
    fun deterministicAcrossInstances() {
        val a =
            SignaturePayloadBuilder("test/domain")
                .writeString("hello")
                .writeLong(42)
                .writeNullableString(null)
                .writeNullableString("world")
                .writeBoolean(true)
                .build()
        val b =
            SignaturePayloadBuilder("test/domain")
                .writeString("hello")
                .writeLong(42)
                .writeNullableString(null)
                .writeNullableString("world")
                .writeBoolean(true)
                .build()
        assertContentEquals(a, b)
    }

    @Test
    fun differentDomainsProduceDifferentBytes() {
        val a = SignaturePayloadBuilder("ospchat-v2b/messages").writeString("x").build()
        val b = SignaturePayloadBuilder("ospchat-v2b/reactions").writeString("x").build()
        assertFalse(a.contentEquals(b), "domain prefix must differentiate payloads")
    }

    @Test
    fun stringLengthPrefixIsBigEndianUint32() {
        val payload = SignaturePayloadBuilder("d").writeString("AB").build()
        // After "d\n": 4-byte BE length (0,0,0,2), then "AB" (0x41, 0x42).
        // Last 6 bytes hold these in order.
        val tail = payload.takeLast(6).toByteArray()
        assertContentEquals(byteArrayOf(0, 0, 0, 2, 0x41, 0x42), tail)
    }

    @Test
    fun longIsBigEndianInt64() {
        val payload = SignaturePayloadBuilder("d").writeLong(0x0102030405060708L).build()
        val tail = payload.takeLast(8).toByteArray()
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), tail)
    }

    @Test
    fun nullableStringPresenceMarkerIsOneByte() {
        val absent = SignaturePayloadBuilder("d").writeNullableString(null).build()
        val present = SignaturePayloadBuilder("d").writeNullableString("").build()
        // absent: presence-byte(1) only.
        // present-empty: presence-byte(1) + 4-byte length(0). Differ by 4.
        assertEquals(absent.size + 4, present.size, "present-empty adds 4 bytes vs absent")
        assertEquals(0x00.toByte(), absent.last())
    }

    @Test
    fun nullVsEmptyStringDiffer() {
        val nullish = SignaturePayloadBuilder("d").writeNullableString(null).build()
        val empty = SignaturePayloadBuilder("d").writeNullableString("").build()
        assertFalse(nullish.contentEquals(empty), "null and empty must be distinguishable")
    }

    @Test
    fun listEncodesCountAndElementsInOrder() {
        val payload =
            SignaturePayloadBuilder("d")
                .writeList(listOf("a", "bb")) { writeString(it) }
                .build()
        // After "d\n": 4-byte count=2, then 4-byte len=1 + "a", then 4-byte len=2 + "bb".
        val expected =
            byteArrayOf('d'.code.toByte(), 0x0A) +
                byteArrayOf(0, 0, 0, 2) +
                byteArrayOf(0, 0, 0, 1) +
                byteArrayOf('a'.code.toByte()) +
                byteArrayOf(0, 0, 0, 2) +
                byteArrayOf('b'.code.toByte(), 'b'.code.toByte())
        assertContentEquals(expected, payload)
    }

    @Test
    fun emptyListEncodesAsZeroCount() {
        val payload = SignaturePayloadBuilder("d").writeList(emptyList<String>()) { writeString(it) }.build()
        // "d" + 0x0A + 4-byte zero count = 6 bytes.
        assertEquals(6, payload.size)
    }

    @Test
    fun rejectsEmptyDomain() {
        assertFails {
            SignaturePayloadBuilder("")
        }
    }

    @Test
    fun builderGrowsBeyondInitialCapacity() {
        // INITIAL_CAPACITY is 128 — write more than that.
        val big = "x".repeat(500)
        val payload = SignaturePayloadBuilder("d").writeString(big).build()
        // Domain "d" + 0x0A + 4-byte length + 500 bytes
        assertEquals(2 + 4 + 500, payload.size)
    }

    @Test
    fun signatureRoundTripUsesPayloadIntegrity() {
        // End-to-end: build a payload, sign, mutate the source, verify
        // that signature no longer matches the new payload.
        val keyPair = SigningCrypto.generate()
        val verifier = SigningCrypto.verifyingKey(keyPair.publicKeyBytes())

        val payloadA = SignaturePayloadBuilder("d").writeString("hello").writeLong(1).build()
        val payloadB = SignaturePayloadBuilder("d").writeString("hello").writeLong(2).build()
        val sig = keyPair.sign(payloadA)
        assertTrue(verifier.verify(payloadA, sig))
        assertFalse(verifier.verify(payloadB, sig), "tampered payload must fail to verify")
    }
}
