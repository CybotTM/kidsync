package com.kidsync.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// ─── Enums ───────────────────────────────────────────────────────────────────

enum class OperationType { CREATE, UPDATE, DELETE }

enum class EntityType {
    CustodySchedule,
    ScheduleOverride,
    Expense,
    ExpenseStatus,
    Event,
    InfoBank
}

enum class OverrideType {
    MANUAL,
    SWAP_REQUEST,
    HOLIDAY,
    COURT_ORDER;

    val precedence: Int
        get() = when (this) {
            MANUAL -> 1
            SWAP_REQUEST -> 2
            HOLIDAY -> 3
            COURT_ORDER -> 4
        }
}

enum class OverrideStatus {
    PROPOSED,
    APPROVED,
    DECLINED,
    CANCELLED,
    SUPERSEDED,
    EXPIRED;

    val isTerminal: Boolean
        get() = this in setOf(DECLINED, CANCELLED, SUPERSEDED, EXPIRED)
}

enum class ExpenseCategory {
    MEDICAL,
    EDUCATION,
    CLOTHING,
    ACTIVITIES,
    TRANSPORT,
    CHILDCARE,
    OTHER
}

enum class ExpenseStatusType {
    PENDING,
    ACKNOWLEDGED,
    DISPUTED,
    RESOLVED
}

enum class DeviceStatus {
    ACTIVE,
    REVOKED
}

// ─── Domain Models ───────────────────────────────────────────────────────────

data class Family(
    val familyId: UUID,
    val name: String,
    val isSolo: Boolean = false,
    val createdAt: Instant
)

data class FamilyMember(
    val id: UUID,
    val familyId: UUID,
    val userId: UUID,
    val displayName: String,
    val role: String,
    val joinedAt: Instant
)

data class CustodySchedule(
    val scheduleId: UUID,
    val childId: UUID,
    val anchorDate: LocalDate,
    val cycleLengthDays: Int,
    val pattern: List<UUID>,
    val effectiveFrom: Instant,
    val timeZone: String,
    val status: String = "ACTIVE",
    val createdAt: Instant? = null,
    val clientTimestamp: Instant? = null
)

data class ScheduleOverride(
    val overrideId: UUID,
    val type: OverrideType,
    val childId: UUID,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val assignedParentId: UUID,
    val status: OverrideStatus,
    val proposerId: UUID,
    val responderId: UUID? = null,
    val note: String? = null,
    val createdAt: Instant? = null
)

data class Expense(
    val expenseId: UUID,
    val childId: UUID,
    val paidByUserId: UUID,
    val amountCents: Int,
    val currencyCode: String,
    val category: ExpenseCategory,
    val description: String,
    val incurredAt: LocalDate,
    val payerResponsibilityRatio: Double,
    val receiptBlobId: UUID? = null,
    val receiptDecryptionKey: String? = null,
    val createdAt: Instant? = null
)

data class ExpenseStatus(
    val id: UUID,
    val expenseId: UUID,
    val status: ExpenseStatusType,
    val responderId: UUID,
    val note: String? = null,
    val clientTimestamp: Instant,
    val createdAt: Instant? = null
)

data class CalendarEvent(
    val eventId: UUID,
    val childId: UUID,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    val location: String? = null,
    val notes: String? = null,
    val cancelled: Boolean = false,
    val createdAt: Instant? = null
)

data class Device(
    val deviceId: UUID,
    val userId: UUID,
    val name: String,
    val publicKey: String,
    val status: DeviceStatus,
    val registeredAt: Instant,
    val revokedAt: Instant? = null
)

data class KeyEpoch(
    val epoch: Int,
    val familyId: UUID,
    val wrappedDek: String,
    val createdAt: Instant,
    val revokedDeviceId: UUID? = null
)

data class OpLogEntry(
    val globalSequence: Long,
    val familyId: UUID,
    val deviceId: UUID,
    val deviceSequence: Long,
    val entityType: EntityType,
    val entityId: UUID,
    val operation: OperationType,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val clientTimestamp: Instant,
    val serverTimestamp: Instant? = null,
    val transitionTo: String? = null
)

data class SyncState(
    val familyId: UUID,
    val lastGlobalSequence: Long,
    val lastSyncTimestamp: Instant,
    val serverCheckpointHash: String? = null
)

// ─── Custody Day Result ──────────────────────────────────────────────────────

data class CustodyDay(
    val date: LocalDate,
    val assignedParentId: UUID,
    val source: CustodyDaySource,
    val overrideId: UUID? = null
)

enum class CustodyDaySource {
    BASE_SCHEDULE,
    OVERRIDE
}

// ─── Auth Models ─────────────────────────────────────────────────────────────

data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

data class UserSession(
    val userId: UUID,
    val familyId: UUID,
    val deviceId: UUID,
    val tokens: AuthTokens
)
