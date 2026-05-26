package com.ospchat.shared.data.peers

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY last_seen_at DESC")
    fun observeAll(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE uuid = :uuid")
    suspend fun findByUuid(uuid: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerEntity)

    @Query("UPDATE peers SET last_read_at = :lastReadAt WHERE uuid = :uuid")
    suspend fun updateLastReadAt(
        uuid: String,
        lastReadAt: Long,
    )

    @Query(
        "UPDATE peers SET avatar_hash = :hash, avatar_local_path = :localPath WHERE uuid = :uuid",
    )
    suspend fun updateAvatar(
        uuid: String,
        hash: String?,
        localPath: String?,
    )

    @Query("UPDATE peers SET is_contact = :isContact WHERE uuid = :uuid")
    suspend fun setIsContact(
        uuid: String,
        isContact: Boolean,
    )

    /**
     * Phase 2b multi-network bridging — read every known peer's pinned
     * pubkey for warm-starting the in-memory pin map at boot. Filter
     * non-null in SQL to keep the result set tight; legacy rows
     * (pre-phase-2a sightings) have null `pub_key` and contribute nothing
     * to the pin map.
     */
    @Query("SELECT uuid, pub_key FROM peers WHERE pub_key IS NOT NULL")
    suspend fun loadPinnedPubkeys(): List<PeerPubkeyRow>

    /**
     * Persist a TOFU pubkey pin. Only writes if the existing row has
     * `pub_key IS NULL` (first-sight pin); a row whose pub_key is
     * already set keeps the original. Phase 2b — see `docs/SECURITY.md`
     * F9 residual.
     */
    @Query("UPDATE peers SET pub_key = :pubKey WHERE uuid = :uuid AND pub_key IS NULL")
    suspend fun pinPubkey(
        uuid: String,
        pubKey: String,
    )

    /**
     * Phase 4 multi-network bridging cleanup — delete a peer row by UUID.
     * Used at startup to scrub a self-leaked phantom row (an earlier bug
     * where the consumer's gossip-collector wrote a row for the local
     * user's own UUID before the self-filter was in place). Safe to call
     * with a uuid that doesn't exist.
     */
    @Query("DELETE FROM peers WHERE uuid = :uuid")
    suspend fun deleteByUuid(uuid: String)
}

/** Projection for [PeerDao.loadPinnedPubkeys]. */
data class PeerPubkeyRow(
    val uuid: String,
    @androidx.room.ColumnInfo(name = "pub_key") val pubKey: String,
)
