package com.ospchat.shared.domain.groups

import com.ospchat.shared.data.groups.GroupRepository

/**
 * Creator-only operation. Adds [memberUuids] to [groupId] (idempotent on
 * already-present members) and rebroadcasts the updated snapshot.
 */
class AddGroupMembersUseCase(
    private val groupRepository: GroupRepository,
    private val groupBroadcaster: GroupBroadcaster,
) {
    suspend operator fun invoke(
        groupId: String,
        memberUuids: List<String>,
    ) {
        groupRepository.addMembers(groupId = groupId, memberUuids = memberUuids)
        groupBroadcaster.broadcastSnapshot(groupId)
    }
}
