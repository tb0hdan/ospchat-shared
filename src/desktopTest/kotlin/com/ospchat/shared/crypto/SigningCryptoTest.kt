package com.ospchat.shared.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Round-trip coverage for the desktop actual of [SigningCrypto]. Mirrored on
 * Android via the byte-identical androidMain actual — no separate Android
 * test needed for phase 2a since both targets use BC's lightweight API.
 */
class SigningCryptoTest {
    @Test
    fun generateProducesCorrectlySizedKeyPair() {
        val keyPair = SigningCrypto.generate()
        assertEquals(SigningCrypto.publicKeySize, keyPair.publicKeyBytes().size)
        assertEquals(SigningCrypto.privateSeedSize, keyPair.privateSeedBytes().size)
    }

    @Test
    fun signAndVerifyRoundTrip() {
        val keyPair = SigningCrypto.generate()
        val verifier = SigningCrypto.verifyingKey(keyPair.publicKeyBytes())
        val msg = "hello, multi-network world".encodeToByteArray()
        val sig = keyPair.sign(msg)
        assertEquals(SigningCrypto.signatureSize, sig.size)
        assertTrue(verifier.verify(msg, sig), "valid sig must verify")
    }

    @Test
    fun verifyFailsOnTamperedMessage() {
        val keyPair = SigningCrypto.generate()
        val verifier = SigningCrypto.verifyingKey(keyPair.publicKeyBytes())
        val msg = "original".encodeToByteArray()
        val sig = keyPair.sign(msg)
        val tampered = "originaX".encodeToByteArray()
        assertFalse(verifier.verify(tampered, sig))
    }

    @Test
    fun verifyFailsOnTamperedSignature() {
        val keyPair = SigningCrypto.generate()
        val verifier = SigningCrypto.verifyingKey(keyPair.publicKeyBytes())
        val msg = "original".encodeToByteArray()
        val sig = keyPair.sign(msg).copyOf()
        sig[0] = (sig[0].toInt() xor 0x01).toByte()
        assertFalse(verifier.verify(msg, sig))
    }

    @Test
    fun verifyFailsWithDifferentKey() {
        val a = SigningCrypto.generate()
        val b = SigningCrypto.generate()
        assertNotEquals(a.publicKeyBytes().toList(), b.publicKeyBytes().toList())
        val msg = "x".encodeToByteArray()
        val sigFromA = a.sign(msg)
        val verifierForB = SigningCrypto.verifyingKey(b.publicKeyBytes())
        assertFalse(verifierForB.verify(msg, sigFromA))
    }

    @Test
    fun fromSeedIsDeterministic() {
        val original = SigningCrypto.generate()
        val seed = original.privateSeedBytes()
        val restored = SigningCrypto.fromSeed(seed)
        assertContentEquals(original.publicKeyBytes(), restored.publicKeyBytes())

        // And signatures from the restored keypair verify against the
        // original's pubkey.
        val msg = "deterministic".encodeToByteArray()
        val sigRestored = restored.sign(msg)
        val verifierOriginal = SigningCrypto.verifyingKey(original.publicKeyBytes())
        assertTrue(verifierOriginal.verify(msg, sigRestored))
    }

    @Test
    fun verifyRejectsWrongSizedSignature() {
        val keyPair = SigningCrypto.generate()
        val verifier = SigningCrypto.verifyingKey(keyPair.publicKeyBytes())
        val msg = "x".encodeToByteArray()
        // 63 bytes instead of 64.
        val shortSig = ByteArray(SigningCrypto.signatureSize - 1)
        assertFalse(verifier.verify(msg, shortSig))
    }

    @Test
    fun fromSeedRejectsWrongSize() {
        assertFails {
            SigningCrypto.fromSeed(ByteArray(8))
        }
    }

    @Test
    fun verifyingKeyRejectsWrongSize() {
        assertFails {
            SigningCrypto.verifyingKey(ByteArray(31))
        }
    }
}
