package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.*
import com.kidsync.app.domain.model.*
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * Applies decrypted operation payloads to the local database.
 * Handles conflict resolution per entity type as defined in sync-protocol.md Section 9.
 */
class OpApplier @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao,
    private val overrideDao: OverrideDao,
    private val expenseDao: ExpenseDao,
    private val infoBankDao: InfoBankDao,
    private val opLogDao: OpLogDao,
    private val conflictResolver: ConflictResolver,
    private val json: Json
) {
    data class ApplyResult(
        val conflictResolved: Boolean = false
    )

    suspend fun apply(op: OpLogEntry, decryptedPayload: String): ApplyResult {
        // Store the op in the log
        opLogDao.insertOpLogEntry(op.toEntity())

        // Parse the payload
        val payload = json.parseToJsonElement(decryptedPayload).jsonObject
        val payloadType = payload["payloadType"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing payloadType in payload")

        return when (payloadType) {
            "SetCustodySchedule" -> applyCustodySchedule(op, payload)
            "UpsertOverride" -> applyOverride(op, payload)
            "CreateExpense" -> applyExpense(op, payload)
            "UpdateExpenseStatus" -> applyExpenseStatus(op, payload)
            "CreateEvent" -> applyEvent(op, payload)
            "UpdateEvent" -> applyEvent(op, payload)
            "CancelEvent" -> applyCancelEvent(op, payload)
            "CreateInfoBankEntry" -> applyInfoBankEntry(op, payload)
            "UpdateInfoBankEntry" -> applyInfoBankEntry(op, payload)
            "DeleteInfoBankEntry" -> applyDeleteInfoBankEntry(op, payload)
            else -> ApplyResult()
        }
    }

    private suspend fun applyCustodySchedule(
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        val scheduleId = UUID.fromString(payload["scheduleId"]!!.jsonPrimitive.content)
        val childId = UUID.fromString(payload["childId"]!!.jsonPrimitive.content)
        val effectiveFrom = Instant.parse(payload["effectiveFrom"]!!.jsonPrimitive.content)
        val pattern = payload["pattern"]!!.jsonArray.map { it.jsonPrimitive.content }

        val newSchedule = CustodyScheduleEntity(
            scheduleId = scheduleId,
            childId = childId,
            anchorDate = payload["anchorDate"]!!.jsonPrimitive.content,
            cycleLengthDays = payload["cycleLengthDays"]!!.jsonPrimitive.int,
            patternJson = json.encodeToString(JsonArray.serializer(), JsonArray(pattern.map { JsonPrimitive(it) })),
            effectiveFrom = effectiveFrom.toString(),
            timeZone = payload["timeZone"]!!.jsonPrimitive.content,
            status = "ACTIVE",
            clientTimestamp = op.clientTimestamp.toString()
        )

        // Conflict resolution: check for existing schedule with same effectiveFrom for same child
        val existing = custodyScheduleDao.getActiveSchedulesForChild(childId)
        var conflictResolved = false

        for (existingSchedule in existing) {
            if (existingSchedule.scheduleId != scheduleId &&
                existingSchedule.effectiveFrom == newSchedule.effectiveFrom
            ) {
                // Same effectiveFrom - conflict resolution needed
                val winner = conflictResolver.resolveCustodyScheduleConflict(
                    existingSchedule, newSchedule, op.clientTimestamp
                )

                if (winner.scheduleId == newSchedule.scheduleId) {
                    // New schedule wins - supersede existing
                    custodyScheduleDao.updateStatus(existingSchedule.scheduleId, "SUPERSEDED")
                } else {
                    // Existing wins - mark new as superseded
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
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        val overrideId = UUID.fromString(payload["overrideId"]!!.jsonPrimitive.content)
        val status = payload["status"]!!.jsonPrimitive.content

        val entity = ScheduleOverrideEntity(
            overrideId = overrideId,
            type = payload["type"]!!.jsonPrimitive.content,
            childId = UUID.fromString(payload["childId"]!!.jsonPrimitive.content),
            startDate = payload["startDate"]!!.jsonPrimitive.content,
            endDate = payload["endDate"]!!.jsonPrimitive.content,
            assignedParentId = UUID.fromString(payload["assignedParentId"]!!.jsonPrimitive.content),
            status = status,
            proposerId = UUID.fromString(payload["proposerId"]!!.jsonPrimitive.content),
            responderId = payload["responderId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) },
            note = payload["note"]?.jsonPrimitive?.content,
            clientTimestamp = op.clientTimestamp.toString()
        )

        val existing = overrideDao.getOverrideById(overrideId)
        if (existing != null && op.operationType == OperationType.UPDATE) {
            // Validate state transition
            val validTransition = conflictResolver.isValidOverrideTransition(
                OverrideStatus.valueOf(existing.status),
                OverrideStatus.valueOf(status)
            )
            if (!validTransition) {
                // Invalid transition - skip this op
                return ApplyResult()
            }
            overrideDao.updateOverride(entity)
        } else {
            overrideDao.insertOverride(entity)
        }

        return ApplyResult()
    }

    private suspend fun applyExpense(
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        val entity = ExpenseEntity(
            expenseId = UUID.fromString(payload["expenseId"]!!.jsonPrimitive.content),
            childId = UUID.fromString(payload["childId"]!!.jsonPrimitive.content),
            paidByUserId = UUID.fromString(payload["paidByUserId"]!!.jsonPrimitive.content),
            amountCents = payload["amountCents"]!!.jsonPrimitive.int,
            currencyCode = payload["currencyCode"]!!.jsonPrimitive.content,
            category = payload["category"]!!.jsonPrimitive.content,
            description = payload["description"]!!.jsonPrimitive.content,
            incurredAt = payload["incurredAt"]!!.jsonPrimitive.content,
            payerResponsibilityRatio = payload["payerResponsibilityRatio"]!!.jsonPrimitive.double,
            receiptBlobId = payload["receiptBlobId"]?.jsonPrimitive?.content?.let { UUID.fromString(it) },
            receiptDecryptionKey = payload["receiptDecryptionKey"]?.jsonPrimitive?.content,
            clientTimestamp = op.clientTimestamp.toString()
        )

        // Expenses are append-only (no conflict resolution)
        expenseDao.insertExpense(entity)
        return ApplyResult()
    }

    private suspend fun applyExpenseStatus(
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        val entity = ExpenseStatusEntity(
            id = UUID.randomUUID(),
            expenseId = UUID.fromString(payload["expenseId"]!!.jsonPrimitive.content),
            status = payload["status"]!!.jsonPrimitive.content,
            responderId = UUID.fromString(payload["responderId"]!!.jsonPrimitive.content),
            note = payload["note"]?.jsonPrimitive?.content,
            clientTimestamp = op.clientTimestamp.toString()
        )

        // Last-write-wins by clientTimestamp
        val existing = expenseDao.getLatestStatusForExpense(entity.expenseId)
        if (existing == null ||
            Instant.parse(entity.clientTimestamp) > Instant.parse(existing.clientTimestamp)
        ) {
            expenseDao.insertExpenseStatus(entity)
        }

        return ApplyResult()
    }

    private suspend fun applyEvent(
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        // Events are stored in the oplog but not yet materialized to a separate table
        // in this phase. The oplog entry itself serves as the event record.
        return ApplyResult()
    }

    private suspend fun applyCancelEvent(
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        // CancelEvent marks an event as cancelled in the oplog
        return ApplyResult()
    }

    private suspend fun applyInfoBankEntry(
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        val entity = InfoBankEntryEntity(
            entryId = UUID.fromString(payload["entryId"]!!.jsonPrimitive.content),
            childId = UUID.fromString(payload["childId"]!!.jsonPrimitive.content),
            category = payload["category"]!!.jsonPrimitive.content,
            allergies = payload["allergies"]?.jsonPrimitive?.content,
            medicationName = payload["medicationName"]?.jsonPrimitive?.content,
            medicationDosage = payload["medicationDosage"]?.jsonPrimitive?.content,
            medicationSchedule = payload["medicationSchedule"]?.jsonPrimitive?.content,
            doctorName = payload["doctorName"]?.jsonPrimitive?.content,
            doctorPhone = payload["doctorPhone"]?.jsonPrimitive?.content,
            insuranceInfo = payload["insuranceInfo"]?.jsonPrimitive?.content,
            bloodType = payload["bloodType"]?.jsonPrimitive?.content,
            schoolName = payload["schoolName"]?.jsonPrimitive?.content,
            teacherNames = payload["teacherNames"]?.jsonPrimitive?.content,
            gradeClass = payload["gradeClass"]?.jsonPrimitive?.content,
            schoolPhone = payload["schoolPhone"]?.jsonPrimitive?.content,
            scheduleNotes = payload["scheduleNotes"]?.jsonPrimitive?.content,
            contactName = payload["contactName"]?.jsonPrimitive?.content,
            relationship = payload["relationship"]?.jsonPrimitive?.content,
            phone = payload["phone"]?.jsonPrimitive?.content,
            email = payload["email"]?.jsonPrimitive?.content,
            title = payload["title"]?.jsonPrimitive?.content,
            content = payload["content"]?.jsonPrimitive?.content,
            tag = payload["tag"]?.jsonPrimitive?.content,
            notes = payload["notes"]?.jsonPrimitive?.content,
            clientTimestamp = op.clientTimestamp.toString(),
            updatedTimestamp = op.clientTimestamp.toString()
        )

        // Last-write-wins via REPLACE
        infoBankDao.insertEntry(entity)
        return ApplyResult()
    }

    private suspend fun applyDeleteInfoBankEntry(
        op: OpLogEntry,
        payload: JsonObject
    ): ApplyResult {
        val entryId = UUID.fromString(payload["entryId"]!!.jsonPrimitive.content)
        infoBankDao.markDeleted(entryId)
        return ApplyResult()
    }

    suspend fun getPendingOps(familyId: UUID): List<OpLogEntry> {
        return opLogDao.getPendingOps(familyId).map { it.toDomain() }
    }

    private fun OpLogEntry.toEntity(): OpLogEntryEntity {
        return OpLogEntryEntity(
            globalSequence = globalSequence,
            familyId = familyId,
            deviceId = deviceId,
            deviceSequence = deviceSequence,
            entityType = entityType.name,
            entityId = entityId,
            operation = operation.name,
            keyEpoch = keyEpoch,
            encryptedPayload = encryptedPayload,
            devicePrevHash = devicePrevHash,
            currentHash = currentHash,
            clientTimestamp = clientTimestamp.toString(),
            serverTimestamp = serverTimestamp?.toString(),
            transitionTo = transitionTo,
            isPending = false
        )
    }

    private fun OpLogEntryEntity.toDomain(): OpLogEntry {
        return OpLogEntry(
            globalSequence = globalSequence,
            familyId = familyId,
            deviceId = deviceId,
            deviceSequence = deviceSequence,
            entityType = EntityType.valueOf(entityType),
            entityId = entityId,
            operation = OperationType.valueOf(operation),
            keyEpoch = keyEpoch,
            encryptedPayload = encryptedPayload,
            devicePrevHash = devicePrevHash,
            currentHash = currentHash,
            clientTimestamp = Instant.parse(clientTimestamp),
            serverTimestamp = serverTimestamp?.let { Instant.parse(it) },
            transitionTo = transitionTo
        )
    }
}
