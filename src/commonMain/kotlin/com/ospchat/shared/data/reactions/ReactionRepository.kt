package com.ospchat.shared.data.reactions

import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.groups.GroupDao
import com.ospchat.shared.data.identity.IdentityRepository
import com.ospchat.shared.data.peers.PeerDao
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.net.dto.ReactionDto
import com.ospchat.shared.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock

class ReactionRepository(
    private val dao: ReactionDao,
    private val client: MessageClient,
    private val identityRepository: IdentityRepository,
    private val groupDao: GroupDao? = null,
    private val peerDao: PeerDao? = null,
    private val discoveryRepository: DiscoveryRepository? = null,
) {
    fun reactionsForPeer(peerUuid: String): Flow<List<Reaction>> =
        dao
            .observeForPeer(peerUuid)
            .onEach { rows ->
                Log.d(
                    "ReactionRepo",
                    "DAO emit peerUuid=$peerUuid size=${rows.size} ids=${rows.map { it.messageId.take(8) + ":" + it.emoji }}",
                )
            }.map { rows -> rows.map(ReactionEntity::toDomain) }

    fun reactionsForGroup(groupId: String): Flow<List<Reaction>> =
        dao
            .observeForGroup(groupId)
            .map { rows -> rows.map(ReactionEntity::toDomain) }

    /**
     * Local user reacts on [messageId] with [emoji], or removes their
     * reaction entirely when [emoji] is `null`. Persists locally and
     * fires the wire-side update to [peer]. Network failures are not
     * retried at this layer — the next peer rediscovery picks up
     * divergence via newcomer-sync (or, eventually, a periodic
     * reconciliation step).
     */
    suspend fun react(
        peer: Peer,
        messageId: String,
        emoji: String?,
    ): Result<Unit> {
        val selfUuid = identityRepository.ensureUuid()
        val selfNickname = identityRepository.nicknameFlow.first().orEmpty()
        val reactedAt = Clock.System.now().toEpochMilliseconds()

        if (emoji == null) {
            dao.delete(messageId = messageId, fromUuid = selfUuid)
        } else {
            dao.upsert(
                ReactionEntity(
                    messageId = messageId,
                    fromUuid = selfUuid,
                    fromNickname = selfNickname,
                    emoji = emoji,
                    reactedAt = reactedAt,
                ),
            )
        }

        val dto =
            ReactionDto(
                messageId = messageId,
                fromUuid = selfUuid,
                fromNickname = selfNickname,
                emoji = emoji,
                reactedAt = reactedAt,
            )
        return runCatching { client.sendReaction(peer, dto) }
    }

    /**
     * Group analogue of [react]: persists locally and **fans out** the
     * reaction to every other current member of [groupId]. Same mesh
     * delivery model as `GroupMessageRepository.send` — no retry queue;
     * catch-up sync converges offline members on next re-discovery.
     *
     * Requires the optional [groupDao] / [peerDao] / [discoveryRepository]
     * collaborators to be wired (they are on every real platform). Throws
     * if invoked without them.
     */
    suspend fun reactToGroup(
        groupId: String,
        messageId: String,
        emoji: String?,
    ): Result<Unit> {
        val gd = groupDao ?: error("reactToGroup requires GroupDao")
        val selfUuid = identityRepository.ensureUuid()
        val selfNickname = identityRepository.nicknameFlow.first().orEmpty()
        val reactedAt = Clock.System.now().toEpochMilliseconds()

        if (emoji == null) {
            dao.delete(messageId = messageId, fromUuid = selfUuid)
        } else {
            dao.upsert(
                ReactionEntity(
                    messageId = messageId,
                    fromUuid = selfUuid,
                    fromNickname = selfNickname,
                    emoji = emoji,
                    reactedAt = reactedAt,
                ),
            )
        }

        val dto =
            ReactionDto(
                messageId = messageId,
                fromUuid = selfUuid,
                fromNickname = selfNickname,
                emoji = emoji,
                reactedAt = reactedAt,
                groupId = groupId,
            )
        var anySuccess = false
        gd.membersOf(groupId).forEach { member ->
            if (member.memberUuid == selfUuid) return@forEach
            val peer = resolvePeer(member.memberUuid) ?: return@forEach
            runCatching { client.sendReaction(peer, dto) }
                .onSuccess { anySuccess = true }
                .onFailure { Log.w(TAG, "fan-out to ${member.memberUuid} failed", it) }
        }
        return if (anySuccess) Result.success(Unit) else Result.failure(NoReachableMember)
    }

    /** Persist an inbound reaction from the peer end of the wire. */
    suspend fun applyReaction(dto: ReactionDto) {
        if (dto.emoji == null) {
            dao.delete(messageId = dto.messageId, fromUuid = dto.fromUuid)
        } else {
            dao.upsert(
                ReactionEntity(
                    messageId = dto.messageId,
                    fromUuid = dto.fromUuid,
                    fromNickname = dto.fromNickname,
                    emoji = dto.emoji,
                    reactedAt = dto.reactedAt,
                ),
            )
        }
    }

    /**
     * Most-recent [limit] reactions on messages in [groupId]; used by
     * catch-up sync. Caller bounds the size to keep the sync response
     * out of OOM territory (see docs/SECURITY.md D5).
     */
    suspend fun reactionsSnapshotForGroup(
        groupId: String,
        limit: Int,
    ): List<Reaction> = dao.snapshotForGroup(groupId, limit).map(ReactionEntity::toDomain)

    private suspend fun resolvePeer(memberUuid: String): Peer? {
        discoveryRepository?.findPeer(memberUuid)?.let { return it }
        val entity = peerDao?.findByUuid(memberUuid) ?: return null
        return Peer(
            uuid = entity.uuid,
            nickname = entity.nickname,
            host = entity.lastHost,
            port = entity.lastPort,
        )
    }

    private object NoReachableMember : RuntimeException("no reachable member")

    private companion object {
        const val TAG = "ReactionRepo"
    }
}
