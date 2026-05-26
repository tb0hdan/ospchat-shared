package com.ospchat.shared.net.dto

import com.ospchat.shared.crypto.SignatureDomain
import com.ospchat.shared.crypto.SignaturePayloadBuilder
import kotlinx.serialization.Serializable

/**
 * Wire schema for `POST /v1/messages`.
 *
 * Phase 2b multi-network bridging — [signature] is the b64-encoded Ed25519
 * signature over the canonical payload built by [signaturePayload], using
 * [signedAt] (epoch millis) as the replay-window timestamp. Both fields are
 * nullable so peers running pre-2b builds can still POST during the
 * one-release backwards-compat rollout window. Once 2b is everywhere, the
 * receiver flips to "reject if absent".
 *
 * Phase 4 multi-network bridging — [toUuid] / [via] / [hopTtl] enable
 * **message-level relay** through a multi-homed peer (e.g. a desktop that
 * sits on two LANs which aren't routed between each other). Originator sets
 * [toUuid] to the final recipient and POSTs to a bridge peer; the bridge
 * sees `toUuid != self`, appends itself to [via], decrements [hopTtl],
 * forwards to the target. The signature commits [toUuid] (append-only
 * extension to the canonical payload; backwards-compatible with phase 2b
 * messages whose `toUuid == null`); [via] / [hopTtl] are not signed because
 * intermediates mutate them.
 */
@Serializable
data class IncomingMessageDto(
    val id: String,
    val fromUuid: String,
    val fromNickname: String,
    val body: String,
    val sentAt: Long,
    val attachment: AttachmentDto? = null,
    val signedAt: Long? = null,
    val signature: String? = null,
    val toUuid: String? = null,
    val via: List<String>? = null,
    val hopTtl: Int? = null,
)

fun IncomingMessageDto.signaturePayload(signedAt: Long): ByteArray {
    val b =
        SignaturePayloadBuilder(SignatureDomain.MESSAGE)
            .writeString(id)
            .writeString(fromUuid)
            .writeString(fromNickname)
            .writeString(body)
            .writeLong(sentAt)
            .also { inner ->
                val att = attachment
                if (att == null) {
                    inner.writeBoolean(false)
                } else {
                    inner
                        .writeBoolean(true)
                        .writeString(att.mimeType)
                        .writeLong(att.sizeBytes)
                        .writeInt(att.width)
                        .writeInt(att.height)
                }
            }.writeLong(signedAt)
    // Phase 4 append-only extension. Pre-phase-4 senders (toUuid == null)
    // append nothing → byte-identical to the phase-2b payload, so old
    // signatures still verify on new receivers and vice versa.
    if (toUuid != null) b.writeNullableString(toUuid)
    return b.build()
}

/**
 * Metadata for an image attachment. The binary itself is fetched separately
 * by the receiver via `GET /v1/attachments/{messageId}` on the sender.
 */
@Serializable
data class AttachmentDto(
    val mimeType: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
)

/** Wire schema for `POST /v1/read-receipts`. */
@Serializable
data class ReadReceiptDto(
    val fromUuid: String,
    val upToSentAt: Long,
    val signedAt: Long? = null,
    val signature: String? = null,
    val toUuid: String? = null,
    val via: List<String>? = null,
    val hopTtl: Int? = null,
)

fun ReadReceiptDto.signaturePayload(signedAt: Long): ByteArray {
    val b =
        SignaturePayloadBuilder(SignatureDomain.READ_RECEIPT)
            .writeString(fromUuid)
            .writeLong(upToSentAt)
            .writeLong(signedAt)
    if (toUuid != null) b.writeNullableString(toUuid)
    return b.build()
}

/** Wire schema for the body of any 4xx / 5xx response. */
@Serializable
data class ErrorDto(
    val error: String,
    val detail: String? = null,
)
