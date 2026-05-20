package com.ospchat.shared.data.reactions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReactionDao {
    /**
     * Reactions on every message in the conversation with [peerUuid].
     * Joined via the `messages` table so we don't have to re-derive
     * conversation membership from message ids on the client.
     */
    @Query(
        """
        SELECT r.* FROM reactions r
        INNER JOIN messages m ON m.id = r.message_id
        WHERE m.peer_uuid = :peerUuid
        ORDER BY r.reacted_at ASC
        """,
    )
    fun observeForPeer(peerUuid: String): Flow<List<ReactionEntity>>

    /**
     * Reactions on every message in [groupId]. Joined via `group_messages` so
     * the caller doesn't have to pre-resolve message ids. Mirror of
     * [observeForPeer] for the group case.
     */
    @Query(
        """
        SELECT r.* FROM reactions r
        INNER JOIN group_messages gm ON gm.id = r.message_id
        WHERE gm.group_id = :groupId
        ORDER BY r.reacted_at ASC
        """,
    )
    fun observeForGroup(groupId: String): Flow<List<ReactionEntity>>

    /** Snapshot variant of [observeForGroup] for one-shot reads (catch-up sync). */
    @Query(
        """
        SELECT r.* FROM reactions r
        INNER JOIN group_messages gm ON gm.id = r.message_id
        WHERE gm.group_id = :groupId
        ORDER BY r.reacted_at ASC
        """,
    )
    suspend fun snapshotForGroup(groupId: String): List<ReactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ReactionEntity)

    @Query("DELETE FROM reactions WHERE message_id = :messageId AND from_uuid = :fromUuid")
    suspend fun delete(
        messageId: String,
        fromUuid: String,
    )
}
