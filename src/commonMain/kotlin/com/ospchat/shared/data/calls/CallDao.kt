package com.ospchat.shared.data.calls

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CallEntity)

    @Query("SELECT * FROM calls WHERE id = :id")
    suspend fun findById(id: String): CallEntity?

    /**
     * Live active call (anything not yet `ENDED`). Phase 1 guarantees at most
     * one such row exists at a time. Returns `null` when the user is idle.
     */
    @Query("SELECT * FROM calls WHERE state != 'ENDED' ORDER BY started_at DESC LIMIT 1")
    fun observeActive(): Flow<CallEntity?>
}
