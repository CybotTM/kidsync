package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
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
    private val canonicalJsonSerializer: CanonicalJsonSerializer,
    private val createOperationUseCase: CreateOperationUseCase
) {
    /**
     * Create a snapshot of the current local state and publish it as a DeviceSnapshot operation.
     */
    suspend fun createSnapshot(familyId: UUID, deviceId: UUID): Result<String> {
        return try {
            val syncState = syncStateDao.getSyncState(familyId)
                ?: return Result.failure(IllegalStateException("No sync state found"))

            // Compute state hash from all materialized entities
            val stateHash = computeStateHash(familyId)

            val snapshotId = UUID.randomUUID()
            val payload = mapOf(
                "payloadType" to "DeviceSnapshot",
                "entityId" to snapshotId.toString(),
                "timestamp" to Instant.now().toString(),
                "operationType" to "CREATE",
                "snapshotId" to snapshotId.toString(),
                "deviceId" to deviceId.toString(),
                "familyId" to familyId.toString(),
                "lastGlobalSequence" to syncState.lastGlobalSequence,
                "stateHash" to stateHash
            )

            createOperationUseCase(
                familyId = familyId,
                deviceId = deviceId,
                entityType = EntityType.CustodySchedule, // snapshot entity type
                entityId = snapshotId,
                operationType = OperationType.CREATE,
                payloadMap = payload
            )

            Result.success(stateHash)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compute SHA-256 hash of the current materialized state.
     */
    private suspend fun computeStateHash(familyId: UUID): String {
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
