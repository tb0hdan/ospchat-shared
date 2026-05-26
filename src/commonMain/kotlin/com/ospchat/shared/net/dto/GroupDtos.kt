package com.ospchat.shared.net.dto

import com.ospchat.shared.crypto.SignatureDomain
import com.ospchat.shared.crypto.SignaturePayloadBuilder
import kotlinx.serialization.Serializable

/**
 * Authoritative description of a group's identity + membership at a point in
 * time. Carried on every group-related request so receivers can auto-create
 * (or upgrade) their local copy. Receivers only accept membership changes if
 * the snapshot is sent by the [creatorUuid] AND its [membershipUpdatedAt]
 * is newer than the local copy.
 *
 * Phase 2b multi-network bridging — [signature] is signed by the
 * [creatorUuid]'s Ed25519 key over a canonical payload. The signature
 * travels intact through mesh fan-out, so any peer receiving the snapshot
 * (directly or via sync) can verify it really came from the creator.
 */
@Serializable
data class GroupSnapshotDto(
    val id: String,
    val name: String,
    val kind: String,
    val creatorUuid: String,
    val createdAt: Long,
    val membershipUpdatedAt: Long,
    val members: List<GroupMemberDto>,
    val signedAt: Long? = null,
    val signature: String? = null,
    val toUuid: String? = null,
    val via: List<String>? = null,
    val hopTtl: Int? = null,
)

fun GroupSnapshotDto.signaturePayload(signedAt: Long): ByteArray {
    val b =
        SignaturePayloadBuilder(SignatureDomain.GROUP_SNAPSHOT)
            .writeString(id)
            .writeString(name)
            .writeString(kind)
            .writeString(creatorUuid)
            .writeLong(createdAt)
            .writeLong(membershipUpdatedAt)
            .writeList(members) { m ->
                writeString(m.uuid)
                writeString(m.nickname)
            }.writeLong(signedAt)
    if (toUuid != null) b.writeNullableString(toUuid)
    return b.build()
}

@Serializable
data class GroupMemberDto(
    val uuid: String,
    val nickname: String,
)

/**
 * Wire schema for one inbound group message.
 *
 * Phase 2b — signed by the message's [fromUuid] (original author). The
 * signature travels intact through mesh fan-out and group-sync replay,
 * so any peer encountering the message can verify it really came from
 * the named sender.
 */
@Serializable
data class GroupMessageDto(
    val id: String,
    val fromUuid: String,
    val fromNickname: String,
    val body: String,
    val sentAt: Long,
    val signedAt: Long? = null,
    val signature: String? = null,
    val toUuid: String? = null,
    val via: List<String>? = null,
    val hopTtl: Int? = null,
)

fun GroupMessageDto.signaturePayload(signedAt: Long): ByteArray {
    val b =
        SignaturePayloadBuilder(SignatureDomain.GROUP_MESSAGE)
            .writeString(id)
            .writeString(fromUuid)
            .writeString(fromNickname)
            .writeString(body)
            .writeLong(sentAt)
            .writeLong(signedAt)
    if (toUuid != null) b.writeNullableString(toUuid)
    return b.build()
}

/**
 * `POST /v1/groups/messages` body. The DTO itself is *not* signed — its two
 * components ([snapshot] and [message]) carry their own signatures, signed
 * by the snapshot creator and message author respectively. This lets a
 * relay forward a group message without holding the original author's key.
 */
@Serializable
data class GroupMessagePostDto(
    val snapshot: GroupSnapshotDto,
    val message: GroupMessageDto,
)

/** Per-group sync cursor: caller's latest known `sent_at` for [groupId]. */
@Serializable
data class GroupSyncCursorDto(
    val groupId: String,
    val latestSentAt: Long,
)

/**
 * `POST /v1/groups/sync` body.
 *
 * Phase 2b — signed by [fromUuid] (the requester). Receivers verify so
 * a peer can't spoof a sync request "from" someone else and pull their
 * group state.
 */
@Serializable
data class GroupSyncRequestDto(
    val fromUuid: String,
    val cursors: List<GroupSyncCursorDto>,
    val signedAt: Long? = null,
    val signature: String? = null,
    val toUuid: String? = null,
    val via: List<String>? = null,
    val hopTtl: Int? = null,
)

fun GroupSyncRequestDto.signaturePayload(signedAt: Long): ByteArray {
    val b =
        SignaturePayloadBuilder(SignatureDomain.GROUP_SYNC_REQUEST)
            .writeString(fromUuid)
            .writeList(cursors) { c ->
                writeString(c.groupId)
                writeLong(c.latestSentAt)
            }.writeLong(signedAt)
    if (toUuid != null) b.writeNullableString(toUuid)
    return b.build()
}

/**
 * Per-group sync payload: snapshot the caller should adopt + missing messages.
 *
 * [reactions] carries every current reaction for messages in this group's
 * `group_messages` table so the caller catches up on reactions that arrived
 * while they were offline. Defaults to empty for back-compat with peers that
 * predate group reactions.
 */
@Serializable
data class GroupSyncPayloadDto(
    val snapshot: GroupSnapshotDto,
    val messages: List<GroupMessageDto>,
    val reactions: List<ReactionDto> = emptyList(),
)

/** `POST /v1/groups/sync` response. */
@Serializable
data class GroupSyncResponseDto(
    val payloads: List<GroupSyncPayloadDto>,
)

/**
 * `POST /v1/groups/leave` body. The [fromUuid] is the member who is leaving.
 *
 * Phase 2b — signed by [fromUuid] so a peer can't forge a leave-the-group
 * notification "from" another member.
 */
@Serializable
data class GroupLeaveDto(
    val groupId: String,
    val fromUuid: String,
    val signedAt: Long? = null,
    val signature: String? = null,
    val toUuid: String? = null,
    val via: List<String>? = null,
    val hopTtl: Int? = null,
)

fun GroupLeaveDto.signaturePayload(signedAt: Long): ByteArray {
    val b =
        SignaturePayloadBuilder(SignatureDomain.GROUP_LEAVE)
            .writeString(groupId)
            .writeString(fromUuid)
            .writeLong(signedAt)
    if (toUuid != null) b.writeNullableString(toUuid)
    return b.build()
}
