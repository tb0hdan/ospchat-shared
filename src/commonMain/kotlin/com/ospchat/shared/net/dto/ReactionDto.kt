package com.ospchat.shared.net.dto

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
 */
@Serializable
data class ReactionDto(
    val messageId: String,
    val fromUuid: String,
    val fromNickname: String,
    val emoji: String?,
    val reactedAt: Long,
    val groupId: String? = null,
)
