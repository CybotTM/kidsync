package com.kidsync.app.domain.repository

import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.model.SyncState
import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface SyncRepository {
    suspend fun pushOps(familyId: UUID, ops: List<OpLogEntry>): Result<List<OpLogEntry>>
    suspend fun pullOps(familyId: UUID, afterSequence: Long, limit: Int = 100): Result<List<OpLogEntry>>
    suspend fun getSyncState(familyId: UUID): SyncState?
    suspend fun updateSyncState(syncState: SyncState)
    suspend fun getCheckpoint(familyId: UUID): Result<ServerCheckpoint>
    fun observeSyncState(familyId: UUID): Flow<SyncState?>
    suspend fun connectWebSocket(familyId: UUID): Flow<WebSocketEvent>
    suspend fun disconnectWebSocket()
}

data class ServerCheckpoint(
    val globalSequence: Long,
    val checkpointHash: String,
    val timestamp: String
)

sealed class WebSocketEvent {
    data class NewOps(val fromSequence: Long) : WebSocketEvent()
    data class KeyRotation(val newEpoch: Int, val revokedDeviceId: UUID) : WebSocketEvent()
    data object Connected : WebSocketEvent()
    data object Disconnected : WebSocketEvent()
    data class Error(val message: String) : WebSocketEvent()
}
