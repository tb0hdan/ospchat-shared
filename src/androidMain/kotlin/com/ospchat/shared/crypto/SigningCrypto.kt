package com.ospchat.shared.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Android actual of [SigningCrypto]. Identical to the desktop actual — see
 * `desktopMain/.../crypto/SigningCrypto.kt` for the rationale on duplication.
 * Android's bundled BC provider is incomplete on pre-API-33 devices, so we
 * use the lightweight BC API directly instead of going through
 * `java.security.Signature("Ed25519")`.
 */
actual class SigningKeyPair internal constructor(
    private val privateKey: Ed25519PrivateKeyParameters,
    private val publicKey: Ed25519PublicKeyParameters,
) {
    actual fun publicKeyBytes(): ByteArray = publicKey.encoded

    actual fun privateSeedBytes(): ByteArray = privateKey.encoded

    actual fun sign(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }
}

actual class VerifyingKey internal constructor(
    private val publicKey: Ed25519PublicKeyParameters,
) {
    actual fun publicKeyBytes(): ByteArray = publicKey.encoded

    actual fun verify(
        message: ByteArray,
        signature: ByteArray,
    ): Boolean {
        if (signature.size != SigningCrypto.signatureSize) return false
        val verifier = Ed25519Signer()
        verifier.init(false, publicKey)
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}

actual object SigningCrypto {
    actual val publicKeySize: Int = Ed25519PublicKeyParameters.KEY_SIZE
    actual val privateSeedSize: Int = Ed25519PrivateKeyParameters.KEY_SIZE
    actual val signatureSize: Int = Ed25519PrivateKeyParameters.SIGNATURE_SIZE

    actual fun generate(): SigningKeyPair {
        val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val pub = priv.generatePublicKey()
        return SigningKeyPair(priv, pub)
    }

    actual fun fromSeed(seed: ByteArray): SigningKeyPair {
        require(seed.size == privateSeedSize) {
            "Ed25519 seed must be $privateSeedSize bytes, got ${seed.size}"
        }
        val priv = Ed25519PrivateKeyParameters(seed, 0)
        val pub = priv.generatePublicKey()
        return SigningKeyPair(priv, pub)
    }

    actual fun verifyingKey(publicKey: ByteArray): VerifyingKey {
        require(publicKey.size == publicKeySize) {
            "Ed25519 public key must be $publicKeySize bytes, got ${publicKey.size}"
        }
        return VerifyingKey(Ed25519PublicKeyParameters(publicKey, 0))
    }
}
