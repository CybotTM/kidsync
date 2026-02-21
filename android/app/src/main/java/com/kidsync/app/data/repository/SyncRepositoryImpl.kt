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

            // Server returns per-op details with assigned sequences.
            val acceptedByIndex = response.accepted.associateBy { it.index }

            val updatedOps = ops.mapIndexed { index, op ->
                val acceptedOp = acceptedByIndex[index]
                if (acceptedOp != null) {
                    // Find and mark the local entity as synced
                    // Match by deviceSequence which is unique per device, not currentHash (fragile)
                    val pendingEntities = opLogDao.getPendingOps(bucketId)
                    val entity = pendingEntities.firstOrNull { it.deviceSequence == op.deviceSequence }
                    if (entity != null) {
                        opLogDao.markAsSynced(
                            id = entity.id,
                            globalSequence = acceptedOp.globalSequence,
                            serverTimestamp = acceptedOp.serverTimestamp
                        )
                    }
                    op.copy(
                        globalSequence = acceptedOp.globalSequence,
                        serverTimestamp = Instant.parse(acceptedOp.serverTimestamp)
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
            val allOps = mutableListOf<OpLogEntry>()
            var currentAfterSequence = afterSequence
            var hasMore = true

            while (hasMore) {
                val pullResponse = apiService.pullOps(
                    bucketId,
                    since = currentAfterSequence,
                    limit = limit
                )

                val ops = pullResponse.ops.map { dto ->
                    OpLogEntry(
                        globalSequence = dto.globalSequence,
                        bucketId = bucketId,
                        deviceId = dto.deviceId,
                        deviceSequence = 0, // Set to real value in SyncOpsUseCase after decryption
                        keyEpoch = dto.keyEpoch,
                        encryptedPayload = dto.encryptedPayload,
                        devicePrevHash = dto.prevHash,
                        currentHash = dto.currentHash,
                        serverTimestamp = Instant.parse(dto.serverTimestamp)
                    )
                }

                // Store ops locally
                val entities = ops.map { it.toEntity() }
                opLogDao.insertOpLogEntries(entities)

                allOps.addAll(ops)
                hasMore = pullResponse.hasMore

                if (ops.isNotEmpty()) {
                    currentAfterSequence = ops.maxOf { it.globalSequence }
                } else {
                    hasMore = false
                }
            }

            Result.success(allOps)
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
                    timestamp = body.timestamp ?: Instant.now().toString()
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
