package com.ospchat.shared.data.calls

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calls",
    indices = [Index(value = ["peer_uuid", "started_at"])],
)
data class CallEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "peer_uuid") val peerUuid: String,
    @ColumnInfo(name = "peer_nickname") val peerNickname: String,
    @ColumnInfo(name = "direction") val direction: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "connected_at") val connectedAt: Long? = null,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    @ColumnInfo(name = "end_reason") val endReason: String? = null,
)

internal fun CallEntity.toDomain(): Call =
    Call(
        id = id,
        peerUuid = peerUuid,
        peerNickname = peerNickname,
        direction = Call.Direction.valueOf(direction),
        state = runCatching { Call.State.valueOf(state) }.getOrDefault(Call.State.ENDED),
        startedAt = startedAt,
        connectedAt = connectedAt,
        endedAt = endedAt,
        endReason = endReason?.let { runCatching { Call.EndReason.valueOf(it) }.getOrNull() },
    )

internal fun Call.toEntity(): CallEntity =
    CallEntity(
        id = id,
        peerUuid = peerUuid,
        peerNickname = peerNickname,
        direction = direction.name,
        state = state.name,
        startedAt = startedAt,
        connectedAt = connectedAt,
        endedAt = endedAt,
        endReason = endReason?.name,
    )
