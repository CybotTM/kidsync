package com.kidsync.app.sync

import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.*
import com.kidsync.app.domain.model.DecryptedPayload
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import com.kidsync.app.domain.usecase.sync.OpApplier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.serialization.json.*
import java.time.Instant

/**
 * Extended tests for OpApplier covering:
 *
 * - OverrideStatus.valueOf guard (SEC6-A-15) with unknown status values
 * - CalendarEvent UPDATE operation
 * - Expense without optional receipt fields
 * - ScheduleOverride with proposerId vs proposerDeviceId fallback
 * - ExpenseStatus with responderDeviceId vs responderId fallback
 * - CustodySchedule conflict where existing wins
 * - Multiple entity types all store op in oplog
 * - InfoBankEntry UPDATE (upsert behavior)
 */
class OpApplierExtendedTest : FunSpec({

    val custodyScheduleDao = mockk<CustodyScheduleDao>()
    val overrideDao = mockk<OverrideDao>()
    val expenseDao = mockk<ExpenseDao>()
    val infoBankDao = mockk<InfoBankDao>()
    val calendarEventDao = mockk<CalendarEventDao>()
    val opLogDao = mockk<OpLogDao>()
    val overrideStateMachine = mockk<OverrideStateMachine>(relaxed = true)
    val conflictResolver = mockk<ConflictResolver>()
    val json = Json { ignoreUnknownKeys = true }

    val bucketId = "bucket-ext-test"
    val deviceId = "device-ext-test"

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

    // ── SEC6-A-15: OverrideStatus.valueOf Guard ──────────────────────────────

    test("ScheduleOverride UPDATE with unknown from-status skips gracefully (SEC6-A-15)") {
        val existing = ScheduleOverrideEntity(
            overrideId = "ovr-unknown",
            type = "SWAP_REQUEST",
            childId = "child-001",
            startDate = "2026-05-01",
            endDate = "2026-05-01",
            assignedParentId = "parentA",
            status = "FUTURE_STATUS_V2",  // Unknown to current code
            proposerId = "device-proposer"
        )

        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "ScheduleOverride",
            entityId = "ovr-unknown",
            operation = "UPDATE",
            clientTimestamp = "2026-05-01T11:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "SWAP_REQUEST")
                put("childId", "child-001")
                put("startDate", "2026-05-01")
                put("endDate", "2026-05-01")
                put("assignedParentId", "parentA")
                put("status", "APPROVED")
                put("responderDeviceId", "device-other")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-unknown") } returns existing

        val applier = createOpApplier()
        val result = applier.apply(opLogEntry(), payload)

        // Should skip the update (not crash)
        result.conflictResolved shouldBe false
        coVerify(exactly = 0) { overrideDao.updateOverride(any()) }
    }

    test("ScheduleOverride UPDATE with unknown to-status skips gracefully (SEC6-A-15)") {
        val existing = ScheduleOverrideEntity(
            overrideId = "ovr-future",
            type = "SWAP_REQUEST",
            childId = "child-001",
            startDate = "2026-05-01",
            endDate = "2026-05-01",
            assignedParentId = "parentA",
            status = "PROPOSED",
            proposerId = "device-proposer"
        )

        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "ScheduleOverride",
            entityId = "ovr-future",
            operation = "UPDATE",
            clientTimestamp = "2026-05-01T11:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "SWAP_REQUEST")
                put("childId", "child-001")
                put("startDate", "2026-05-01")
                put("endDate", "2026-05-01")
                put("assignedParentId", "parentA")
                put("status", "NEW_UNKNOWN_STATUS")  // Unknown to current code
                put("responderDeviceId", "device-other")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-future") } returns existing

        val applier = createOpApplier()
        val result = applier.apply(opLogEntry(), payload)

        // Should skip the update (not crash)
        result.conflictResolved shouldBe false
        coVerify(exactly = 0) { overrideDao.updateOverride(any()) }
    }

    // ── CalendarEvent UPDATE ─────────────────────────────────────────────────

    test("CalendarEvent UPDATE inserts (upserts) the event") {
        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "CalendarEvent",
            entityId = "evt-update-001",
            operation = "UPDATE",
            clientTimestamp = "2026-04-02T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("title", "Updated Doctor Visit")
                put("startTime", "2026-04-10T09:00:00Z")
                put("endTime", "2026-04-10T11:00:00Z")
                put("allDay", "false")
                put("location", "New Clinic")
                put("createdBy", "device-creator")
            }
        )

        coEvery { calendarEventDao.insertEvent(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            calendarEventDao.insertEvent(match {
                it.eventId == "evt-update-001" && it.title == "Updated Doctor Visit"
            })
        }
    }

    // ── CalendarEvent with allDay = true ─────────────────────────────────────

    test("CalendarEvent CREATE with allDay=true") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "CalendarEvent",
            entityId = "evt-allday",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("title", "School Holiday")
                put("startTime", "2026-04-10T00:00:00Z")
                put("endTime", "2026-04-11T00:00:00Z")
                put("allDay", "true")
            }
        )

        coEvery { calendarEventDao.insertEvent(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            calendarEventDao.insertEvent(match { it.allDay })
        }
    }

    // ── Expense Without Optional Fields ──────────────────────────────────────

    test("Expense CREATE without receipt fields") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "Expense",
            entityId = "exp-no-receipt",
            operation = "CREATE",
            clientTimestamp = "2026-04-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("paidByDeviceId", "device-payer")
                put("amountCents", 5000)
                put("currencyCode", "EUR")
                put("category", "FOOD")
                put("description", "School lunch")
                put("incurredAt", "2026-03-25")
                put("payerResponsibilityRatio", 0.5)
                // No receipt fields
            }
        )

        coEvery { expenseDao.insertExpense(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            expenseDao.insertExpense(match {
                it.expenseId == "exp-no-receipt" &&
                it.receiptBlobId == null &&
                it.receiptBlobKey == null &&
                it.receiptBlobNonce == null
            })
        }
    }

    // ── ScheduleOverride with proposerId Fallback ────────────────────────────

    test("ScheduleOverride CREATE uses proposerId when proposerDeviceId is absent") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "ScheduleOverride",
            entityId = "ovr-pid",
            operation = "CREATE",
            clientTimestamp = "2026-05-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "MANUAL_OVERRIDE")
                put("childId", "child-001")
                put("startDate", "2026-05-05")
                put("endDate", "2026-05-05")
                put("assignedParentId", "parentB")
                put("status", "APPROVED")
                put("proposerId", "device-originator")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-pid") } returns null
        coEvery { overrideDao.insertOverride(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            overrideDao.insertOverride(match {
                it.proposerId == "device-originator"
            })
        }
    }

    // ── ExpenseStatus with responderId Fallback ──────────────────────────────

    test("ExpenseStatus uses responderId when responderDeviceId is absent") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "ExpenseStatus",
            entityId = "es-resp",
            operation = "CREATE",
            clientTimestamp = "2026-05-01T12:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("expenseId", "exp-001")
                put("status", "DISPUTED")
                put("responderId", "device-responder-fallback")
            }
        )

        coEvery { expenseDao.getLatestStatusForExpense("exp-001") } returns null
        coEvery { expenseDao.insertExpenseStatus(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            expenseDao.insertExpenseStatus(match {
                it.responderId == "device-responder-fallback"
            })
        }
    }

    // ── CustodySchedule Conflict - Existing Wins ─────────────────────────────

    test("CustodySchedule CREATE with conflict - existing schedule wins") {
        val existingSchedule = CustodyScheduleEntity(
            scheduleId = "sched-winner",
            childId = "child-001",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00Z",
            timeZone = "UTC",
            clientTimestamp = "2026-03-28T12:00:00.000Z"
        )

        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "CustodySchedule",
            entityId = "sched-loser",
            operation = "CREATE",
            clientTimestamp = "2026-03-28T10:00:00.000Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "child-001")
                put("anchorDate", "2026-04-01")
                put("cycleLengthDays", 14)
                putJsonArray("pattern") { add("parentB") }
                put("effectiveFrom", "2026-04-01T00:00:00.000Z")
                put("timeZone", "UTC")
            }
        )

        coEvery { custodyScheduleDao.getActiveSchedulesForChild("child-001") } returns listOf(existingSchedule)
        coEvery { conflictResolver.resolveCustodyScheduleConflict(any(), any(), any()) } answers {
            firstArg() // existing wins
        }
        coEvery { custodyScheduleDao.insertSchedule(any()) } just Runs

        val applier = createOpApplier()
        val result = applier.apply(opLogEntry(), payload)

        result.conflictResolved shouldBe true
        // Incoming schedule should be inserted with SUPERSEDED status
        coVerify {
            custodyScheduleDao.insertSchedule(match {
                it.scheduleId == "sched-loser" && it.status == "SUPERSEDED"
            })
        }
        // Existing schedule should NOT be superseded
        coVerify(exactly = 0) { custodyScheduleDao.updateStatus("sched-winner", any()) }
    }

    // ── InfoBankEntry UPDATE (same as INSERT, upsert) ────────────────────────

    test("InfoBankEntry UPDATE inserts entry (upsert via Room)") {
        val payload = DecryptedPayload(
            deviceSequence = 2,
            entityType = "InfoBankEntry",
            entityId = "11111111-2222-3333-4444-555555555555",
            operation = "UPDATE",
            clientTimestamp = "2026-04-02T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("childId", "22222222-3333-4444-5555-666666666666")
                put("category", "MEDICAL")
                put("title", "Updated Allergies")
                put("notes", "Penicillin + Sulfa")
                put("doctorName", "Dr. Jones")
            }
        )

        coEvery { infoBankDao.insertEntry(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            infoBankDao.insertEntry(match {
                it.title == "Updated Allergies" &&
                it.content!!.contains("doctorName")
            })
        }
    }

    // ── ScheduleOverride default status ──────────────────────────────────────

    test("ScheduleOverride CREATE without explicit status defaults to PROPOSED") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "ScheduleOverride",
            entityId = "ovr-default",
            operation = "CREATE",
            clientTimestamp = "2026-05-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "SWAP_REQUEST")
                put("childId", "child-001")
                put("startDate", "2026-05-10")
                put("endDate", "2026-05-10")
                put("assignedParentId", "parentA")
                // No "status" field
                put("proposerDeviceId", "device-proposer")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-default") } returns null
        coEvery { overrideDao.insertOverride(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            overrideDao.insertOverride(match { it.status == "PROPOSED" })
        }
    }

    // ── ScheduleOverride CREATE when getOverrideById returns null (no existing) ──

    test("ScheduleOverride CREATE inserts new override when no existing") {
        val payload = DecryptedPayload(
            deviceSequence = 1,
            entityType = "ScheduleOverride",
            entityId = "ovr-new",
            operation = "CREATE",
            clientTimestamp = "2026-05-01T10:00:00Z",
            protocolVersion = 2,
            data = buildJsonObject {
                put("type", "HOLIDAY_RULE")
                put("childId", "child-002")
                put("startDate", "2026-07-01")
                put("endDate", "2026-07-15")
                put("assignedParentId", "parentA")
                put("status", "APPROVED")
                put("proposerDeviceId", "device-A")
                put("responderDeviceId", "device-B")
                put("note", "Summer holiday arrangement")
            }
        )

        coEvery { overrideDao.getOverrideById("ovr-new") } returns null
        coEvery { overrideDao.insertOverride(any()) } just Runs

        val applier = createOpApplier()
        applier.apply(opLogEntry(), payload)

        coVerify {
            overrideDao.insertOverride(match {
                it.overrideId == "ovr-new" &&
                it.type == "HOLIDAY_RULE" &&
                it.note == "Summer holiday arrangement" &&
                it.responderId == "device-B"
            })
        }
    }

    // ── Multiple entity types all store op in oplog ──────────────────────────

    test("all entity types store the op in oplog") {
        val entityTypes = listOf("CustodySchedule", "ScheduleOverride", "Expense",
            "ExpenseStatus", "CalendarEvent", "InfoBankEntry", "UnknownType")

        for (entityType in entityTypes) {
            clearAllMocks()
            coEvery { opLogDao.insertOpLogEntry(any()) } just Runs

            // Set up necessary mocks for each type
            when (entityType) {
                "CustodySchedule" -> {
                    coEvery { custodyScheduleDao.getActiveSchedulesForChild(any()) } returns emptyList()
                    coEvery { custodyScheduleDao.insertSchedule(any()) } just Runs
                }
                "ScheduleOverride" -> {
                    coEvery { overrideDao.getOverrideById(any()) } returns null
                    coEvery { overrideDao.insertOverride(any()) } just Runs
                }
                "Expense" -> coEvery { expenseDao.insertExpense(any()) } just Runs
                "ExpenseStatus" -> {
                    coEvery { expenseDao.getLatestStatusForExpense(any()) } returns null
                    coEvery { expenseDao.insertExpenseStatus(any()) } just Runs
                }
                "CalendarEvent" -> coEvery { calendarEventDao.insertEvent(any()) } just Runs
                "InfoBankEntry" -> coEvery { infoBankDao.insertEntry(any()) } just Runs
            }

            val data = when (entityType) {
                "CustodySchedule" -> buildJsonObject {
                    put("childId", "child-001")
                    put("anchorDate", "2026-04-01")
                    put("cycleLengthDays", 14)
                    putJsonArray("pattern") { add("parentA") }
                    put("effectiveFrom", "2026-04-01T00:00:00.000Z")
                    put("timeZone", "UTC")
                }
                "ScheduleOverride" -> buildJsonObject {
                    put("type", "SWAP_REQUEST")
                    put("childId", "child-001")
                    put("startDate", "2026-05-01")
                    put("endDate", "2026-05-01")
                    put("assignedParentId", "parentA")
                    put("proposerDeviceId", "device")
                }
                "Expense" -> buildJsonObject {
                    put("childId", "child-001")
                    put("paidByDeviceId", "device")
                    put("amountCents", 100)
                    put("currencyCode", "USD")
                    put("category", "OTHER")
                    put("description", "test")
                    put("incurredAt", "2026-01-01")
                    put("payerResponsibilityRatio", 0.5)
                }
                "ExpenseStatus" -> buildJsonObject {
                    put("expenseId", "exp-001")
                    put("status", "LOGGED")
                    put("responderId", "device")
                }
                "CalendarEvent" -> buildJsonObject {
                    put("childId", "child-001")
                    put("title", "test")
                    put("startTime", "2026-01-01T10:00:00Z")
                    put("endTime", "2026-01-01T11:00:00Z")
                }
                "InfoBankEntry" -> buildJsonObject {
                    put("childId", "22222222-3333-4444-5555-666666666666")
                    put("category", "OTHER")
                }
                else -> buildJsonObject {}
            }

            val entityId = if (entityType == "InfoBankEntry") "11111111-2222-3333-4444-555555555555" else "entity-$entityType"

            val payload = DecryptedPayload(
                deviceSequence = 1,
                entityType = entityType,
                entityId = entityId,
                operation = "CREATE",
                clientTimestamp = "2026-04-01T10:00:00Z",
                protocolVersion = 2,
                data = data
            )

            val applier = createOpApplier()
            applier.apply(opLogEntry(), payload)

            coVerify(atLeast = 1) { opLogDao.insertOpLogEntry(any()) }
        }
    }
})
