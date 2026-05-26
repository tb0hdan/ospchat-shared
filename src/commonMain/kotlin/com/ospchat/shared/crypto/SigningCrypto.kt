package com.ospchat.shared.crypto

/**
 * Per-install Ed25519 keypair. The private key is 32 raw bytes of seed; the
 * public key is 32 raw bytes derived from the seed. Phase 2a of multi-network
 * bridging introduces this so peers can be identified by a stable cryptographic
 * key rather than just an mDNS-published UUID — see `docs/SECURITY.md` F9 and
 * the desktop `PROJECT_NOTES.md` "Suggested next steps" item 7 phase 2.
 *
 * Phase 2a uses the keypair only for **identity binding**: the public key is
 * advertised in the mDNS TXT record (`pk=`) and in `GET /v1/info`, and the
 * discovery layer pins it on first-sight (TOFU) to reject same-UUID resolutions
 * from other responders. Phase 2b adds signed message DTOs.
 */
expect class SigningKeyPair {
    /** Raw 32-byte public key. Safe to hand out / persist / advertise. */
    fun publicKeyBytes(): ByteArray

    /** Raw 32-byte private seed. Treat as secret; never leaves the device. */
    fun privateSeedBytes(): ByteArray

    /**
     * Detached Ed25519 signature over [message]. Result is the canonical
     * 64-byte signature. Phase 2b uses this for per-message DTOs; phase 2a
     * generates keypairs but doesn't yet sign anything.
     */
    fun sign(message: ByteArray): ByteArray
}

/** Public-key half of a peer's identity. */
expect class VerifyingKey {
    /** Raw 32-byte public key. */
    fun publicKeyBytes(): ByteArray

    /** Verify a 64-byte detached signature over [message]. */
    fun verify(
        message: ByteArray,
        signature: ByteArray,
    ): Boolean
}

/** Cross-platform Ed25519 keypair factory. Wraps Bouncy Castle on every target. */
expect object SigningCrypto {
    /** Generate a fresh keypair from a cryptographically secure RNG. */
    fun generate(): SigningKeyPair

    /** Restore a keypair from its 32-byte private seed; the public half is derived. */
    fun fromSeed(seed: ByteArray): SigningKeyPair

    /** Build a verifier from raw 32-byte public key bytes. */
    fun verifyingKey(publicKey: ByteArray): VerifyingKey

    /** Canonical raw public-key byte length. Ed25519: 32. */
    val publicKeySize: Int

    /** Canonical raw private-seed byte length. Ed25519: 32. */
    val privateSeedSize: Int

    /** Canonical Ed25519 signature byte length: 64. */
    val signatureSize: Int
}
