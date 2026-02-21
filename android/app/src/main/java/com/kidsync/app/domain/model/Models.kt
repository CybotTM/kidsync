package com.kidsync.app.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

// ── Enums ───────────────────────────────────────────────────────────────────

enum class OperationType { CREATE, UPDATE, DELETE }

enum class EntityType {
    CustodySchedule,
    ScheduleOverride,
    Expense,
    ExpenseStatus,
    CalendarEvent,
    InfoBankEntry,
    DeviceSnapshot,
    DeviceRevocation,
    KeyRotation
}

enum class OverrideType {
    MANUAL_OVERRIDE,
    SWAP_REQUEST,
    HOLIDAY_RULE,
    COURT_ORDER;

    val precedence: Int
        get() = when (this) {
            MANUAL_OVERRIDE -> 1
            SWAP_REQUEST -> 2
            HOLIDAY_RULE -> 3
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
    LOGGED,
    ACKNOWLEDGED,
    DISPUTED,
    RESOLVED
}

enum class DeviceStatus {
    ACTIVE,
    REVOKED
}

// ── Core Domain Models ──────────────────────────────────────────────────────

/**
 * An anonymous, opaque storage namespace.
 * The concept of "family" is a client-side abstraction stored in encrypted ops.
 */
data class Bucket(
    val bucketId: String,
    val createdByDeviceId: String,
    val createdAt: Instant
)

/**
 * A device's identity in the zero-knowledge architecture.
 * Devices are identified by their cryptographic keys, not by user accounts.
 */
data class DeviceIdentity(
    val deviceId: String,
    val signingKey: ByteArray,
    val encryptionKey: ByteArray,
    val fingerprint: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceIdentity) return false
        return deviceId == other.deviceId
    }

    override fun hashCode(): Int = deviceId.hashCode()
}

/**
 * Key attestation: a cross-signature proving one device vouches for
 * another device's encryption key. Used for key transparency.
 */
@Serializable
data class KeyAttestation(
    val signerDeviceId: String,
    val attestedDeviceId: String,
    val attestedEncryptionKey: String,
    val signature: String,
    val createdAt: String
)

// ── Business Domain Models ──────────────────────────────────────────────────

data class CustodySchedule(
    val scheduleId: String,
    val childId: String,
    val anchorDate: LocalDate,
    val cycleLengthDays: Int,
    val pattern: List<String>,
    val effectiveFrom: Instant,
    val timeZone: String,
    val status: String = "ACTIVE",
    val createdAt: Instant? = null,
    val clientTimestamp: Instant? = null
)

data class ScheduleOverride(
    val overrideId: String,
    val type: OverrideType,
    val childId: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val assignedParentId: String,
    val status: OverrideStatus,
    val proposerId: String,
    val responderId: String? = null,
    val note: String? = null,
    val createdAt: Instant? = null
)

data class Expense(
    val expenseId: String,
    val childId: String,
    val paidByDeviceId: String,
    val amountCents: Int,
    val currencyCode: String,
    val category: ExpenseCategory,
    val description: String,
    val incurredAt: LocalDate,
    val payerResponsibilityRatio: Double,
    val receiptBlobId: String? = null,
    val receiptBlobKey: String? = null,
    val receiptBlobNonce: String? = null,
    val createdAt: Instant? = null
)

data class ExpenseStatus(
    val id: String,
    val expenseId: String,
    val status: ExpenseStatusType,
    val responderId: String,
    val note: String? = null,
    val clientTimestamp: Instant,
    val createdAt: Instant? = null
)

data class CalendarEvent(
    val eventId: String,
    val childId: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    val location: String? = null,
    val notes: String? = null,
    val cancelled: Boolean = false,
    val createdAt: Instant? = null
)

data class Device(
    val deviceId: String,
    val signingKey: String,
    val encryptionKey: String,
    val createdAt: Instant
)

data class KeyEpoch(
    val epoch: Int,
    val bucketId: String,
    val wrappedDek: String,
    val createdAt: Instant,
    val revokedDeviceId: String? = null
)

/**
 * A decrypted operation log entry from the oplog.
 * The OpLogEntry contains the sync envelope; the DecryptedPayload
 * contains the actual metadata and content.
 */
data class OpLogEntry(
    val globalSequence: Long,
    val bucketId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val serverTimestamp: Instant? = null
)

/**
 * The decrypted contents of an encrypted op payload.
 * ALL metadata that was previously in plaintext columns is now inside here.
 */
@Serializable
data class DecryptedPayload(
    val deviceSequence: Long,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val clientTimestamp: String,
    val protocolVersion: Int,
    val data: JsonObject
)

data class SyncState(
    val bucketId: String,
    val lastGlobalSequence: Long,
    val lastSyncTimestamp: Instant,
    val serverCheckpointHash: String? = null
)

// ── Custody Day Result ──────────────────────────────────────────────────────

data class CustodyDay(
    val date: LocalDate,
    val assignedParentId: String,
    val source: CustodyDaySource,
    val overrideId: String? = null
)

enum class CustodyDaySource {
    BASE_SCHEDULE,
    OVERRIDE
}

// ── Session Models ──────────────────────────────────────────────────────────

/**
 * Device session in the zero-knowledge architecture.
 * No user IDs, no family IDs in the token -- just device identity and session token.
 */
data class DeviceSession(
    val deviceId: String,
    val sessionToken: String,
    val expiresIn: Long
)
