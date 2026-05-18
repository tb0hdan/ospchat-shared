package com.ospchat.shared.domain.groups

import com.ospchat.shared.data.discovery.DiscoveryRepository
import com.ospchat.shared.data.discovery.Peer
import com.ospchat.shared.data.groups.GroupDao
import com.ospchat.shared.data.groups.GroupRepository
import com.ospchat.shared.data.peers.PeerDao
import com.ospchat.shared.net.client.MessageClient
import com.ospchat.shared.net.dto.GroupLeaveDto
import com.ospchat.shared.util.Log

/**
 * Pushes group-state events (snapshot, leave) to every reachable member.
 *
 * In practice this class is invoked from the creator side after every
 * mutation so reachable members converge immediately; offline members will
 * pick the snapshot up on next message or catch-up sync.
 */
class GroupBroadcaster(
    private val groupDao: GroupDao,
    private val groupRepository: GroupRepository,
    private val peerDao: PeerDao,
    private val discoveryRepository: DiscoveryRepository,
    private val client: MessageClient,
) {
    suspend fun broadcastSnapshot(groupId: String) {
        val snapshot = groupRepository.snapshotOf(groupId) ?: return
        val selfUuid = groupRepository.selfUuid()
        val targets = groupDao.membersOf(groupId)
        targets.forEach { member ->
            if (member.memberUuid == selfUuid) return@forEach
            val peer = resolvePeer(member.memberUuid) ?: return@forEach
            runCatching { client.postGroupMembership(peer, snapshot) }
                .onFailure { Log.w(TAG, "membership push to ${member.memberUuid} failed", it) }
        }
    }

    suspend fun broadcastLeave(groupId: String) {
        val selfUuid = groupRepository.selfUuid()
        val targets = groupDao.membersOf(groupId)
        val dto = GroupLeaveDto(groupId = groupId, fromUuid = selfUuid)
        targets.forEach { member ->
            if (member.memberUuid == selfUuid) return@forEach
            val peer = resolvePeer(member.memberUuid) ?: return@forEach
            runCatching { client.postGroupLeave(peer, dto) }
                .onFailure { Log.w(TAG, "leave push to ${member.memberUuid} failed", it) }
        }
    }

    private suspend fun resolvePeer(memberUuid: String): Peer? {
        val live = discoveryRepository.findPeer(memberUuid)
        if (live != null) return live
        val entity = peerDao.findByUuid(memberUuid) ?: return null
        return Peer(
            uuid = entity.uuid,
            nickname = entity.nickname,
            host = entity.lastHost,
            port = entity.lastPort,
        )
    }

    private companion object {
        const val TAG = "GroupBroadcaster"
    }
}
