package com.ospchat.shared.crypto

/**
 * Canonical byte encoder for DTO signature payloads. Phase 2b multi-network
 * bridging: every signed wire DTO defines a stable `signaturePayload(signedAt)`
 * extension that uses this builder so sender and receiver hash exactly the
 * same bytes.
 *
 * The format is deliberately **not** JSON. JSON canonicalisation (RFC 8785,
 * JCS) is full of footguns — float formatting, key ordering across language
 * runtimes, Unicode normalisation. A small length-prefixed binary format is
 * trivially deterministic across implementations and easy to audit.
 *
 * Format:
 *   ```
 *   <domain-bytes> 0x0A
 *   <field-1> <field-2> ... <field-N>
 *   ```
 *
 * Each field is one of:
 *   - **string**: 4-byte big-endian uint32 length, then UTF-8 bytes.
 *   - **long**: 8-byte big-endian signed int64.
 *   - **int**: 4-byte big-endian signed int32.
 *   - **boolean**: 1 byte (0x00 = false, 0x01 = true), used as a presence
 *     marker for nullable fields.
 *   - **list**: 4-byte length prefix, then N elements written by the caller.
 *
 * The domain prefix prevents cross-DTO signature reuse: a signature valid
 * for an [IncomingMessageDto] body cannot be replayed as a [ReactionDto]
 * because the domain string changes the hashed bytes.
 */
class SignaturePayloadBuilder(
    domain: String,
) {
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var pos = 0

    init {
        val domainBytes = domain.encodeToByteArray()
        require(domainBytes.isNotEmpty()) { "domain must be non-empty" }
        writeRaw(domainBytes)
        writeByte(DOMAIN_DELIMITER)
    }

    fun writeString(value: String): SignaturePayloadBuilder =
        apply {
            val bytes = value.encodeToByteArray()
            writeUint32(bytes.size)
            writeRaw(bytes)
        }

    /** Null vs. empty-string is preserved: presence byte + (length + bytes) only when present. */
    fun writeNullableString(value: String?): SignaturePayloadBuilder =
        apply {
            if (value == null) {
                writeByte(ABSENT)
            } else {
                writeByte(PRESENT)
                writeString(value)
            }
        }

    fun writeLong(value: Long): SignaturePayloadBuilder =
        apply {
            for (i in 7 downTo 0) {
                writeByte(((value shr (i * 8)) and 0xFF).toByte())
            }
        }

    fun writeNullableLong(value: Long?): SignaturePayloadBuilder =
        apply {
            if (value == null) {
                writeByte(ABSENT)
            } else {
                writeByte(PRESENT)
                writeLong(value)
            }
        }

    fun writeInt(value: Int): SignaturePayloadBuilder =
        apply {
            writeUint32(value)
        }

    fun writeBoolean(value: Boolean): SignaturePayloadBuilder =
        apply {
            writeByte(if (value) PRESENT else ABSENT)
        }

    /**
     * Write a list of elements. The caller's [writeElement] is invoked for
     * each item in iteration order, so the same iterable always produces
     * the same bytes — keep the iteration order stable in the DTO.
     */
    fun <T> writeList(
        items: List<T>,
        writeElement: SignaturePayloadBuilder.(T) -> Unit,
    ): SignaturePayloadBuilder =
        apply {
            writeUint32(items.size)
            for (item in items) {
                writeElement(item)
            }
        }

    fun build(): ByteArray = buf.copyOf(pos)

    private fun writeByte(value: Byte) {
        ensure(1)
        buf[pos++] = value
    }

    private fun writeRaw(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buf, pos)
        pos += bytes.size
    }

    private fun writeUint32(value: Int) {
        ensure(4)
        buf[pos++] = ((value ushr 24) and 0xFF).toByte()
        buf[pos++] = ((value ushr 16) and 0xFF).toByte()
        buf[pos++] = ((value ushr 8) and 0xFF).toByte()
        buf[pos++] = (value and 0xFF).toByte()
    }

    private fun ensure(extra: Int) {
        if (pos + extra > buf.size) {
            var newSize = buf.size * 2
            while (newSize < pos + extra) newSize *= 2
            buf = buf.copyOf(newSize)
        }
    }

    private companion object {
        const val INITIAL_CAPACITY = 128
        const val DOMAIN_DELIMITER: Byte = 0x0A
        const val ABSENT: Byte = 0x00
        const val PRESENT: Byte = 0x01
    }
}

/**
 * Domain prefixes for every signed DTO. Kept in one place so cross-DTO
 * collisions are obvious during review. The `ospchat-v2b/` namespace
 * leaves room for a future signature-format revision without colliding
 * with these.
 */
object SignatureDomain {
    const val MESSAGE = "ospchat-v2b/messages"
    const val READ_RECEIPT = "ospchat-v2b/read-receipts"
    const val REACTION = "ospchat-v2b/reactions"
    const val GROUP_SNAPSHOT = "ospchat-v2b/groups/snapshot"
    const val GROUP_MESSAGE = "ospchat-v2b/groups/message"
    const val GROUP_SYNC_REQUEST = "ospchat-v2b/groups/sync-request"
    const val GROUP_LEAVE = "ospchat-v2b/groups/leave"

    // Phase 3 multi-network bridging — voice-call signaling DTOs and the
    // /v1/call/relay-cred wire pair pick up signatures.
    const val CALL_OFFER = "ospchat-v3/calls/offer"
    const val CALL_ANSWER = "ospchat-v3/calls/answer"
    const val CALL_ICE = "ospchat-v3/calls/ice"
    const val CALL_HANGUP = "ospchat-v3/calls/hangup"
    const val RELAY_CRED_REQUEST = "ospchat-v3/calls/relay-cred-request"
    const val RELAY_CRED_RESPONSE = "ospchat-v3/calls/relay-cred-response"
}

/**
 * Replay-window for [signedAt] verification. Receivers reject a signature
 * whose `signedAt` is more than this many milliseconds away from the
 * receiver's local clock in either direction. Generous enough to tolerate
 * a few minutes of clock drift between peers on a LAN; tight enough that
 * a captured message can't be replayed hours later.
 */
const val SIGNATURE_REPLAY_WINDOW_MS: Long = 5L * 60L * 1000L
