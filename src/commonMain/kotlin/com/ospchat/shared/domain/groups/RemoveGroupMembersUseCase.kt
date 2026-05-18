package com.ospchat.shared.domain.groups

import com.ospchat.shared.data.groups.GroupRepository

/**
 * Creator-only operation. Removes [memberUuids] from [groupId] and pushes
 * the updated snapshot to the (now-reduced) member set so they learn about
 * the change. The removed members themselves do not receive the push;
 * they discover their removal on next catch-up sync.
 */
class RemoveGroupMembersUseCase(
    private val groupRepository: GroupRepository,
    private val groupBroadcaster: GroupBroadcaster,
) {
    suspend operator fun invoke(
        groupId: String,
        memberUuids: List<String>,
    ) {
        groupRepository.removeMembers(groupId = groupId, memberUuids = memberUuids)
        groupBroadcaster.broadcastSnapshot(groupId)
    }
}
