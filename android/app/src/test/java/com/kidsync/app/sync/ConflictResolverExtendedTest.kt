package com.kidsync.app.sync

import com.kidsync.app.data.local.entity.CustodyScheduleEntity
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import com.kidsync.app.domain.usecase.custody.OverrideStateMachine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * Extended tests for ConflictResolver covering:
 * - Schedule with later effectiveFrom wins
 * - Tie on effectiveFrom: later clientTimestamp wins
 * - Tie on both: higher deviceId wins (lexicographic)
 * - Override status transitions: valid and invalid
 * - Edge cases with identical schedules
 */
class ConflictResolverExtendedTest : FunSpec({

    val overrideStateMachine = OverrideStateMachine()
    val conflictResolver = ConflictResolver(overrideStateMachine)

    fun schedule(
        scheduleId: String,
        effectiveFrom: String,
        clientTimestamp: String,
        deviceId: String = ""
    ) = CustodyScheduleEntity(
        scheduleId = scheduleId,
        childId = "child-001",
        anchorDate = "2026-04-01",
        cycleLengthDays = 14,
        patternJson = "[]",
        effectiveFrom = effectiveFrom,
        timeZone = "UTC",
        clientTimestamp = clientTimestamp,
        deviceId = deviceId
    )

    // ── effectiveFrom wins ──────────────────────────────────────────────────

    test("schedule with later effectiveFrom wins regardless of clientTimestamp") {
        val early = schedule("sched-early", "2026-04-01T00:00:00.000Z", "2026-04-01T12:00:00.000Z")
        val late = schedule("sched-late", "2026-04-15T00:00:00.000Z", "2026-03-20T08:00:00.000Z")

        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = early,
            incoming = late,
            incomingClientTimestamp = Instant.parse("2026-03-20T08:00:00.000Z")
        )
        winner.scheduleId shouldBe "sched-late"
    }

    test("schedule with earlier effectiveFrom loses regardless of clientTimestamp") {
        val early = schedule("sched-early", "2026-04-01T00:00:00.000Z", "2026-04-01T12:00:00.000Z")
        val late = schedule("sched-late", "2026-04-15T00:00:00.000Z", "2026-03-20T08:00:00.000Z")

        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = late,
            incoming = early,
            incomingClientTimestamp = Instant.parse("2026-04-01T12:00:00.000Z")
        )
        winner.scheduleId shouldBe "sched-late"
    }

    // ── clientTimestamp tie-break ────────────────────────────────────────────

    test("same effectiveFrom: later clientTimestamp wins (incoming wins)") {
        val existing = schedule("sched-A", "2026-04-01T00:00:00.000Z", "2026-03-28T10:00:00.000Z")
        val incoming = schedule("sched-B", "2026-04-01T00:00:00.000Z", "2026-03-28T10:05:00.000Z")

        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = existing,
            incoming = incoming,
            incomingClientTimestamp = Instant.parse("2026-03-28T10:05:00.000Z")
        )
        winner.scheduleId shouldBe "sched-B"
    }

    test("same effectiveFrom: later clientTimestamp wins (existing wins)") {
        val existing = schedule("sched-A", "2026-04-01T00:00:00.000Z", "2026-03-28T10:05:00.000Z")
        val incoming = schedule("sched-B", "2026-04-01T00:00:00.000Z", "2026-03-28T10:00:00.000Z")

        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = existing,
            incoming = incoming,
            incomingClientTimestamp = Instant.parse("2026-03-28T10:00:00.000Z")
        )
        winner.scheduleId shouldBe "sched-A"
    }

    // ── deviceId tie-break ──────────────────────────────────────────────────

    test("same effectiveFrom and clientTimestamp: lexicographically greater deviceId wins") {
        val timestamp = "2026-03-28T10:00:00.000Z"
        val scheduleSmall = schedule("sched-small", "2026-04-01T00:00:00.000Z", timestamp, "aaaa-device")
        val scheduleLarge = schedule("sched-large", "2026-04-01T00:00:00.000Z", timestamp, "zzzz-device")

        val winner = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleSmall,
            incoming = scheduleLarge,
            incomingClientTimestamp = Instant.parse(timestamp)
        )
        winner.scheduleId shouldBe "sched-large"
    }

    test("deviceId tie-break is symmetric") {
        val timestamp = "2026-03-28T10:00:00.000Z"
        val scheduleSmall = schedule("sched-small", "2026-04-01T00:00:00.000Z", timestamp, "aaaa-device")
        val scheduleLarge = schedule("sched-large", "2026-04-01T00:00:00.000Z", timestamp, "zzzz-device")

        // Try both orderings
        val winner1 = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleSmall,
            incoming = scheduleLarge,
            incomingClientTimestamp = Instant.parse(timestamp)
        )

        val winner2 = conflictResolver.resolveCustodyScheduleConflict(
            existing = scheduleLarge,
            incoming = scheduleSmall,
            incomingClientTimestamp = Instant.parse(timestamp)
        )

        winner1.scheduleId shouldBe winner2.scheduleId
    }

    // ── Override Status Transitions ─────────────────────────────────────────

    test("valid override transitions are accepted") {
        conflictResolver.isValidOverrideTransition(OverrideStatus.PROPOSED, OverrideStatus.APPROVED) shouldBe true
        conflictResolver.isValidOverrideTransition(OverrideStatus.PROPOSED, OverrideStatus.DECLINED) shouldBe true
        conflictResolver.isValidOverrideTransition(OverrideStatus.PROPOSED, OverrideStatus.CANCELLED) shouldBe true
        conflictResolver.isValidOverrideTransition(OverrideStatus.APPROVED, OverrideStatus.SUPERSEDED) shouldBe true
        conflictResolver.isValidOverrideTransition(OverrideStatus.APPROVED, OverrideStatus.EXPIRED) shouldBe true
    }

    test("invalid override transitions from terminal states are rejected") {
        conflictResolver.isValidOverrideTransition(OverrideStatus.DECLINED, OverrideStatus.APPROVED) shouldBe false
        conflictResolver.isValidOverrideTransition(OverrideStatus.CANCELLED, OverrideStatus.PROPOSED) shouldBe false
        conflictResolver.isValidOverrideTransition(OverrideStatus.SUPERSEDED, OverrideStatus.APPROVED) shouldBe false
        conflictResolver.isValidOverrideTransition(OverrideStatus.EXPIRED, OverrideStatus.APPROVED) shouldBe false
    }

    test("invalid transitions between non-terminal states") {
        conflictResolver.isValidOverrideTransition(OverrideStatus.APPROVED, OverrideStatus.DECLINED) shouldBe false
    }

    test("valid transitions from PROPOSED to EXPIRED and SUPERSEDED in ZK architecture") {
        // In the zero-knowledge architecture, PROPOSED can transition to EXPIRED and SUPERSEDED
        // (auto-expiry and auto-supersede are system-initiated)
        conflictResolver.isValidOverrideTransition(OverrideStatus.PROPOSED, OverrideStatus.EXPIRED) shouldBe true
        conflictResolver.isValidOverrideTransition(OverrideStatus.PROPOSED, OverrideStatus.SUPERSEDED) shouldBe true
    }
})
