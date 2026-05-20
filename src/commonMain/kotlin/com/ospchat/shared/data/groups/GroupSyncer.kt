package com.ospchat.shared.data.groups

import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.reactions.ReactionRepository
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.net.dto.GroupMessageDto
import com.ospchat.shared.net.dto.GroupSyncCursorDto
import com.ospchat.shared.net.dto.GroupSyncPayloadDto
import com.ospchat.shared.net.dto.GroupSyncRequestDto
import com.ospchat.shared.net.dto.GroupSyncResponseDto
import com.ospchat.shared.net.dto.ReactionDto
import com.ospchat.shared.util.Log

/**
 * Pulls group history from a peer we share at least one group with.
 *
 * Triggered on every NSD "absent → present" transition (same hook as
 * [PeerAvatarSync][com.ospchat.shared.data.peers.PeerAvatarSync]).
 *
 * Bidirectional: we ask the peer for messages newer than what we have, and
 * the peer's server-side `/v1/groups/sync` returns its current snapshot plus
 * any missing messages. The peer's own catch-up runs symmetrically when we
 * appear in their NSD snapshot.
 */
class GroupSyncer(
    private val groupDao: GroupDao,
    private val groupMessageDao: GroupMessageDao,
    private val groupRepository: GroupRepository,
    private val client: MessageClient,
    private val identityRepository: IdentityRepository,
    private val reactionRepository: ReactionRepository? = null,
) {
    suspend fun sync(peer: Peer) {
        val sharedGroupIds = groupDao.groupsContaining(peer.uuid)
        if (sharedGroupIds.isEmpty()) return

        val selfUuid = identityRepository.ensureUuid()
        val cursors =
            sharedGroupIds.mapNotNull { id ->
                val members = groupDao.membersOf(id)
                if (members.none { it.memberUuid == selfUuid }) return@mapNotNull null
                val latest = groupMessageDao.latestSentAt(id) ?: 0L
                GroupSyncCursorDto(groupId = id, latestSentAt = latest)
            }
        if (cursors.isEmpty()) return
        val request = GroupSyncRequestDto(fromUuid = selfUuid, cursors = cursors)
        val response: GroupSyncResponseDto =
            runCatching { client.syncGroups(peer, request, rediscover = false) }
                .getOrElse {
                    Log.w(TAG, "sync request to ${peer.uuid} failed", it)
                    return
                }
        response.payloads.forEach { payload -> applyPayload(peer.uuid, payload) }
    }

    private suspend fun applyPayload(
        fromUuid: String,
        payload: GroupSyncPayloadDto,
    ) {
        groupRepository.applySnapshot(fromUuid = fromUuid, snapshot = payload.snapshot)
        val group = groupDao.findById(payload.snapshot.id) ?: return
        val kind = runCatching { GroupKind.valueOf(group.kind) }.getOrDefault(GroupKind.CHAT)
        val members = groupDao.membersOf(group.id).map { it.memberUuid }.toSet()
        payload.messages.forEach { msg ->
            if (msg.fromUuid !in members) return@forEach
            if (kind == GroupKind.BROADCAST && msg.fromUuid != group.creatorUuid) return@forEach
            // No notification for catch-up messages — these aren't fresh.
            groupMessageDao.insert(
                GroupMessage(
                    id = msg.id,
                    groupId = group.id,
                    fromUuid = msg.fromUuid,
                    fromNickname = msg.fromNickname,
                    body = msg.body,
                    sentAt = msg.sentAt,
                    direction = GroupMessage.Direction.IN,
                    status = GroupMessage.Status.DELIVERED,
                ).toEntity(),
            )
        }
        val rxRepo = reactionRepository
        if (rxRepo != null) {
            payload.reactions.forEach { rx ->
                if (rx.fromUuid !in members) return@forEach
                rxRepo.applyReaction(rx)
            }
        }
    }

    /** Server-side responder: build the response for an inbound sync request. */
    suspend fun buildResponse(request: GroupSyncRequestDto): GroupSyncResponseDto {
        val selfUuid = identityRepository.ensureUuid()
        val payloads =
            request.cursors.mapNotNull { cursor ->
                val group = groupDao.findById(cursor.groupId) ?: return@mapNotNull null
                val members = groupDao.membersOf(group.id)
                if (members.none { it.memberUuid == request.fromUuid }) return@mapNotNull null
                if (members.none { it.memberUuid == selfUuid }) return@mapNotNull null
                val snapshot = groupRepository.snapshotOf(group.id) ?: return@mapNotNull null
                val missing =
                    groupMessageDao
                        .messagesAfter(group.id, cursor.latestSentAt)
                        .map {
                            GroupMessageDto(
                                id = it.id,
                                fromUuid = it.fromUuid,
                                fromNickname = it.fromNickname,
                                body = it.body,
                                sentAt = it.sentAt,
                            )
                        }
                // Include current reactions for the whole group, not only the
                // missing slice — a reaction can land on an old message after
                // the message itself synced, and there's no per-reaction cursor
                // yet. Idempotent upsert on the receiver makes this safe.
                val reactions =
                    reactionRepository
                        ?.reactionsSnapshotForGroup(group.id)
                        ?.map { r ->
                            ReactionDto(
                                messageId = r.messageId,
                                fromUuid = r.fromUuid,
                                fromNickname = r.fromNickname,
                                emoji = r.emoji,
                                reactedAt = r.reactedAt,
                                groupId = group.id,
                            )
                        }.orEmpty()
                GroupSyncPayloadDto(snapshot = snapshot, messages = missing, reactions = reactions)
            }
        return GroupSyncResponseDto(payloads = payloads)
    }

    private companion object {
        const val TAG = "GroupSyncer"
    }
}
