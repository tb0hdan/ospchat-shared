package com.ospchat.shared.turn

/**
 * Decoded STUN / TURN message — header fields plus an ordered list of
 * attributes. Construction is via [StunCodec.decode]; encoding is via
 * [StunCodec.encode].
 *
 * Attribute order matters: MESSAGE-INTEGRITY and FINGERPRINT (when present)
 * must appear last and last-of-last respectively (RFC 5389 §15.4, §15.5).
 * Callers building responses are expected to append them via
 * [StunCodec.encodeWithMac] which positions them correctly.
 */
internal data class StunMessage(
    val type: Int,
    val transactionId: ByteArray,
    val attributes: List<StunAttribute>,
) {
    val method: Int get() = decodeMethod(type)
    val messageClass: StunClass? get() = decodeClass(type)

    init {
        require(transactionId.size == STUN_TRANSACTION_ID_BYTES) {
            "transaction id must be $STUN_TRANSACTION_ID_BYTES bytes, got ${transactionId.size}"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StunMessage) return false
        if (type != other.type) return false
        if (!transactionId.contentEquals(other.transactionId)) return false
        return attributes == other.attributes
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + transactionId.contentHashCode()
        result = 31 * result + attributes.hashCode()
        return result
    }

    /** Return the first attribute of the given type, or null if absent. */
    inline fun <reified T : StunAttribute> findAttribute(): T? {
        for (a in attributes) if (a is T) return a
        return null
    }

    companion object {
        /**
         * STUN message type encoding (RFC 5389 §6, Fig. 3):
         * ```
         *  0                 1
         *  0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * |M |M |M |M |M |C |M |M |M |C |M |M |M |M |M |M |
         * |11|10|9 |8 |7 |1 |6 |5 |4 |0 |3 |2 |1 |0 |0 |0 |
         * +--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+--+
         * ```
         * (top two bits are always 00 for STUN).
         */
        fun encodeType(
            method: Int,
            messageClass: StunClass,
        ): Int {
            require(method in 0..0xFFF) { "method out of range: $method" }
            val c1 = (messageClass.bits and 0b10) shr 1
            val c0 = messageClass.bits and 0b01
            // method bits: M11..M7 | C1 | M6..M4 | C0 | M3..M0 (RFC 5389 Fig. 3)
            val methodHi = (method and 0xF80) shr 7
            val methodMid = (method and 0x070) shr 4
            val methodLo = method and 0x00F
            return (methodHi shl 9) or (c1 shl 8) or (methodMid shl 5) or (c0 shl 4) or methodLo
        }

        fun decodeMethod(type: Int): Int {
            val methodHi = (type and 0x3E00) shr 9
            val methodMid = (type and 0x00E0) shr 5
            val methodLo = type and 0x000F
            return (methodHi shl 7) or (methodMid shl 4) or methodLo
        }

        fun decodeClass(type: Int): StunClass? {
            val c1 = (type and 0x0100) shr 8
            val c0 = (type and 0x0010) shr 4
            return StunClass.fromBits((c1 shl 1) or c0)
        }
    }
}
