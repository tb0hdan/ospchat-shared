package com.ospchat.shared.data.groups

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupMessageDao {
    @Query("SELECT * FROM group_messages WHERE group_id = :groupId ORDER BY sent_at ASC")
    fun observeByGroup(groupId: String): Flow<List<GroupMessageEntity>>

    @Query("SELECT MAX(sent_at) FROM group_messages WHERE group_id = :groupId")
    suspend fun latestSentAt(groupId: String): Long?

    /**
     * Oldest [limit] messages strictly newer than [after]. ORDER BY ASC +
     * LIMIT is the natural pagination shape for cursor-forward sync: the
     * receiver advances its cursor to the max sent_at it sees and the next
     * sync picks up the next batch. See docs/SECURITY.md D5.
     */
    @Query(
        "SELECT * FROM group_messages WHERE group_id = :groupId AND sent_at > :after " +
            "ORDER BY sent_at ASC LIMIT :limit",
    )
    suspend fun messagesAfter(
        groupId: String,
        after: Long,
        limit: Int,
    ): List<GroupMessageEntity>

    @Query("SELECT * FROM group_messages WHERE id = :id")
    suspend fun findById(id: String): GroupMessageEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: GroupMessageEntity)

    @Query("UPDATE group_messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(
        id: String,
        status: String,
    )

    @Query("DELETE FROM group_messages WHERE group_id = :groupId")
    suspend fun deleteByGroup(groupId: String)
}
