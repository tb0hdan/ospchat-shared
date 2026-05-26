package com.ospchat.shared.turn

import com.ospchat.shared.crypto.HmacSha1

/**
 * STUN / TURN binary message codec. Pure data manipulation — no I/O, no
 * coroutines, no platform types. The decode path is forgiving: unrecognised
 * attributes become [StunAttribute.Raw] rather than failures, so the protocol
 * layer can apply the RFC 5389 §15 comprehension-required rule itself and
 * surface a `420 Unknown Attribute` response with a useful attribute list.
 *
 * **Wire layout** (RFC 5389 §6, Fig. 2):
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |0 0|     STUN Message Type     |         Message Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         Magic Cookie                          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                                                               |
 * |                     Transaction ID (96 bits)                  |
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ```
 * Each attribute: type (2 bytes), length (2 bytes), value (length bytes),
 * padded to a 4-byte boundary.
 */
internal object StunCodec {
    /**
     * Decode a STUN message from raw UDP datagram bytes. Returns null when the
     * bytes are too short, have the wrong magic cookie, or describe attributes
     * that overflow the buffer. The first byte of every STUN message has its
     * top two bits clear — TURN ChannelData multiplexes on the same port but
     * starts with bits 01, so the caller is expected to demultiplex before
     * calling here.
     */
    fun decode(
        buf: ByteArray,
        offset: Int = 0,
        length: Int = buf.size - offset,
    ): StunMessage? {
        if (length < STUN_HEADER_BYTES) return null
        if ((buf[offset].toInt() and 0xC0) != 0) return null // top bits must be 00

        val type = readUInt16(buf, offset)
        val messageLength = readUInt16(buf, offset + 2)
        val cookie = readInt32(buf, offset + 4)
        if (cookie != STUN_MAGIC_COOKIE) return null
        if (length < STUN_HEADER_BYTES + messageLength) return null
        if (messageLength % 4 != 0) return null // attributes are 4-byte aligned

        val txId = buf.copyOfRange(offset + 8, offset + 20)
        val attrs = mutableListOf<StunAttribute>()

        var pos = offset + STUN_HEADER_BYTES
        val end = offset + STUN_HEADER_BYTES + messageLength
        while (pos < end) {
            if (pos + 4 > end) return null
            val attrType = readUInt16(buf, pos)
            val attrLen = readUInt16(buf, pos + 2)
            val valStart = pos + 4
            val valEnd = valStart + attrLen
            if (valEnd > end) return null

            val attr = decodeAttribute(attrType, buf, valStart, attrLen, txId) ?: return null
            attrs += attr

            // Advance past attribute including 4-byte alignment padding.
            pos = valEnd
            val pad = (4 - (attrLen % 4)) % 4
            if (pos + pad > end) return null
            pos += pad
        }
        return StunMessage(type, txId, attrs)
    }

    /**
     * Encode a STUN message to a fresh ByteArray. No HMAC / FINGERPRINT is
     * computed here — for that, use [encodeWithMacAndFingerprint]. This
     * variant is intended for messages that don't carry credentials (e.g.
     * unauthenticated 401 challenges, indications without MESSAGE-INTEGRITY).
     */
    fun encode(message: StunMessage): ByteArray {
        val bodyBytes = encodeAttributes(message.attributes, message.transactionId)
        val out = ByteArray(STUN_HEADER_BYTES + bodyBytes.size)
        writeHeader(out, message.type, bodyBytes.size, message.transactionId)
        bodyBytes.copyInto(out, STUN_HEADER_BYTES)
        return out
    }

    /**
     * Encode [message], then append MESSAGE-INTEGRITY (HMAC-SHA1 keyed with
     * [hmacKey]) and FINGERPRINT in that order — RFC 5389 §15.4, §15.5.
     *
     * The `hmacKey` for long-term credentials is `MD5(username:realm:password)`
     * (RFC 5389 §15.4), but TURN-REST-API style this can be replaced with a
     * caller-supplied secret. The caller is responsible for selecting the
     * right key per request.
     */
    fun encodeWithMacAndFingerprint(
        message: StunMessage,
        hmacKey: ByteArray,
    ): ByteArray {
        val bodyBytes = encodeAttributes(message.attributes, message.transactionId)
        // Reserve space for MESSAGE-INTEGRITY (4 hdr + 20 mac = 24) and FINGERPRINT
        // (4 hdr + 4 crc = 8). Both are 4-byte aligned and need no padding.
        val miBytes = 4 + 20
        val fpBytes = 4 + 4
        val fullBodyLen = bodyBytes.size + miBytes + fpBytes

        val out = ByteArray(STUN_HEADER_BYTES + fullBodyLen)

        // Step 1: compute MAC over the message with `length` field set to
        // cover the body so far + MESSAGE-INTEGRITY (RFC 5389 §15.4).
        writeHeader(out, message.type, bodyBytes.size + miBytes, message.transactionId)
        bodyBytes.copyInto(out, STUN_HEADER_BYTES)
        val macInput = out.copyOf(STUN_HEADER_BYTES + bodyBytes.size)
        val mac = HmacSha1.mac(hmacKey, macInput)
        require(mac.size == 20) { "HMAC-SHA1 must produce 20 bytes, got ${mac.size}" }

        // Step 2: write MESSAGE-INTEGRITY attribute header + value.
        var pos = STUN_HEADER_BYTES + bodyBytes.size
        writeUInt16(out, pos, StunAttrType.MESSAGE_INTEGRITY)
        writeUInt16(out, pos + 2, 20)
        mac.copyInto(out, pos + 4)
        pos += miBytes

        // Step 3: bump header length to cover FINGERPRINT, then CRC32 the
        // bytes preceding it (RFC 5389 §15.5).
        writeUInt16(out, 2, fullBodyLen)
        val crcInput = out.copyOf(pos)
        val crc = crc32(crcInput) xor STUN_FINGERPRINT_XOR

        writeUInt16(out, pos, StunAttrType.FINGERPRINT)
        writeUInt16(out, pos + 2, 4)
        writeInt32(out, pos + 4, crc)

        return out
    }

    /**
     * Verify that [message]'s MESSAGE-INTEGRITY attribute matches the HMAC
     * computed with [hmacKey] over the byte form of [message] up to (but not
     * including) the MESSAGE-INTEGRITY attribute. Returns false when no
     * MESSAGE-INTEGRITY attribute is present.
     *
     * The provided [rawBytes] is the originally-received datagram payload
     * (RFC 5389 §15.4 mandates verifying against the actual received bytes,
     * not against a re-encoded form — re-encoding can rearrange attributes
     * or change unknown-attribute encodings).
     */
    fun verifyMessageIntegrity(
        rawBytes: ByteArray,
        message: StunMessage,
        hmacKey: ByteArray,
    ): Boolean {
        val mi = message.findAttribute<StunAttribute.MessageIntegrity>() ?: return false

        // Locate the byte position of the MESSAGE-INTEGRITY attribute header.
        val miStart = findAttributeStart(rawBytes, StunAttrType.MESSAGE_INTEGRITY) ?: return false

        // RFC 5389 §15.4: the length field is set to point one past the end
        // of MESSAGE-INTEGRITY for the MAC computation. We rewrite a fresh
        // header buffer to honour that.
        val patched = rawBytes.copyOfRange(0, miStart)
        val lengthForMac = miStart - STUN_HEADER_BYTES + 4 + 20
        writeUInt16(patched, 2, lengthForMac)

        val expected = HmacSha1.mac(hmacKey, patched)
        return constantTimeEquals(mi.mac, expected)
    }

    // -------------------------------------------------------------------------
    // Attribute encoding / decoding
    // -------------------------------------------------------------------------

    private fun decodeAttribute(
        type: Int,
        buf: ByteArray,
        valStart: Int,
        valLen: Int,
        txId: ByteArray,
    ): StunAttribute? =
        when (type) {
            StunAttrType.USERNAME -> {
                StunAttribute.Username(buf.decodeToString(valStart, valStart + valLen))
            }

            StunAttrType.REALM -> {
                StunAttribute.Realm(buf.decodeToString(valStart, valStart + valLen))
            }

            StunAttrType.NONCE -> {
                StunAttribute.Nonce(buf.decodeToString(valStart, valStart + valLen))
            }

            StunAttrType.SOFTWARE -> {
                StunAttribute.Software(buf.decodeToString(valStart, valStart + valLen))
            }

            StunAttrType.MESSAGE_INTEGRITY -> {
                if (valLen != 20) {
                    null
                } else {
                    StunAttribute.MessageIntegrity(buf.copyOfRange(valStart, valStart + 20))
                }
            }

            StunAttrType.FINGERPRINT -> {
                if (valLen != 4) {
                    null
                } else {
                    StunAttribute.Fingerprint(readInt32(buf, valStart))
                }
            }

            StunAttrType.ERROR_CODE -> {
                decodeErrorCode(buf, valStart, valLen)
            }

            StunAttrType.UNKNOWN_ATTRIBUTES -> {
                if (valLen % 2 != 0) {
                    null
                } else {
                    val list = ArrayList<Int>(valLen / 2)
                    for (i in 0 until valLen step 2) {
                        list += readUInt16(buf, valStart + i)
                    }
                    StunAttribute.UnknownAttributes(list)
                }
            }

            StunAttrType.XOR_MAPPED_ADDRESS -> {
                decodeAddress(buf, valStart, valLen, xor = true, txId = txId)
                    ?.let { StunAttribute.XorMappedAddress(it) }
            }

            StunAttrType.XOR_PEER_ADDRESS -> {
                decodeAddress(buf, valStart, valLen, xor = true, txId = txId)
                    ?.let { StunAttribute.XorPeerAddress(it) }
            }

            StunAttrType.XOR_RELAYED_ADDRESS -> {
                decodeAddress(buf, valStart, valLen, xor = true, txId = txId)
                    ?.let { StunAttribute.XorRelayedAddress(it) }
            }

            StunAttrType.LIFETIME -> {
                if (valLen != 4) null else StunAttribute.Lifetime(readInt32(buf, valStart))
            }

            StunAttrType.REQUESTED_TRANSPORT -> {
                if (valLen != 4) null else StunAttribute.RequestedTransport(buf[valStart].toInt() and 0xFF)
            }

            StunAttrType.CHANNEL_NUMBER -> {
                if (valLen != 4) null else StunAttribute.ChannelNumber(readUInt16(buf, valStart))
            }

            StunAttrType.DATA -> {
                StunAttribute.Data(buf.copyOfRange(valStart, valStart + valLen))
            }

            StunAttrType.DONT_FRAGMENT -> {
                if (valLen != 0) null else StunAttribute.DontFragment
            }

            else -> {
                StunAttribute.Raw(type, buf.copyOfRange(valStart, valStart + valLen))
            }
        }

    private fun encodeAttributes(
        attrs: List<StunAttribute>,
        txId: ByteArray,
    ): ByteArray {
        // First pass: compute total size.
        var total = 0
        for (a in attrs) total += 4 + paddedSize(rawValueSize(a, txId))
        val out = ByteArray(total)
        var pos = 0
        for (a in attrs) {
            val (attrType, valBytes) = encodeAttributeValue(a, txId)
            writeUInt16(out, pos, attrType)
            writeUInt16(out, pos + 2, valBytes.size)
            valBytes.copyInto(out, pos + 4)
            pos += 4 + valBytes.size
            val pad = (4 - (valBytes.size % 4)) % 4
            // pad bytes are already zero (fresh ByteArray)
            pos += pad
        }
        return out
    }

    private fun rawValueSize(
        a: StunAttribute,
        txId: ByteArray,
    ): Int =
        when (a) {
            is StunAttribute.Raw -> a.value.size

            is StunAttribute.Username -> a.value.encodeToByteArray().size

            is StunAttribute.Realm -> a.value.encodeToByteArray().size

            is StunAttribute.Nonce -> a.value.encodeToByteArray().size

            is StunAttribute.Software -> a.value.encodeToByteArray().size

            is StunAttribute.MessageIntegrity -> 20

            is StunAttribute.Fingerprint -> 4

            is StunAttribute.ErrorCode -> 4 + a.reason.encodeToByteArray().size

            is StunAttribute.UnknownAttributes -> a.types.size * 2

            is StunAttribute.XorMappedAddress,
            is StunAttribute.XorPeerAddress,
            is StunAttribute.XorRelayedAddress,
            -> 8

            // IPv4 only
            is StunAttribute.Lifetime -> 4

            is StunAttribute.RequestedTransport -> 4

            is StunAttribute.ChannelNumber -> 4

            is StunAttribute.Data -> a.bytes.size

            is StunAttribute.DontFragment -> 0
        }

    private fun encodeAttributeValue(
        a: StunAttribute,
        txId: ByteArray,
    ): Pair<Int, ByteArray> =
        when (a) {
            is StunAttribute.Raw -> {
                a.type to a.value
            }

            is StunAttribute.Username -> {
                StunAttrType.USERNAME to a.value.encodeToByteArray()
            }

            is StunAttribute.Realm -> {
                StunAttrType.REALM to a.value.encodeToByteArray()
            }

            is StunAttribute.Nonce -> {
                StunAttrType.NONCE to a.value.encodeToByteArray()
            }

            is StunAttribute.Software -> {
                StunAttrType.SOFTWARE to a.value.encodeToByteArray()
            }

            is StunAttribute.MessageIntegrity -> {
                StunAttrType.MESSAGE_INTEGRITY to a.mac
            }

            is StunAttribute.Fingerprint -> {
                val v = ByteArray(4)
                writeInt32(v, 0, a.crc32)
                StunAttrType.FINGERPRINT to v
            }

            is StunAttribute.ErrorCode -> {
                StunAttrType.ERROR_CODE to encodeErrorCode(a)
            }

            is StunAttribute.UnknownAttributes -> {
                val v = ByteArray(a.types.size * 2)
                for ((i, t) in a.types.withIndex()) writeUInt16(v, i * 2, t)
                StunAttrType.UNKNOWN_ATTRIBUTES to v
            }

            is StunAttribute.XorMappedAddress -> {
                StunAttrType.XOR_MAPPED_ADDRESS to encodeXorAddress(a.address, txId)
            }

            is StunAttribute.XorPeerAddress -> {
                StunAttrType.XOR_PEER_ADDRESS to encodeXorAddress(a.address, txId)
            }

            is StunAttribute.XorRelayedAddress -> {
                StunAttrType.XOR_RELAYED_ADDRESS to encodeXorAddress(a.address, txId)
            }

            is StunAttribute.Lifetime -> {
                val v = ByteArray(4)
                writeInt32(v, 0, a.seconds)
                StunAttrType.LIFETIME to v
            }

            is StunAttribute.RequestedTransport -> {
                val v = ByteArray(4)
                v[0] = a.protocol.toByte()
                StunAttrType.REQUESTED_TRANSPORT to v
            }

            is StunAttribute.ChannelNumber -> {
                val v = ByteArray(4)
                writeUInt16(v, 0, a.channel)
                StunAttrType.CHANNEL_NUMBER to v
            }

            is StunAttribute.Data -> {
                StunAttrType.DATA to a.bytes
            }

            is StunAttribute.DontFragment -> {
                StunAttrType.DONT_FRAGMENT to ByteArray(0)
            }
        }

    private fun decodeAddress(
        buf: ByteArray,
        start: Int,
        len: Int,
        xor: Boolean,
        txId: ByteArray,
    ): TransportAddress? {
        if (len < 8) return null
        val family = buf[start + 1].toInt() and 0xFF
        if (family != StunAddressFamily.IPV4) return null // phase 3: IPv4 only
        val rawPort = readUInt16(buf, start + 2)
        val rawAddr = buf.copyOfRange(start + 4, start + 8)
        val port =
            if (xor) {
                rawPort xor ((STUN_MAGIC_COOKIE ushr 16) and 0xFFFF)
            } else {
                rawPort
            }
        val addr =
            if (xor) {
                ByteArray(4).also {
                    it[0] = (rawAddr[0].toInt() xor ((STUN_MAGIC_COOKIE ushr 24) and 0xFF)).toByte()
                    it[1] = (rawAddr[1].toInt() xor ((STUN_MAGIC_COOKIE ushr 16) and 0xFF)).toByte()
                    it[2] = (rawAddr[2].toInt() xor ((STUN_MAGIC_COOKIE ushr 8) and 0xFF)).toByte()
                    it[3] = (rawAddr[3].toInt() xor (STUN_MAGIC_COOKIE and 0xFF)).toByte()
                }
            } else {
                rawAddr
            }
        return TransportAddress(StunAddressFamily.IPV4, addr, port)
    }

    private fun encodeXorAddress(
        addr: TransportAddress,
        txId: ByteArray,
    ): ByteArray {
        // RFC 5389 §15.2: 1 byte reserved, 1 byte family, 2 bytes XOR'd port,
        // 4 bytes XOR'd address (IPv4).
        val out = ByteArray(8)
        out[0] = 0
        out[1] = addr.family.toByte()
        val xorPort = addr.port xor ((STUN_MAGIC_COOKIE ushr 16) and 0xFFFF)
        writeUInt16(out, 2, xorPort)
        out[4] = (addr.address[0].toInt() xor ((STUN_MAGIC_COOKIE ushr 24) and 0xFF)).toByte()
        out[5] = (addr.address[1].toInt() xor ((STUN_MAGIC_COOKIE ushr 16) and 0xFF)).toByte()
        out[6] = (addr.address[2].toInt() xor ((STUN_MAGIC_COOKIE ushr 8) and 0xFF)).toByte()
        out[7] = (addr.address[3].toInt() xor (STUN_MAGIC_COOKIE and 0xFF)).toByte()
        return out
    }

    private fun decodeErrorCode(
        buf: ByteArray,
        start: Int,
        len: Int,
    ): StunAttribute.ErrorCode? {
        if (len < 4) return null
        // RFC 5389 §15.6: 2 bytes reserved, 1 byte class (top 3 bits used),
        // 1 byte number, then UTF-8 reason.
        val errClass = buf[start + 2].toInt() and 0x07
        val number = buf[start + 3].toInt() and 0xFF
        val code = errClass * 100 + number
        val reason = buf.decodeToString(start + 4, start + len)
        return StunAttribute.ErrorCode(code, reason)
    }

    private fun encodeErrorCode(a: StunAttribute.ErrorCode): ByteArray {
        val reason = a.reason.encodeToByteArray()
        val out = ByteArray(4 + reason.size)
        // 2 bytes reserved == 0
        out[2] = (a.code / 100).toByte()
        out[3] = (a.code % 100).toByte()
        reason.copyInto(out, 4)
        return out
    }

    private fun findAttributeStart(
        buf: ByteArray,
        attrType: Int,
    ): Int? {
        if (buf.size < STUN_HEADER_BYTES) return null
        val messageLength = readUInt16(buf, 2)
        var pos = STUN_HEADER_BYTES
        val end = STUN_HEADER_BYTES + messageLength
        if (end > buf.size) return null
        while (pos < end) {
            if (pos + 4 > end) return null
            val type = readUInt16(buf, pos)
            val len = readUInt16(buf, pos + 2)
            if (type == attrType) return pos
            val padded = paddedSize(len)
            pos += 4 + padded
        }
        return null
    }

    // -------------------------------------------------------------------------
    // Header helpers
    // -------------------------------------------------------------------------

    private fun writeHeader(
        out: ByteArray,
        type: Int,
        bodyLen: Int,
        txId: ByteArray,
    ) {
        writeUInt16(out, 0, type)
        writeUInt16(out, 2, bodyLen)
        writeInt32(out, 4, STUN_MAGIC_COOKIE)
        txId.copyInto(out, 8)
    }

    private fun paddedSize(len: Int): Int = len + ((4 - (len % 4)) % 4)

    // -------------------------------------------------------------------------
    // Byte-buffer helpers (big-endian)
    // -------------------------------------------------------------------------

    private fun readUInt16(
        buf: ByteArray,
        off: Int,
    ): Int = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)

    private fun readInt32(
        buf: ByteArray,
        off: Int,
    ): Int =
        ((buf[off].toInt() and 0xFF) shl 24) or
            ((buf[off + 1].toInt() and 0xFF) shl 16) or
            ((buf[off + 2].toInt() and 0xFF) shl 8) or
            (buf[off + 3].toInt() and 0xFF)

    private fun writeUInt16(
        buf: ByteArray,
        off: Int,
        value: Int,
    ) {
        buf[off] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt32(
        buf: ByteArray,
        off: Int,
        value: Int,
    ) {
        buf[off] = ((value ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((value ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((value ushr 8) and 0xFF).toByte()
        buf[off + 3] = (value and 0xFF).toByte()
    }

    private fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/**
 * Standard CRC-32 (ISO/HDLC poly 0xEDB88320), kept in this file as a tiny
 * platform-free implementation so FINGERPRINT computation stays in commonMain.
 * Output matches `java.util.zip.CRC32`.
 */
internal fun crc32(
    buf: ByteArray,
    offset: Int = 0,
    length: Int = buf.size - offset,
): Int {
    var crc = -0x1 // 0xFFFFFFFF in 32-bit
    val end = offset + length
    for (i in offset until end) {
        val byte = buf[i].toInt() and 0xFF
        var c = (crc xor byte) and 0xFF
        for (k in 0 until 8) {
            c = if ((c and 1) != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
        }
        crc = (crc ushr 8) xor c
    }
    return crc.inv()
}
