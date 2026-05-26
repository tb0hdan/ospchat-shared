package com.ospchat.shared.turn

/**
 * Typed STUN / TURN attribute. The codec parses the wire bytes into one of
 * these variants when the attribute type is recognised, and falls back to
 * [Raw] for everything else. Order in [StunMessage.attributes] is preserved
 * — RFC 5389 §15 requires MESSAGE-INTEGRITY / FINGERPRINT to appear last,
 * and channels (RFC 5766 §11.3) require attributes to be processed in the
 * order they were sent.
 */
internal sealed class StunAttribute {
    /** Raw attribute bytes — used for unknown / opaque attribute types. */
    data class Raw(
        val type: Int,
        val value: ByteArray,
    ) : StunAttribute() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Raw) return false
            return type == other.type && value.contentEquals(other.value)
        }

        override fun hashCode(): Int = 31 * type + value.contentHashCode()
    }

    /** USERNAME (RFC 5389 §15.3) — UTF-8, ≤ 513 bytes. */
    data class Username(
        val value: String,
    ) : StunAttribute()

    /** REALM (RFC 5389 §15.7). */
    data class Realm(
        val value: String,
    ) : StunAttribute()

    /** NONCE (RFC 5389 §15.8). */
    data class Nonce(
        val value: String,
    ) : StunAttribute()

    /** SOFTWARE (RFC 5389 §15.10). */
    data class Software(
        val value: String,
    ) : StunAttribute()

    /**
     * MESSAGE-INTEGRITY (RFC 5389 §15.4) — HMAC-SHA1 keyed with the
     * long-term credential key over the message up to (but not including)
     * this attribute. 20 raw bytes when present.
     */
    data class MessageIntegrity(
        val mac: ByteArray,
    ) : StunAttribute() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MessageIntegrity) return false
            return mac.contentEquals(other.mac)
        }

        override fun hashCode(): Int = mac.contentHashCode()
    }

    /** FINGERPRINT (RFC 5389 §15.5) — CRC32 of the message up to (but not including) this attribute, XOR 0x5354554E. */
    data class Fingerprint(
        val crc32: Int,
    ) : StunAttribute()

    /** ERROR-CODE (RFC 5389 §15.6). */
    data class ErrorCode(
        val code: Int,
        val reason: String,
    ) : StunAttribute()

    /** UNKNOWN-ATTRIBUTES (RFC 5389 §15.9) — emitted in 420 responses. */
    data class UnknownAttributes(
        val types: List<Int>,
    ) : StunAttribute()

    /** XOR-MAPPED-ADDRESS (RFC 5389 §15.2). */
    data class XorMappedAddress(
        val address: TransportAddress,
    ) : StunAttribute()

    /** XOR-PEER-ADDRESS (RFC 5766 §14.3). */
    data class XorPeerAddress(
        val address: TransportAddress,
    ) : StunAttribute()

    /** XOR-RELAYED-ADDRESS (RFC 5766 §14.5). */
    data class XorRelayedAddress(
        val address: TransportAddress,
    ) : StunAttribute()

    /** LIFETIME (RFC 5766 §14.2) — uint32 seconds. */
    data class Lifetime(
        val seconds: Int,
    ) : StunAttribute()

    /** REQUESTED-TRANSPORT (RFC 5766 §14.7) — uint8 IP protocol number in high byte; 24 bits RFFU. */
    data class RequestedTransport(
        val protocol: Int,
    ) : StunAttribute()

    /** CHANNEL-NUMBER (RFC 5766 §14.1) — uint16 channel number; 16 bits RFFU. */
    data class ChannelNumber(
        val channel: Int,
    ) : StunAttribute()

    /** DATA (RFC 5766 §14.4) — opaque payload bytes (the relayed application data). */
    data class Data(
        val bytes: ByteArray,
    ) : StunAttribute() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Data) return false
            return bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** DONT-FRAGMENT (RFC 5766 §14.8) — zero-length presence-only attribute. */
    data object DontFragment : StunAttribute()
}
