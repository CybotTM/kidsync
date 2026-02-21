package com.kidsync.app.sync

import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.*
import com.kidsync.app.domain.model.DecryptedPayload
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import com.kidsync.app.domain.usecase.sync.OpApplier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Tests for OpApplier covering:
 * - CustodySchedule CREATE applies correctly
 * - CustodySchedule with conflict resolution
 * - ScheduleOverride CREATE/UPDATE lifecycle
 * - Expense CREATE
 * - ExpenseStatus with LWW (last-writer-wins)
 * - CalendarEvent CREATE/DELETE
 * - InfoBankEntry CREATE/UPDATE/DELETE
 * - Unknown entity type handled gracefully
 */
class OpApplierTest : FunSpec({

    val custodyScheduleDao = mockk<CustodyScheduleDao>()
    val overrideDao = mockk<OverrideDao>()
    val expenseDao = mockk<ExpenseDao>()
    val infoBankDao = mockk<InfoBankDao>()
    val calendarEventDao = mockk<CalendarEventDao>()
    val opLogDao = mockk<OpLogDao>()
    val overrideStateMachine = mockk<OverrideStateMachine>(relaxed = true)
    val conflictResolver = mockk<ConflictResolver>()
    val json = Json { ignoreUnknownKeys = true }

    val bucketId = "bucket-test"
    val deviceId = "device-test"

    fun createOpApplier() = OpApplier(
        custodyScheduleDao = custodyScheduleDao,
        overrideDao = overrideDao,
        expenseDao = expenseDao,
        infoBankDao = infoBankDao,
        calendarEventDao = calendarEventDao,
        opLogDao = opLogDao,
        conflictResolver = conflictResolver,
        overrideStateMachine = overrideStateMachine,
        json = json
    )

    fun opLogEntry(seq: Long = 1) = OpLogEntry(
        globalSequence = seq,
        bucketId = bucketId,
        deviceId = deviceId,
        deviceSequence = seq,
        keyEpoch = 1,
        encryptedPayload = "enc",
        devicePrevHash = "prev",
        currentHash = "curr",
        serverTimestamp = Instant.now()
    )

    beforeEach {
        clearAllMocks()
        coEvery { opLogDao.insertOpLogEntry(any()) } just Runs
    }

    // ── CustodySchedule CREATE ──────────────────────────────────────────────

    test("CustodySchedule CREATE inserts schedule") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "CustodySchedule",
            entityId = "sched-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("anchorDate", "2026-04-01")
                put("cycleLengthDays", 14)
                putJsonArray("pattern") {
                    add("parentA")
                    add("parentB")
                }
                put("effectiveFrom", "2026-04-01T00:00:00.000Z")
                put("timeZone", "America/New_York")
            }
        )

        coEvery { custodyScheduleDao.getActiveSchedulesForChild("child-001") } returns emptyList()
        coEvery { custodyScheduleDao.insertSchedule(any()) } just Runs

        val applier = createOpApplier()
        val result = applier.apply(opLogEntry(), payload)

        result.conflictResolved shouldBe false
        coVerify { custodyScheduleDao.insertSchedule(match { it.scheduleId == "sched-001" }) }
    }

    // ── CustodySchedule with Conflict ───────────────────────────────────────

    test("CustodySchedule CREATE with conflict resolution - incoming wins") {
        // Note: Instant.parse("...").toString() truncates trailing zeros,
        // so effectiveFrom must match the Instant.toString() format for string comparison
        val existingSchedule = CustodyScheduleEntity(
            scheduleId = "sched-old",
            childId = "child-001",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00Z",
            timeZone = "UTC",
            clientTimestamp = "2026-03-28T10:00:00.000Z"
        )

        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "CustodySchedule",
            entityId = "sched-new",
            operation = "CREATE",
            clientTimestamp = "2026-03-28T12:00:00.000Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("anchorDate", "2026-04-01")
                put("cycleLengthDays", 14)
                putJsonArray("pattern") { add("parentA") }
                put("effectiveFrom", "2026-04-01T00:00:00.000Z")
                put("timeZone", "UTC")
            }
        )

        coEvery { custodyScheduleDao.getActiveSchedulesForChild("child-001") } returns listOf(existingSchedule)
        coEvery { conflictResolver.resolveCustodyScheduleConflict(any(), any(), any()) } answers {
            secondArg() // incoming wins
        }
        coEvery { custodyScheduleDao.updateStatus("sched-old", "SUPERSEDED") } just Runs
        coEvery { custodyScheduleDao.insertSchedule(any()) } just Runs

        val applier = createOpApplier()
        val result = applier.apply(opLogEntry(), payload)

        result.conflictResolved shouldBe true
        coVerify { custodyScheduleDao.updateStatus("sched-old", "SUPERSEDED") }
    }

    // ── ScheduleOverride CREATE ─────────────────────────────────────────────

    test("ScheduleOverride CREATE inserts override") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "ScheduleOverride",
            entityId = "ovr-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "SWAP_REQUEST")
                put("childId", "child-001")
                put("startDate", "2026-04-05")
                put("endDate", "2026-04-05")
                put("assignedParentId", "parentA")
                put("status", "PROPOSED")
                put("proposerDeviceId", "device-proposer")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-001") } returns null
        coEvery { overrideDao.insertOverride(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify { overrideDao.insertOverride(match { it.overrideId == "ovr-001" && it.status == "PROPOSED" }) }
    }

    // ── ScheduleOverride UPDATE ─────────────────────────────────────────────

    test("ScheduleOverride UPDATE with valid transition updates override") {
        val existing = ScheduleOverrideEntity(
            overrideId = "ovr-001",
            type = "SWAP_REQUEST",
            childId = "child-001",
            startDate = "2026-04-05",
            endDate = "2026-04-05",
            assignedParentId = "parentA",
            status = "PROPOSED",
            proposerId = "device-proposer"
        )

        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "ScheduleOverride",
            entityId = "ovr-001",
            operation = "UPDATE",
            clientTimestamp = "2026-04-01T11:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "SWAP_REQUEST")
                put("childId", "child-001")
                put("startDate", "2026-04-05")
                put("endDate", "2026-04-05")
                put("assignedParentId", "parentA")
                put("status", "APPROVED")
                put("responderDeviceId", "device-other")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-001") } returns existing
        every { conflictResolver.isValidOverrideTransition(any(), any()) } returns true
        coEvery { overrideDao.updateOverride(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify { overrideDao.updateOverride(match { it.status == "APPROVED" }) }
    }

    test("ScheduleOverride UPDATE with invalid transition is rejected") {
        val existing = ScheduleOverrideEntity(
            overrideId = "ovr-001",
            type = "SWAP_REQUEST",
            childId = "child-001",
            startDate = "2026-04-05",
            endDate = "2026-04-05",
            assignedParentId = "parentA",
            status = "DECLINED",
            proposerId = "device-proposer"
        )

        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "ScheduleOverride",
            entityId = "ovr-001",
            operation = "UPDATE",
            clientTimestamp = "2026-04-01T11:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "SWAP_REQUEST")
                put("childId", "child-001")
                put("startDate", "2026-04-05")
                put("endDate", "2026-04-05")
                put("assignedParentId", "parentA")
                put("status", "APPROVED")
                put("responderDeviceId", "device-other")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-001") } returns existing
        every { conflictResolver.isValidOverrideTransition(any(), any()) } returns false

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify(exactly = 0) { overrideDao.updateOverride(any()) }
    }

    // ── Expense CREATE ──────────────────────────────────────────────────────

    test("Expense CREATE inserts expense with all fields") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "Expense",
            entityId = "exp-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("paidByDeviceId", "device-payer")
                put("amountCents", 15099)
                put("currencyCode", "USD")
                put("category", "MEDICAL")
                put("description", "Doctor visit")
                put("incurredAt", "2026-03-25")
                put("payerResponsibilityRatio", 0.6)
                put("receiptBlobId", "blob-001")
                put("receiptBlobKey", "key-001")
            }
        )

        coEvery { expenseDao.insertExpense(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            expenseDao.insertExpense(match {
                it.expenseId == "exp-001" &&
                it.amountCents == 15099 &&
                it.category == "MEDICAL" &&
                it.receiptBlobId == "blob-001"
            })
        }
    }

    // ── ExpenseStatus with LWW ──────────────────────────────────────────────

    test("ExpenseStatus insert when no existing status") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "ExpenseStatus",
            entityId = "exp-status-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T12:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("expenseId", "exp-001")
                put("status", "ACKNOWLEDGED")
                put("responderDeviceId", "device-responder")
            }
        )

        coEvery { expenseDao.getLatestStatusForExpense("exp-001") } returns null
        coEvery { expenseDao.insertExpenseStatus(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify { expenseDao.insertExpenseStatus(match { it.status == "ACKNOWLEDGED" }) }
    }

    test("ExpenseStatus LWW: later timestamp replaces earlier status") {
        val existingStatus = ExpenseStatusEntity(
            id = "status-old",
            expenseId = "exp-001",
            status = "LOGGED",
            responderId = "device-a",
            clientTimestamp = "2026-04-01T10:00:00Z"
        )

        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "ExpenseStatus",
            entityId = "exp-status-002",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T12:00:00Z", // later timestamp
            protocolVersion = 2,
            data = buildJsonObject {
                put("expenseId", "exp-001")
                put("status", "ACKNOWLEDGED")
                put("responderDeviceId", "device-b")
            }
        )

        coEvery { expenseDao.getLatestStatusForExpense("exp-001") } returns existingStatus
        coEvery { expenseDao.insertExpenseStatus(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify { expenseDao.insertExpenseStatus(match { it.status == "ACKNOWLEDGED" }) }
    }

    test("ExpenseStatus LWW: earlier timestamp does NOT replace later status") {
        val existingStatus = ExpenseStatusEntity(
            id = "status-new",
            expenseId = "exp-001",
            status = "ACKNOWLEDGED",
            responderId = "device-b",
            clientTimestamp = "2026-04-01T14:00:00Z" // later
        )

        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "ExpenseStatus",
            entityId = "exp-status-old",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z", // earlier timestamp
            protocolVersion = 2,
            data = buildJsonObject {
                put("expenseId", "exp-001")
                put("status", "DISPUTED")
                put("responderDeviceId", "device-a")
            }
        )

        coEvery { expenseDao.getLatestStatusForExpense("exp-001") } returns existingStatus

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        // Should NOT insert because existing has later timestamp
        coVerify(exactly = 0) { expenseDao.insertExpenseStatus(any()) }
    }

    // ── CalendarEvent CREATE/DELETE ──────────────────────────────────────────

    test("CalendarEvent CREATE inserts event") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "CalendarEvent",
            entityId = "evt-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("title", "Doctor visit")
                put("startTime", "2026-04-10T09:00:00Z")
                put("endTime", "2026-04-10T10:00:00Z")
                put("location", "Clinic")
            }
        )

        coEvery { calendarEventDao.insertEvent(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            calendarEventDao.insertEvent(match {
                it.eventId == "evt-001" && it.title == "Doctor visit"
            })
        }
    }

    test("CalendarEvent DELETE deletes event") {
        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "CalendarEvent",
            entityId = "evt-001",
            operation = "DELETE",
            clientTimestamp = "2026-04-02T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {}
        )

        coEvery { calendarEventDao.deleteEvent("evt-001") } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify { calendarEventDao.deleteEvent("evt-001") }
    }

    // ── InfoBankEntry CREATE/UPDATE/DELETE ───────────────────────────────────

    test("InfoBankEntry CREATE inserts entry with content JSON") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "InfoBankEntry",
            entityId = "11111111-2222-3333-4444-555555555555",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "22222222-3333-4444-5555-666666666666")
                put("category", "MEDICAL")
                put("title", "Allergies")
                put("notes", "Penicillin allergy")
                put("doctorName", "Dr. Smith")
            }
        )

        coEvery { infoBankDao.insertEntry(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            infoBankDao.insertEntry(match {
                it.category == "MEDICAL" &&
                it.title == "Allergies" &&
                it.content != null &&
                it.content!!.contains("doctorName")
            })
        }
    }

    test("InfoBankEntry DELETE marks entry as deleted") {
        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "InfoBankEntry",
            entityId = "11111111-2222-3333-4444-555555555555",
            operation = "DELETE",
            clientTimestamp = "2026-04-02T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {}
        )

        coEvery { infoBankDao.markDeleted(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify { infoBankDao.markDeleted(any()) }
    }

    // ── Unknown Entity Type ─────────────────────────────────────────────────

    test("unknown entity type is handled gracefully") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "UnknownType",
            entityId = "unknown-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {}
        )

        val applier = createOpApplier()
        val result = applier.apply(opLogEntry(), payload)

        // Should not crash, just return default result
        result.conflictResolved shouldBe false
    }

    // ── Op is always stored in oplog ────────────────────────────────────────

    test("every applied op is stored in the oplog") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "UnknownType",
            entityId = "any-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {}
        )

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify { opLogDao.insertOpLogEntry(any()) }
    }

    test("every applied op is fed to the override state machine") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "CustodySchedule",
            entityId = "sched-001",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("anchorDate", "2026-04-01")
                put("cycleLengthDays", 14)
                putJsonArray("pattern") { add("parentA") }
                put("effectiveFrom", "2026-04-01T00:00:00.000Z")
                put("timeZone", "UTC")
            }
        )

        coEvery { custodyScheduleDao.getActiveSchedulesForChild(any()) } returns emptyList()
        coEvery { custodyScheduleDao.insertSchedule(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        verify { overrideStateMachine.apply(payload) }
    }
})
