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
                        deviceSequence = op.deviceSequence,
                        entityType = op.entityType.name,
                        entityId = op.entityId.toString(),
                        operation = op.operation.name,
                        encryptedPayload = op.encryptedPayload,
                        devicePrevHash = op.devicePrevHash,
                        currentHash = op.currentHash,
                        keyEpoch = op.keyEpoch,
                        clientTimestamp = op.clientTimestamp.toString(),
                        protocolVersion = 1,
                        transitionTo = op.transitionTo,
                        localId = op.globalSequence.toString()
                    )
                }
            )

            val response = apiService.uploadOps(request)
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            // Build lookup of accepted ops by deviceSequence
            val acceptedBySeq = body.accepted.associateBy { it.deviceSequence }

            // Update local ops with server-assigned sequences
            val updatedOps = ops.map { op ->
                val accepted = acceptedBySeq[op.deviceSequence]
                if (accepted != null) {
                    val entity = opLogDao.getPendingOps(familyId)
                        .firstOrNull { it.currentHash == op.currentHash }
                    if (entity != null) {
                        opLogDao.markAsSynced(
                            id = entity.id,
                            globalSequence = accepted.globalSequence,
                            serverTimestamp = accepted.serverTimestamp
                        )
                    }
                    op.copy(
                        globalSequence = accepted.globalSequence,
                        serverTimestamp = Instant.parse(accepted.serverTimestamp)
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
                    deviceSequence = dto.deviceSequence,
                    entityType = dto.entityType?.let { runCatching { EntityType.valueOf(it) }.getOrNull() }
                        ?: EntityType.CustodySchedule,
                    entityId = dto.entityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        ?: UUID(0, 0),
                    operation = dto.operation?.let { runCatching { OperationType.valueOf(it) }.getOrNull() }
                        ?: OperationType.CREATE,
                    keyEpoch = dto.keyEpoch,
                    encryptedPayload = dto.encryptedPayload,
                    devicePrevHash = dto.devicePrevHash,
                    currentHash = dto.currentHash ?: "",
                    clientTimestamp = dto.clientTimestamp?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        ?: Instant.now(),
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
