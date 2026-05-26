package com.ospchat.shared.turn

/**
 * ChannelData framing (RFC 5766 §11.5). An alternative to wrapping data in
 * Send/Data Indications: once a peer is bound to a 16-bit channel number,
 * subsequent payloads use this much shorter framing.
 *
 * Wire layout:
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         Channel Number        |            Length             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       Application Data                        |
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 *
 * Demultiplexing from STUN: the first byte's top two bits are 00 for STUN
 * and 01 for ChannelData (channel numbers occupy 0x4000–0x7FFF). The UDP
 * receive loop checks `buf[0] & 0xC0` to dispatch.
 *
 * RFC 5766 §11.5: "Over UDP, the ChannelData message MUST be padded to a
 * multiple of four bytes." We honour that on encode and tolerate (skip
 * trailing padding bytes) on decode.
 */
internal object ChannelData {
    const val HEADER_BYTES: Int = 4

    /** Identifies a UDP datagram as ChannelData rather than a STUN message. */
    fun isChannelData(
        buf: ByteArray,
        offset: Int = 0,
    ): Boolean {
        if (buf.size <= offset) return false
        return (buf[offset].toInt() and 0xC0) == 0x40
    }

    data class Frame(
        val channel: Int,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Frame) return false
            return channel == other.channel && data.contentEquals(other.data)
        }

        override fun hashCode(): Int = 31 * channel + data.contentHashCode()
    }

    fun decode(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
    ): Frame? {
        if (length < HEADER_BYTES) return null
        val channel = ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
        if (channel !in TURN_CHANNEL_MIN..TURN_CHANNEL_MAX) return null
        val len = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        if (HEADER_BYTES + len > length) return null
        return Frame(channel, buf.copyOfRange(offset + HEADER_BYTES, offset + HEADER_BYTES + len))
    }

    fun encode(
        channel: Int,
        data: ByteArray,
    ): ByteArray {
        require(channel in TURN_CHANNEL_MIN..TURN_CHANNEL_MAX) { "channel out of range: $channel" }
        require(data.size <= 0xFFFF) { "data too large for ChannelData: ${data.size}" }
        val padded = data.size + ((4 - (data.size % 4)) % 4)
        val out = ByteArray(HEADER_BYTES + padded)
        out[0] = ((channel ushr 8) and 0xFF).toByte()
        out[1] = (channel and 0xFF).toByte()
        out[2] = ((data.size ushr 8) and 0xFF).toByte()
        out[3] = (data.size and 0xFF).toByte()
        data.copyInto(out, HEADER_BYTES)
        return out
    }
}
