package com.kidsync.app.data.repository

import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.data.local.entity.SyncStateEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.OpInputDto
import com.kidsync.app.data.remote.dto.UploadOpsRequest
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.model.SyncState
import com.kidsync.app.domain.repository.ServerCheckpoint
import com.kidsync.app.domain.repository.SyncRepository
import com.kidsync.app.domain.repository.WebSocketEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class SyncRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val opLogDao: OpLogDao,
    private val syncStateDao: SyncStateDao
) : SyncRepository {

    override suspend fun pushOps(familyId: UUID, ops: List<OpLogEntry>): Result<List<OpLogEntry>> {
        return try {
            val request = UploadOpsRequest(
                ops = ops.map { op ->
                    OpInputDto(
                        localId = op.globalSequence.toString(),
                        deviceId = op.deviceId.toString(),
                        encryptedPayload = op.encryptedPayload,
                        devicePrevHash = op.devicePrevHash,
                        keyEpoch = op.keyEpoch
                    )
                }
            )

            val response = apiService.uploadOps(request)
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            // Update local ops with server-assigned sequences
            val updatedOps = ops.mapIndexed { index, op ->
                val assigned = body.assignedSequences.getOrNull(index)
                if (assigned != null) {
                    val entity = opLogDao.getPendingOps(familyId)
                        .firstOrNull { it.currentHash == op.currentHash }
                    if (entity != null) {
                        opLogDao.markAsSynced(
                            id = entity.id,
                            globalSequence = assigned.globalSequence,
                            serverTimestamp = assigned.serverTimestamp
                        )
                    }
                    op.copy(
                        globalSequence = assigned.globalSequence,
                        serverTimestamp = Instant.parse(assigned.serverTimestamp)
                    )
                } else {
                    op
                }
            }

            Result.success(updatedOps)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun pullOps(
        familyId: UUID,
        afterSequence: Long,
        limit: Int
    ): Result<List<OpLogEntry>> {
        return try {
            val response = apiService.pullOps(since = afterSequence, limit = limit)
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            val ops = body.ops.map { dto ->
                OpLogEntry(
                    globalSequence = dto.globalSequence,
                    familyId = familyId,
                    deviceId = UUID.fromString(dto.deviceId),
                    deviceSequence = 0, // Not provided by pull endpoint
                    entityType = EntityType.CustodySchedule, // Determined after decryption
                    entityId = UUID(0, 0), // Determined after decryption
                    operation = OperationType.CREATE, // Determined after decryption
                    keyEpoch = dto.keyEpoch,
                    encryptedPayload = dto.encryptedPayload,
                    devicePrevHash = dto.devicePrevHash,
                    currentHash = "", // Computed locally during verification
                    clientTimestamp = Instant.now(), // Populated after decryption
                    serverTimestamp = Instant.parse(dto.serverTimestamp)
                )
            }

            Result.success(ops)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSyncState(familyId: UUID): SyncState? {
        return syncStateDao.getSyncState(familyId)?.toDomain()
    }

    override suspend fun updateSyncState(syncState: SyncState) {
        syncStateDao.upsertSyncState(syncState.toEntity())
    }

    override suspend fun getCheckpoint(familyId: UUID): Result<ServerCheckpoint> {
        return try {
            val response = apiService.getCheckpoint()
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            Result.success(
                ServerCheckpoint(
                    globalSequence = body.endSequence,
                    checkpointHash = body.hash,
                    timestamp = body.timestamp
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeSyncState(familyId: UUID): Flow<SyncState?> {
        return syncStateDao.observeSyncState(familyId).map { it?.toDomain() }
    }

    override suspend fun connectWebSocket(familyId: UUID): Flow<WebSocketEvent> {
        // WebSocket implementation would use OkHttp WebSocket client
        // Placeholder for now - actual implementation requires OkHttp WebSocket setup
        return emptyFlow()
    }

    override suspend fun disconnectWebSocket() {
        // Close WebSocket connection
    }

    private fun SyncStateEntity.toDomain(): SyncState {
        return SyncState(
            familyId = familyId,
            lastGlobalSequence = lastGlobalSequence,
            lastSyncTimestamp = Instant.parse(lastSyncTimestamp),
            serverCheckpointHash = serverCheckpointHash
        )
    }

    private fun SyncState.toEntity(): SyncStateEntity {
        return SyncStateEntity(
            familyId = familyId,
            lastGlobalSequence = lastGlobalSequence,
            lastSyncTimestamp = lastSyncTimestamp.toString(),
            serverCheckpointHash = serverCheckpointHash
        )
    }
}
