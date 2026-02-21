package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.*
import com.kidsync.app.domain.model.*
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Applies decrypted operation payloads to the local database.
 *
 * In the zero-knowledge architecture, the OpApplier receives the already-decrypted
 * [DecryptedPayload] which contains all metadata (entityType, entityId, operation,
 * clientTimestamp) that was previously in plaintext columns.
 */
class OpApplier @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao,
    private val overrideDao: OverrideDao,
    private val expenseDao: ExpenseDao,
    private val infoBankDao: InfoBankDao,
    private val opLogDao: OpLogDao,
    private val conflictResolver: ConflictResolver,
    private val overrideStateMachine: OverrideStateMachine,
    private val json: Json
) {
    data class ApplyResult(
        val conflictResolved: Boolean = false
    )

    suspend fun apply(op: OpLogEntry, decryptedPayload: DecryptedPayload): ApplyResult {
        // Store the op in the local log
        opLogDao.insertOpLogEntry(op.toEntity())

        // Feed to the override state machine for deterministic state tracking
        overrideStateMachine.apply(decryptedPayload)

        // Route by entity type (now extracted from decrypted payload)
        return when (decryptedPayload.entityType) {
            "CustodySchedule" -> applyCustodySchedule(decryptedPayload)
            "ScheduleOverride" -> applyOverride(decryptedPayload)
            "Expense" -> applyExpense(decryptedPayload)
            "ExpenseStatus" -> applyExpenseStatus(decryptedPayload)
            "Event" -> applyEvent(decryptedPayload)
            "InfoBank" -> applyInfoBankEntry(decryptedPayload)
            else -> ApplyResult()
        }
    }

    private suspend fun applyCustodySchedule(
        payload: DecryptedPayload
    ): ApplyResult {
        val data = payload.data
        val scheduleId = payload.entityId
        val childId = data["childId"]!!.jsonPrimitive.content
        val effectiveFrom = Instant.parse(data["effectiveFrom"]!!.jsonPrimitive.content)
        val pattern = data["pattern"]!!.jsonArray.map { it.jsonPrimitive.content }

        val newSchedule = CustodyScheduleEntity(
            scheduleId = scheduleId,
            childId = childId,
            anchorDate = data["anchorDate"]!!.jsonPrimitive.content,
            cycleLengthDays = data["cycleLengthDays"]!!.jsonPrimitive.int,
            patternJson = json.encodeToString(JsonArray.serializer(), JsonArray(pattern.map { JsonPrimitive(it) })),
            effectiveFrom = effectiveFrom.toString(),
            timeZone = data["timeZone"]!!.jsonPrimitive.content,
            status = "ACTIVE",
            clientTimestamp = payload.clientTimestamp
        )

        // Conflict resolution: check for existing schedule with same effectiveFrom for same child
        val existing = custodyScheduleDao.getActiveSchedulesForChild(childId)
        var conflictResolved = false

        for (existingSchedule in existing) {
            if (existingSchedule.scheduleId != scheduleId &&
                existingSchedule.effectiveFrom == newSchedule.effectiveFrom
            ) {
                val winner = conflictResolver.resolveCustodyScheduleConflict(
                    existingSchedule, newSchedule, Instant.parse(payload.clientTimestamp)
                )

                if (winner.scheduleId == newSchedule.scheduleId) {
                    custodyScheduleDao.updateStatus(existingSchedule.scheduleId, "SUPERSEDED")
                } else {
                    custodyScheduleDao.insertSchedule(newSchedule.copy(status = "SUPERSEDED"))
                    return ApplyResult(conflictResolved = true)
                }
                conflictResolved = true
            }
        }

        custodyScheduleDao.insertSchedule(newSchedule)
        return ApplyResult(conflictResolved = conflictResolved)
    }

    private suspend fun applyOverride(
        payload: DecryptedPayload
    ): ApplyResult {
        val data = payload.data
        val overrideId = payload.entityId
        val status = data["status"]?.jsonPrimitive?.content ?: "PROPOSED"

        val entity = ScheduleOverrideEntity(
            overrideId = overrideId,
            type = data["type"]!!.jsonPrimitive.content,
            childId = data["childId"]!!.jsonPrimitive.content,
            startDate = data["startDate"]!!.jsonPrimitive.content,
            endDate = data["endDate"]!!.jsonPrimitive.content,
            assignedParentId = data["assignedParentId"]!!.jsonPrimitive.content,
            status = status,
            proposerId = data["proposerDeviceId"]?.jsonPrimitive?.content
                ?: data["proposerId"]?.jsonPrimitive?.content
                ?: "",
            responderId = data["responderDeviceId"]?.jsonPrimitive?.content
                ?: data["responderId"]?.jsonPrimitive?.content,
            note = data["note"]?.jsonPrimitive?.content,
            clientTimestamp = payload.clientTimestamp
        )

        val existing = overrideDao.getOverrideById(overrideId)
        if (existing != null && payload.operation == "UPDATE") {
            val validTransition = conflictResolver.isValidOverrideTransition(
                OverrideStatus.valueOf(existing.status),
                OverrideStatus.valueOf(status)
            )
            if (!validTransition) {
                return ApplyResult()
            }
            overrideDao.updateOverride(entity)
        } else {
            overrideDao.insertOverride(entity)
        }

        return ApplyResult()
    }

    private suspend fun applyExpense(
        payload: DecryptedPayload
    ): ApplyResult {
        val data = payload.data
        val entity = ExpenseEntity(
            expenseId = payload.entityId,
            childId = data["childId"]!!.jsonPrimitive.content,
            paidByDeviceId = data["paidByDeviceId"]!!.jsonPrimitive.content,
            amountCents = data["amountCents"]!!.jsonPrimitive.int,
            currencyCode = data["currencyCode"]!!.jsonPrimitive.content,
            category = data["category"]!!.jsonPrimitive.content,
            description = data["description"]!!.jsonPrimitive.content,
            incurredAt = data["incurredAt"]!!.jsonPrimitive.content,
            payerResponsibilityRatio = data["payerResponsibilityRatio"]!!.jsonPrimitive.double,
            receiptBlobId = data["receiptBlobId"]?.jsonPrimitive?.content,
            receiptDecryptionKey = data["receiptDecryptionKey"]?.jsonPrimitive?.content,
            clientTimestamp = payload.clientTimestamp
        )

        expenseDao.insertExpense(entity)
        return ApplyResult()
    }

    private suspend fun applyExpenseStatus(
        payload: DecryptedPayload
    ): ApplyResult {
        val data = payload.data
        val entity = ExpenseStatusEntity(
            id = java.util.UUID.randomUUID().toString(),
            expenseId = data["expenseId"]!!.jsonPrimitive.content,
            status = data["status"]!!.jsonPrimitive.content,
            responderId = data["responderId"]!!.jsonPrimitive.content,
            note = data["note"]?.jsonPrimitive?.content,
            clientTimestamp = payload.clientTimestamp
        )

        val existing = expenseDao.getLatestStatusForExpense(entity.expenseId)
        if (existing == null ||
            Instant.parse(entity.clientTimestamp) > Instant.parse(existing.clientTimestamp)
        ) {
            expenseDao.insertExpenseStatus(entity)
        }

        return ApplyResult()
    }

    private suspend fun applyEvent(
        payload: DecryptedPayload
    ): ApplyResult {
        // Events are stored in the oplog; the decrypted payload has all the data.
        // Materialized event view can be derived from the oplog on demand.
        return ApplyResult()
    }

    private suspend fun applyInfoBankEntry(
        payload: DecryptedPayload
    ): ApplyResult {
        if (payload.operation == "DELETE") {
            val entryId = UUID.fromString(payload.entityId)
            infoBankDao.markDeleted(entryId)
            return ApplyResult()
        }

        val data = payload.data
        val entity = InfoBankEntryEntity(
            entryId = UUID.fromString(payload.entityId),
            childId = UUID.fromString(data["childId"]!!.jsonPrimitive.content),
            category = data["category"]!!.jsonPrimitive.content,
            allergies = data["allergies"]?.jsonPrimitive?.content,
            medicationName = data["medicationName"]?.jsonPrimitive?.content,
            medicationDosage = data["medicationDosage"]?.jsonPrimitive?.content,
            medicationSchedule = data["medicationSchedule"]?.jsonPrimitive?.content,
            doctorName = data["doctorName"]?.jsonPrimitive?.content,
            doctorPhone = data["doctorPhone"]?.jsonPrimitive?.content,
            insuranceInfo = data["insuranceInfo"]?.jsonPrimitive?.content,
            bloodType = data["bloodType"]?.jsonPrimitive?.content,
            schoolName = data["schoolName"]?.jsonPrimitive?.content,
            teacherNames = data["teacherNames"]?.jsonPrimitive?.content,
            gradeClass = data["gradeClass"]?.jsonPrimitive?.content,
            schoolPhone = data["schoolPhone"]?.jsonPrimitive?.content,
            scheduleNotes = data["scheduleNotes"]?.jsonPrimitive?.content,
            contactName = data["contactName"]?.jsonPrimitive?.content,
            relationship = data["relationship"]?.jsonPrimitive?.content,
            phone = data["phone"]?.jsonPrimitive?.content,
            email = data["email"]?.jsonPrimitive?.content,
            title = data["title"]?.jsonPrimitive?.content,
            content = data["content"]?.jsonPrimitive?.content,
            tag = data["tag"]?.jsonPrimitive?.content,
            notes = data["notes"]?.jsonPrimitive?.content,
            clientTimestamp = payload.clientTimestamp,
            updatedTimestamp = payload.clientTimestamp
        )

        infoBankDao.insertEntry(entity)
        return ApplyResult()
    }

    suspend fun getPendingOps(bucketId: String): List<OpLogEntry> {
        return opLogDao.getPendingOps(bucketId).map { it.toDomain() }
    }

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

    private fun OpLogEntryEntity.toDomain(): OpLogEntry {
        return OpLogEntry(
            globalSequence = globalSequence,
            bucketId = bucketId,
            deviceId = deviceId,
            deviceSequence = deviceSequence,
            keyEpoch = keyEpoch,
            encryptedPayload = encryptedPayload,
            devicePrevHash = devicePrevHash,
            currentHash = currentHash,
            serverTimestamp = serverTimestamp?.let { Instant.parse(it) }
        )
    }
}
