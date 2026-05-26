package com.ospchat.shared.net.dto

import com.ospchat.shared.crypto.SignatureDomain
import com.ospchat.shared.crypto.SignaturePayloadBuilder
import kotlinx.serialization.Serializable

/**
 * Wire schema for `POST /v1/reactions`. `emoji == null` means the sender is
 * *removing* their reaction from [messageId]; otherwise it's an
 * upsert that replaces whatever single reaction they had on the same
 * message.
 *
 * [groupId] is non-null when the reaction is on a group message. Receivers
 * use it to validate the sender against group membership instead of the
 * DM "peer is the message's peer" rule. Defaults to `null` for wire
 * compatibility with peers that predate group reactions.
 *
 * Phase 2b multi-network bridging — [signature] / [signedAt] carry the
 * sender's Ed25519 signature; both nullable for the one-release rollout
 * window.
 */
@Serializable
data class ReactionDto(
    val messageId: String,
    val fromUuid: String,
    val fromNickname: String,
    val emoji: String?,
    val reactedAt: Long,
    val groupId: String? = null,
    val signedAt: Long? = null,
    val signature: String? = null,
    val toUuid: String? = null,
    val via: List<String>? = null,
    val hopTtl: Int? = null,
)

fun ReactionDto.signaturePayload(signedAt: Long): ByteArray {
    val b =
        SignaturePayloadBuilder(SignatureDomain.REACTION)
            .writeString(messageId)
            .writeString(fromUuid)
            .writeString(fromNickname)
            .writeNullableString(emoji)
            .writeLong(reactedAt)
            .writeNullableString(groupId)
            .writeLong(signedAt)
    if (toUuid != null) b.writeNullableString(toUuid)
    return b.build()
}
