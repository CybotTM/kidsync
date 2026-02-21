package com.kidsync.app.data.repository

import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.data.local.entity.SyncStateEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.OpInputDto
import com.kidsync.app.data.remote.dto.OpsBatchRequest
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.model.SyncState
import com.kidsync.app.domain.repository.ServerCheckpoint
import com.kidsync.app.domain.repository.SyncRepository
import com.kidsync.app.domain.repository.WebSocketEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

class SyncRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val opLogDao: OpLogDao,
    private val syncStateDao: SyncStateDao
) : SyncRepository {

    override suspend fun pushOps(bucketId: String, ops: List<OpLogEntry>): Result<List<OpLogEntry>> {
        return try {
            // Build the minimal OpInput -- NO plaintext metadata
            val request = OpsBatchRequest(
                ops = ops.map { op ->
                    OpInputDto(
                        deviceId = op.deviceId,
                        keyEpoch = op.keyEpoch,
                        encryptedPayload = op.encryptedPayload,
                        prevHash = op.devicePrevHash,
                        currentHash = op.currentHash
                    )
                }
            )

            val response = apiService.uploadOps(bucketId, request)

            // Server returns accepted count and latest sequence.
            // Assign sequences starting from (latestSequence - accepted + 1).
            val now = Instant.now().toString()
            val baseSequence = response.latestSequence - response.accepted + 1

            val updatedOps = ops.mapIndexed { index, op ->
                if (index < response.accepted) {
                    val assignedSequence = baseSequence + index

                    // Find and mark the local entity as synced
                    val pendingEntities = opLogDao.getPendingOps(bucketId)
                    val entity = pendingEntities.firstOrNull { it.currentHash == op.currentHash }
                    if (entity != null) {
                        opLogDao.markAsSynced(
                            id = entity.id,
                            globalSequence = assignedSequence,
                            serverTimestamp = now
                        )
                    }
                    op.copy(
                        globalSequence = assignedSequence,
                        serverTimestamp = Instant.parse(now)
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
        bucketId: String,
        afterSequence: Long,
        limit: Int
    ): Result<List<OpLogEntry>> {
        return try {
            val opsResponse = apiService.pullOps(bucketId, since = afterSequence)

            val ops = opsResponse.map { dto ->
                OpLogEntry(
                    globalSequence = dto.sequence,
                    bucketId = bucketId,
                    deviceId = dto.deviceId,
                    deviceSequence = 0, // Extracted from decrypted payload during apply
                    keyEpoch = dto.keyEpoch,
                    encryptedPayload = dto.encryptedPayload,
                    devicePrevHash = dto.prevHash,
                    currentHash = dto.currentHash,
                    serverTimestamp = Instant.parse(dto.createdAt)
                )
            }

            // Store ops locally
            val entities = ops.map { it.toEntity() }
            opLogDao.insertOpLogEntries(entities)

            Result.success(ops)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getSyncState(bucketId: String): SyncState? {
        return syncStateDao.getSyncState(bucketId)?.toDomain()
    }

    override suspend fun updateSyncState(syncState: SyncState) {
        syncStateDao.upsertSyncState(syncState.toEntity())
    }

    override suspend fun getCheckpoint(bucketId: String): Result<ServerCheckpoint> {
        return try {
            val body = apiService.getCheckpoint(bucketId)

            Result.success(
                ServerCheckpoint(
                    globalSequence = body.endSequence,
                    checkpointHash = body.hash,
                    timestamp = Instant.now().toString()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeSyncState(bucketId: String): Flow<SyncState?> {
        return syncStateDao.observeSyncState(bucketId).map { it?.toDomain() }
    }

    override suspend fun connectWebSocket(bucketId: String): Flow<WebSocketEvent> {
        // WebSocket implementation would use OkHttp WebSocket client
        // connecting to /buckets/{bucketId}/ws
        return emptyFlow()
    }

    override suspend fun disconnectWebSocket() {
        // Close WebSocket connection
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────────

    private fun OpLogEntry.toEntity(): OpLogEntryEntity {
        return OpLogEntryEntity(
            globalSequence = globalSequence,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = deviceSequence,
            keyEpoch = keyEpoch,
            encryptedPayload = encryptedPayload,
            devicePrevHash = devicePrevHash,
            currentHash = currentHash,
            serverTimestamp = serverTimestamp?.toString(),
            isPending = false
        )
    }

    private fun SyncStateEntity.toDomain(): SyncState {
        return SyncState(
            bucketId = bucketId,
            lastGlobalSequence = lastGlobalSequence,
            lastSyncTimestamp = Instant.parse(lastSyncTimestamp),
            serverCheckpointHash = serverCheckpointHash
        )
    }

    private fun SyncState.toEntity(): SyncStateEntity {
        return SyncStateEntity(
            bucketId = bucketId,
            lastGlobalSequence = lastGlobalSequence,
            lastSyncTimestamp = lastSyncTimestamp.toString(),
            serverCheckpointHash = serverCheckpointHash
        )
    }
}
