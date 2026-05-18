package com.ospchat.shared.data.groups

/**
 * UI-facing group message. Same shape as [com.ospchat.shared.data.messages.Message]
 * minus attachments + read-receipts (out of scope for v1 groups).
 *
 * Entity mappers live alongside the Room entity (moved into commonMain when
 * Room is migrated to KMP — task #4).
 */
data class GroupMessage(
    val id: String,
    val groupId: String,
    val fromUuid: String,
    val fromNickname: String,
    val body: String,
    val sentAt: Long,
    val direction: Direction,
    val status: Status,
) {
    enum class Direction { IN, OUT }

    enum class Status { SENDING, DELIVERED, FAILED }
}
