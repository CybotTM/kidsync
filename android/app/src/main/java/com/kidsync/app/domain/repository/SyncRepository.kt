package com.kidsync.app.domain.repository

import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.model.SyncState
import kotlinx.coroutines.flow.Flow

interface SyncRepository {
    suspend fun pushOps(bucketId: String, ops: List<OpLogEntry>): Result<List<OpLogEntry>>
    suspend fun pullOps(bucketId: String, afterSequence: Long, limit: Int = 100): Result<List<OpLogEntry>>
    suspend fun getSyncState(bucketId: String): SyncState?
    suspend fun updateSyncState(syncState: SyncState)
    suspend fun getCheckpoint(bucketId: String): Result<ServerCheckpoint>
    fun observeSyncState(bucketId: String): Flow<SyncState?>
    suspend fun connectWebSocket(bucketId: String): Flow<WebSocketEvent>
    suspend fun disconnectWebSocket()
}

data class ServerCheckpoint(
    val globalSequence: Long,
    val checkpointHash: String,
    val timestamp: String
)

sealed class WebSocketEvent {
    data class NewOps(val fromSequence: Long) : WebSocketEvent()
    data class KeyRotation(val newEpoch: Int, val revokedDeviceId: String) : WebSocketEvent()
    data object Connected : WebSocketEvent()
    data object Disconnected : WebSocketEvent()
    data class Error(val message: String) : WebSocketEvent()
}
