package com.kidsync.app.sync

import com.kidsync.app.data.local.entity.CustodyScheduleEntity
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Tests for conflict resolution using tv02 (concurrent merge) and tv06 (clock skew) vectors.
 *
 * CustodySchedule conflict resolution rules (sync-protocol.md Section 9.1):
 * 1. Latest effectiveFrom wins
 * 2. Tie-break: later clientTimestamp wins
 * 3. Tie-break: lexicographically greater scheduleId wins
 */
class ConflictResolutionTest : FunSpec({

    val overrideStateMachine = OverrideStateMachine()
    val conflictResolver = ConflictResolver(overrideStateMachine)

    // ---- TV02: Concurrent Offline Merge ----

    test("TV02: same effectiveFrom, later clientTimestamp wins") {
        val scheduleA = CustodyScheduleEntity(
            scheduleId = "aaaa1111-2222-3333-4444-555566667777",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00.000Z",
            timeZone = "America/New_York",
            clientTimestamp = "2026-03-28T10:00:00.000Z"
        )

        val scheduleB = CustodyScheduleEntity(
            scheduleId = "bbbb1111-2222-3333-4444-555566667777",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00.000Z",
            timeZone = "America/New_York",
            clientTimestamp = "2026-03-28T10:05:00.000Z"
        )

        // Device B has later clientTimestamp (10:05 > 10:00) -> B wins
        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleA,
            incoming = scheduleB,
            incomingClientTimestamp = Instant.parse("2026-03-28T10:05:00.000Z")
        )

        winner.scheduleId shouldBe "bbbb1111-2222-3333-4444-555566667777"
    }

    test("TV02: applying in globalSequence order yields deterministic result") {
        // Step 1: Apply Device A's schedule (globalSequence=1) first
        // Step 2: Apply Device B's schedule (globalSequence=2) triggers conflict
        // Device B wins because of later clientTimestamp

        val scheduleA = CustodyScheduleEntity(
            scheduleId = "aaaa1111-2222-3333-4444-555566667777",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00.000Z",
            timeZone = "America/New_York",
            clientTimestamp = "2026-03-28T10:00:00.000Z"
        )

        val scheduleB = CustodyScheduleEntity(
            scheduleId = "bbbb1111-2222-3333-4444-555566667777",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00.000Z",
            timeZone = "America/New_York",
            clientTimestamp = "2026-03-28T10:05:00.000Z"
        )

        // When B arrives (globalSeq=2), it conflicts with existing A
        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleA,
            incoming = scheduleB,
            incomingClientTimestamp = Instant.parse("2026-03-28T10:05:00.000Z")
        )

        // B wins -> B is ACTIVE, A should be SUPERSEDED
        winner.scheduleId shouldBe scheduleB.scheduleId
    }

    // ---- TV06: Clock Skew ----

    test("TV06: clock skew - Device B with 2h ahead clock wins via clientTimestamp tie-break") {
        // Device A: accurate clock -> clientTimestamp 2026-03-28T10:00:00.000Z
        // Device B: 2h ahead clock -> clientTimestamp 2026-03-28T12:00:30.000Z
        // Both have same effectiveFrom -> tie-break by clientTimestamp -> B wins

        val scheduleA = CustodyScheduleEntity(
            scheduleId = "sched-aa-1111-2222-3333-444444444444",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-06",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-06T00:00:00.000Z",
            timeZone = "Europe/Berlin",
            clientTimestamp = "2026-03-28T10:00:00.000Z"
        )

        val scheduleB = CustodyScheduleEntity(
            scheduleId = "sched-bb-1111-2222-3333-444444444444",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-06",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-06T00:00:00.000Z",
            timeZone = "Europe/Berlin",
            clientTimestamp = "2026-03-28T12:00:30.000Z"
        )

        // Even though B's clock was skewed, it has the later timestamp -> B wins
        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleB, // B applied first (globalSeq=1)
            incoming = scheduleA, // A applied second (globalSeq=2) triggers conflict
            incomingClientTimestamp = Instant.parse("2026-03-28T10:00:00.000Z")
        )

        // B has later clientTimestamp (12:00:30 > 10:00:00) -> B wins
        winner.scheduleId shouldBe "sched-bb-1111-2222-3333-444444444444"
    }

    test("TV06: server ordering does not affect conflict resolution outcome") {
        // Device B uploaded first (globalSeq=1), Device A uploaded second (globalSeq=2)
        // But conflict resolution uses clientTimestamp, not globalSequence

        val scheduleB = CustodyScheduleEntity(
            scheduleId = "sched-bb-1111-2222-3333-444444444444",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-06",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-06T00:00:00.000Z",
            timeZone = "Europe/Berlin",
            clientTimestamp = "2026-03-28T12:00:30.000Z"
        )

        val scheduleA = CustodyScheduleEntity(
            scheduleId = "sched-aa-1111-2222-3333-444444444444",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-06",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-06T00:00:00.000Z",
            timeZone = "Europe/Berlin",
            clientTimestamp = "2026-03-28T10:00:00.000Z"
        )

        // Regardless of which is "existing" vs "incoming", B wins by clientTimestamp
        val winner1 = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleB,
            incoming = scheduleA,
            incomingClientTimestamp = Instant.parse("2026-03-28T10:00:00.000Z")
        )
        winner1.scheduleId shouldBe scheduleB.scheduleId

        val winner2 = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleA,
            incoming = scheduleB,
            incomingClientTimestamp = Instant.parse("2026-03-28T12:00:30.000Z")
        )
        winner2.scheduleId shouldBe scheduleB.scheduleId
    }

    // ---- Tie-break by scheduleId ----

    test("same effectiveFrom, same clientTimestamp: lexicographically greater deviceId wins") {
        val timestamp = "2026-03-28T10:00:00.000Z"

        val scheduleSmall = CustodyScheduleEntity(
            scheduleId = "11111111-0000-0000-0000-000000000000",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00.000Z",
            timeZone = "UTC",
            clientTimestamp = timestamp,
            deviceId = "device-aaa"
        )

        val scheduleLarge = CustodyScheduleEntity(
            scheduleId = "ffffffff-0000-0000-0000-000000000000",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00.000Z",
            timeZone = "UTC",
            clientTimestamp = timestamp,
            deviceId = "device-zzz"
        )

        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleSmall,
            incoming = scheduleLarge,
            incomingClientTimestamp = Instant.parse(timestamp)
        )

        // "device-zzz" > "device-aaa" -> scheduleLarge wins
        winner.scheduleId shouldBe scheduleLarge.scheduleId
    }

    // ---- Different effectiveFrom ----

    test("different effectiveFrom: later effectiveFrom wins") {
        val scheduleEarly = CustodyScheduleEntity(
            scheduleId = "early-id-1111-2222-3333-444444444444",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-01",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-01T00:00:00.000Z",
            timeZone = "UTC",
            clientTimestamp = "2026-03-28T10:00:00.000Z"
        )

        val scheduleLate = CustodyScheduleEntity(
            scheduleId = "late0-id-1111-2222-3333-444444444444",
            childId = "c1d2e3f4-5678-9abc-def0-123456789012",
            anchorDate = "2026-04-15",
            cycleLengthDays = 14,
            patternJson = "[]",
            effectiveFrom = "2026-04-15T00:00:00.000Z",
            timeZone = "UTC",
            clientTimestamp = "2026-03-25T10:00:00.000Z" // Earlier clientTimestamp
        )

        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleEarly,
            incoming = scheduleLate,
            incomingClientTimestamp = Instant.parse("2026-03-25T10:00:00.000Z")
        )

        // Later effectiveFrom wins regardless of clientTimestamp
        winner.scheduleId shouldBe scheduleLate.scheduleId
    }

    // ---- Override state transition validation ----

    test("valid override transitions are accepted") {
        conflictResolver.isValidOverrideTransition(
            OverrideStatus.PROPOSED, OverrideStatus.APPROVED
        ) shouldBe true

        conflictResolver.isValidOverrideTransition(
            OverrideStatus.PROPOSED, OverrideStatus.DECLINED
        ) shouldBe true

        conflictResolver.isValidOverrideTransition(
            OverrideStatus.PROPOSED, OverrideStatus.CANCELLED
        ) shouldBe true
    }

    test("invalid override transitions from terminal states are rejected") {
        conflictResolver.isValidOverrideTransition(
            OverrideStatus.DECLINED, OverrideStatus.APPROVED
        ) shouldBe false

        conflictResolver.isValidOverrideTransition(
            OverrideStatus.CANCELLED, OverrideStatus.APPROVED
        ) shouldBe false

        conflictResolver.isValidOverrideTransition(
            OverrideStatus.EXPIRED, OverrideStatus.APPROVED
        ) shouldBe false
    }
})
