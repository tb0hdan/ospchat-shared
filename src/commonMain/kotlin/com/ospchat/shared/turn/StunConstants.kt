// Constants for STUN (RFC 5389) and TURN (RFC 5766) message processing.
//
// Phase 3 multi-network bridging — these constants back StunCodec and the
// pure-function handlers in TurnProtocol. Only the subset required by
// libwebrtc clients is named; reserved / experimental attributes are accepted
// as opaque StunAttribute.Raw by the decoder rather than rejected.
package com.ospchat.shared.turn

/** STUN magic cookie (RFC 5389 §6). All modern STUN messages carry this. */
internal const val STUN_MAGIC_COOKIE: Int = 0x2112A442.toInt()

/** Transaction ID length in bytes (RFC 5389 §6). */
internal const val STUN_TRANSACTION_ID_BYTES: Int = 12

/** STUN message header length: 2 (type) + 2 (length) + 4 (cookie) + 12 (tx id). */
internal const val STUN_HEADER_BYTES: Int = 20

/** FINGERPRINT XOR mask (RFC 5389 §15.5). */
internal const val STUN_FINGERPRINT_XOR: Int = 0x5354554E

/**
 * STUN message class — encoded as the C1 and C0 bits inside the 16-bit
 * message-type field (RFC 5389 §6). The class determines whether a message
 * is a request, indication, success response, or error response.
 */
internal enum class StunClass(
    val bits: Int,
) {
    REQUEST(0b00),
    INDICATION(0b01),
    SUCCESS_RESPONSE(0b10),
    ERROR_RESPONSE(0b11),
    ;

    companion object {
        fun fromBits(bits: Int): StunClass? =
            when (bits and 0b11) {
                REQUEST.bits -> REQUEST
                INDICATION.bits -> INDICATION
                SUCCESS_RESPONSE.bits -> SUCCESS_RESPONSE
                ERROR_RESPONSE.bits -> ERROR_RESPONSE
                else -> null
            }
    }
}

/**
 * STUN / TURN method codes (12-bit value embedded in message type).
 * Only the methods used by libwebrtc clients are listed; unknown methods
 * are decoded and surfaced for the protocol layer to reject.
 */
internal object StunMethod {
    const val BINDING: Int = 0x001 // RFC 5389
    const val ALLOCATE: Int = 0x003 // RFC 5766 §6
    const val REFRESH: Int = 0x004 // RFC 5766 §7
    const val SEND: Int = 0x006 // RFC 5766 §10 (indication only)
    const val DATA: Int = 0x007 // RFC 5766 §10 (indication only)
    const val CREATE_PERMISSION: Int = 0x008 // RFC 5766 §9
    const val CHANNEL_BIND: Int = 0x009 // RFC 5766 §11
}

/**
 * STUN attribute types (RFC 5389 §15 and RFC 5766 §14).
 * Attributes with type < 0x8000 are comprehension-required; ≥ 0x8000 are
 * comprehension-optional. The decoder surfaces unknown attributes as
 * [StunAttribute.Raw]; the handlers decide whether to reject based on
 * comprehension-required rules.
 */
internal object StunAttrType {
    // RFC 5389
    const val MAPPED_ADDRESS: Int = 0x0001
    const val USERNAME: Int = 0x0006
    const val MESSAGE_INTEGRITY: Int = 0x0008
    const val ERROR_CODE: Int = 0x0009
    const val UNKNOWN_ATTRIBUTES: Int = 0x000A
    const val REALM: Int = 0x0014
    const val NONCE: Int = 0x0015
    const val XOR_MAPPED_ADDRESS: Int = 0x0020
    const val SOFTWARE: Int = 0x8022
    const val ALTERNATE_SERVER: Int = 0x8023
    const val FINGERPRINT: Int = 0x8028

    // RFC 5766 (TURN)
    const val CHANNEL_NUMBER: Int = 0x000C
    const val LIFETIME: Int = 0x000D
    const val XOR_PEER_ADDRESS: Int = 0x0012
    const val DATA: Int = 0x0013
    const val XOR_RELAYED_ADDRESS: Int = 0x0016
    const val EVEN_PORT: Int = 0x0018
    const val REQUESTED_TRANSPORT: Int = 0x0019
    const val DONT_FRAGMENT: Int = 0x001A
    const val RESERVATION_TOKEN: Int = 0x0022
}

/**
 * Address-family byte in MAPPED-ADDRESS / XOR-MAPPED-ADDRESS / XOR-PEER-ADDRESS
 * / XOR-RELAYED-ADDRESS attributes (RFC 5389 §15.1, §15.2).
 */
internal object StunAddressFamily {
    const val IPV4: Int = 0x01
    const val IPV6: Int = 0x02
}

/**
 * STUN / TURN error codes (RFC 5389 §15.6 + RFC 5766 §15).
 * Encoded as `class * 100 + number`; e.g. 401 = (4, 1).
 */
internal object StunError {
    const val BAD_REQUEST: Int = 400
    const val UNAUTHORIZED: Int = 401
    const val UNKNOWN_ATTRIBUTE: Int = 420
    const val STALE_NONCE: Int = 438
    const val ALLOCATION_MISMATCH: Int = 437
    const val WRONG_CREDENTIALS: Int = 441
    const val UNSUPPORTED_TRANSPORT_PROTOCOL: Int = 442
    const val ALLOCATION_QUOTA_REACHED: Int = 486
    const val SERVER_ERROR: Int = 500
    const val INSUFFICIENT_CAPACITY: Int = 508
}

/** UDP IP-protocol number for REQUESTED-TRANSPORT (RFC 5766 §14.7). */
internal const val TURN_TRANSPORT_UDP: Int = 17

/**
 * Default allocation lifetime when no LIFETIME attribute is present
 * (RFC 5766 §6.2 — "RECOMMENDED default is 600 seconds").
 */
internal const val TURN_DEFAULT_LIFETIME_SECONDS: Int = 600

/** Maximum allocation lifetime accepted by this server. */
internal const val TURN_MAX_LIFETIME_SECONDS: Int = 3600

/**
 * Permission lifetime (RFC 5766 §8). Fixed by spec at 5 minutes.
 */
internal const val TURN_PERMISSION_LIFETIME_SECONDS: Int = 300

/**
 * Channel-binding lifetime (RFC 5766 §11). Fixed by spec at 10 minutes.
 */
internal const val TURN_CHANNEL_LIFETIME_SECONDS: Int = 600

/** Valid channel-number range (RFC 5766 §11). */
internal const val TURN_CHANNEL_MIN: Int = 0x4000

internal const val TURN_CHANNEL_MAX: Int = 0x7FFF

/** Software string advertised by this implementation. */
internal const val TURN_SOFTWARE: String = "OSPChat-TURN/0.1"
