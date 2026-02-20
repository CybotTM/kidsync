package com.kidsync.app.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Sealed hierarchy representing all operation payload types as defined in wire-format.md.
 * Each variant carries only the fields relevant to its operation.
 * Optional fields absent from the input are omitted during canonical serialization.
 */
@Serializable
sealed class OperationPayload {
    abstract val payloadType: String
    abstract val entityId: String
    abstract val timestamp: String
    abstract val operationType: String
}

@Serializable
@SerialName("SetCustodySchedule")
data class SetCustodySchedulePayload(
    override val payloadType: String = "SetCustodySchedule",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val scheduleId: String,
    val childId: String,
    val anchorDate: String,
    val cycleLengthDays: Int,
    val pattern: List<String>,
    val effectiveFrom: String,
    val timeZone: String
) : OperationPayload()

@Serializable
@SerialName("UpsertOverride")
data class UpsertOverridePayload(
    override val payloadType: String = "UpsertOverride",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val overrideId: String,
    val type: String,
    val childId: String,
    val startDate: String,
    val endDate: String,
    val assignedParentId: String,
    val status: String,
    val proposerId: String,
    val responderId: String? = null,
    val note: String? = null
) : OperationPayload()

@Serializable
@SerialName("CreateExpense")
data class CreateExpensePayload(
    override val payloadType: String = "CreateExpense",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val expenseId: String,
    val childId: String,
    val paidByUserId: String,
    val amountCents: Int,
    val currencyCode: String,
    val category: String,
    val description: String,
    val incurredAt: String,
    val payerResponsibilityRatio: Double,
    val receiptBlobId: String? = null,
    val receiptDecryptionKey: String? = null
) : OperationPayload()

@Serializable
@SerialName("UpdateExpenseStatus")
data class UpdateExpenseStatusPayload(
    override val payloadType: String = "UpdateExpenseStatus",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val expenseId: String,
    val status: String,
    val responderId: String,
    val note: String? = null
) : OperationPayload()

@Serializable
@SerialName("CreateEvent")
data class CreateEventPayload(
    override val payloadType: String = "CreateEvent",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val eventId: String,
    val childId: String,
    val title: String,
    val date: String,
    val time: String? = null,
    val location: String? = null,
    val notes: String? = null
) : OperationPayload()

@Serializable
@SerialName("UpdateEvent")
data class UpdateEventPayload(
    override val payloadType: String = "UpdateEvent",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val eventId: String,
    val childId: String,
    val title: String,
    val date: String,
    val time: String? = null,
    val location: String? = null,
    val notes: String? = null
) : OperationPayload()

@Serializable
@SerialName("CancelEvent")
data class CancelEventPayload(
    override val payloadType: String = "CancelEvent",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val eventId: String
) : OperationPayload()

@Serializable
@SerialName("DeviceSnapshot")
data class DeviceSnapshotPayload(
    override val payloadType: String = "DeviceSnapshot",
    override val entityId: String,
    override val timestamp: String,
    override val operationType: String,
    val snapshotId: String,
    val deviceId: String,
    val familyId: String,
    val lastGlobalSequence: Long,
    val stateHash: String
) : OperationPayload()
