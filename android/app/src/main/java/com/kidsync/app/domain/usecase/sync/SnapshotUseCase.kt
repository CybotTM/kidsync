package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Creates and restores snapshots of the local database state.
 * Snapshots are signed DeviceSnapshot operations uploaded as part of the oplog.
 */
class SnapshotUseCase @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao,
    private val overrideDao: OverrideDao,
    private val expenseDao: ExpenseDao,
    private val opLogDao: OpLogDao,
    private val syncStateDao: SyncStateDao,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val createOperationUseCase: CreateOperationUseCase
) {
    /**
     * Create a snapshot of the current local state and publish it as a DeviceSnapshot operation.
     */
    suspend fun createSnapshot(bucketId: String): Result<String> {
        return try {
            val syncState = syncStateDao.getSyncState(bucketId)
                ?: return Result.failure(IllegalStateException("No sync state found"))

            val deviceId = keyManager.getDeviceId()
                ?: return Result.failure(IllegalStateException("Device not registered"))

            // Compute state hash from all materialized entities
            val stateHash = computeStateHash()

            val snapshotId = UUID.randomUUID().toString()
            val contentData = buildJsonObject {
                put("snapshotId", JsonPrimitive(snapshotId))
                put("deviceId", JsonPrimitive(deviceId))
                put("bucketId", JsonPrimitive(bucketId))
                put("lastGlobalSequence", JsonPrimitive(syncState.lastGlobalSequence))
                put("stateHash", JsonPrimitive(stateHash))
                put("timestamp", JsonPrimitive(Instant.now().toString()))
            }

            createOperationUseCase(
                bucketId = bucketId,
                entityType = EntityType.DeviceSnapshot,
                entityId = snapshotId,
                operationType = OperationType.CREATE,
                contentData = contentData
            )

            Result.success(stateHash)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compute SHA-256 hash of the current materialized state.
     */
    private suspend fun computeStateHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")

        // Hash all active schedules
        val schedules = custodyScheduleDao.getAllSchedules()
        for (schedule in schedules.sortedBy { it.scheduleId.toString() }) {
            digest.update(schedule.scheduleId.toString().toByteArray())
            digest.update(schedule.status.toByteArray())
        }

        // Hash all overrides
        val overrides = overrideDao.getAllOverrides()
        for (override in overrides.sortedBy { it.overrideId.toString() }) {
            digest.update(override.overrideId.toString().toByteArray())
            digest.update(override.status.toByteArray())
        }

        // Hash all expenses
        val expenses = expenseDao.getAllExpenses()
        for (expense in expenses.sortedBy { it.expenseId.toString() }) {
            digest.update(expense.expenseId.toString().toByteArray())
            digest.update(expense.amountCents.toString().toByteArray())
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
