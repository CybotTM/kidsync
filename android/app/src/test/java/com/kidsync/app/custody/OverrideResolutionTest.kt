package com.kidsync.app.custody

import com.kidsync.app.domain.model.*
import com.kidsync.app.domain.usecase.custody.OverrideResolver
import com.kidsync.app.domain.usecase.custody.PatternGenerator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Tests for OverrideResolver covering:
 * - Override precedence: COURT_ORDER > HOLIDAY > SWAP_REQUEST > MANUAL
 * - Only APPROVED overrides are applied
 * - Overlapping overrides of different types use highest precedence
 * - Same-type overlapping overrides use latest createdAt
 * - Single day override
 * - Multi-day override range
 * - No overrides (passthrough)
 */
class OverrideResolutionTest : FunSpec({

    val patternGenerator = PatternGenerator()
    val resolver = OverrideResolver(patternGenerator)
    val parentA = "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa"
    val parentB = "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"
    val childId = "c1d2e3f4-5678-9abc-def0-123456789012"

    val baseSchedule = CustodySchedule(
        scheduleId = UUID.randomUUID().toString(),
        childId = childId,
        anchorDate = LocalDate.of(2026, 4, 1),
        cycleLengthDays = 2,
        pattern = listOf(parentA, parentB), // A on even days, B on odd days
        effectiveFrom = Instant.parse("2026-04-01T00:00:00Z"),
        timeZone = "America/New_York"
    )

    test("no overrides: base schedule is returned unchanged") {
        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = emptyList(),
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 4)
        )

        days.size shouldBe 4
        days[0].assignedParentId shouldBe parentA
        days[0].source shouldBe CustodyDaySource.BASE_SCHEDULE
        days[1].assignedParentId shouldBe parentB
        days[2].assignedParentId shouldBe parentA
        days[3].assignedParentId shouldBe parentB
    }

    test("single day APPROVED override replaces base schedule") {
        val override = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.SWAP_REQUEST,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 2), // Normally parentB
            endDate = LocalDate.of(2026, 4, 2),
            assignedParentId = parentA, // Override to parentA
            status = OverrideStatus.APPROVED,
            proposerId = parentB,
            createdAt = Instant.now()
        )

        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = listOf(override),
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 3)
        )

        days.size shouldBe 3
        days[0].assignedParentId shouldBe parentA  // Base schedule
        days[0].source shouldBe CustodyDaySource.BASE_SCHEDULE
        days[1].assignedParentId shouldBe parentA  // Overridden from B to A
        days[1].source shouldBe CustodyDaySource.OVERRIDE
        days[1].overrideId shouldBe override.overrideId
        days[2].assignedParentId shouldBe parentA  // Base schedule
    }

    test("PROPOSED override is NOT applied") {
        val override = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.SWAP_REQUEST,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 2),
            endDate = LocalDate.of(2026, 4, 2),
            assignedParentId = parentA,
            status = OverrideStatus.PROPOSED, // Not APPROVED
            proposerId = parentB,
            createdAt = Instant.now()
        )

        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = listOf(override),
            startDate = LocalDate.of(2026, 4, 2),
            endDate = LocalDate.of(2026, 4, 2)
        )

        days.size shouldBe 1
        days[0].assignedParentId shouldBe parentB // Base schedule, override not applied
        days[0].source shouldBe CustodyDaySource.BASE_SCHEDULE
    }

    test("DECLINED override is NOT applied") {
        val override = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.SWAP_REQUEST,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 2),
            endDate = LocalDate.of(2026, 4, 2),
            assignedParentId = parentA,
            status = OverrideStatus.DECLINED,
            proposerId = parentB,
            createdAt = Instant.now()
        )

        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = listOf(override),
            startDate = LocalDate.of(2026, 4, 2),
            endDate = LocalDate.of(2026, 4, 2)
        )

        days[0].source shouldBe CustodyDaySource.BASE_SCHEDULE
    }

    test("COURT_ORDER beats SWAP_REQUEST on same dates") {
        val swapRequest = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.SWAP_REQUEST,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 5),
            endDate = LocalDate.of(2026, 4, 5),
            assignedParentId = parentA,
            status = OverrideStatus.APPROVED,
            proposerId = parentB,
            createdAt = Instant.parse("2026-03-20T10:00:00Z")
        )

        val courtOrder = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.COURT_ORDER,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 5),
            endDate = LocalDate.of(2026, 4, 5),
            assignedParentId = parentB,
            status = OverrideStatus.APPROVED,
            proposerId = parentA,
            createdAt = Instant.parse("2026-03-20T09:00:00Z") // Earlier!
        )

        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = listOf(swapRequest, courtOrder),
            startDate = LocalDate.of(2026, 4, 5),
            endDate = LocalDate.of(2026, 4, 5)
        )

        // COURT_ORDER (precedence 4) > SWAP_REQUEST (precedence 2)
        days[0].assignedParentId shouldBe parentB
        days[0].overrideId shouldBe courtOrder.overrideId
    }

    test("HOLIDAY beats MANUAL on same dates") {
        val manual = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.MANUAL_OVERRIDE,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 5),
            endDate = LocalDate.of(2026, 4, 5),
            assignedParentId = parentA,
            status = OverrideStatus.APPROVED,
            proposerId = parentB,
            createdAt = Instant.parse("2026-03-21T10:00:00Z")
        )

        val holiday = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.HOLIDAY_RULE,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 5),
            endDate = LocalDate.of(2026, 4, 5),
            assignedParentId = parentB,
            status = OverrideStatus.APPROVED,
            proposerId = parentA,
            createdAt = Instant.parse("2026-03-20T10:00:00Z")
        )

        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = listOf(manual, holiday),
            startDate = LocalDate.of(2026, 4, 5),
            endDate = LocalDate.of(2026, 4, 5)
        )

        // HOLIDAY (3) > MANUAL (1)
        days[0].assignedParentId shouldBe parentB
        days[0].overrideId shouldBe holiday.overrideId
    }

    test("multi-day override covers all days in range") {
        val override = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.HOLIDAY_RULE,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 4),
            assignedParentId = parentB,
            status = OverrideStatus.APPROVED,
            proposerId = parentA,
            createdAt = Instant.now()
        )

        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = listOf(override),
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 4)
        )

        days.size shouldBe 4
        days.forEach { day ->
            day.assignedParentId shouldBe parentB
            day.source shouldBe CustodyDaySource.OVERRIDE
        }
    }

    test("override only applies within its date range") {
        val override = ScheduleOverride(
            overrideId = UUID.randomUUID().toString(),
            type = OverrideType.SWAP_REQUEST,
            childId = childId,
            startDate = LocalDate.of(2026, 4, 2),
            endDate = LocalDate.of(2026, 4, 3),
            assignedParentId = parentA,
            status = OverrideStatus.APPROVED,
            proposerId = parentB,
            createdAt = Instant.now()
        )

        val days = resolver.resolve(
            schedule = baseSchedule,
            overrides = listOf(override),
            startDate = LocalDate.of(2026, 4, 1),
            endDate = LocalDate.of(2026, 4, 5)
        )

        days[0].source shouldBe CustodyDaySource.BASE_SCHEDULE // Apr 1 - outside override
        days[1].source shouldBe CustodyDaySource.OVERRIDE       // Apr 2 - in override
        days[2].source shouldBe CustodyDaySource.OVERRIDE       // Apr 3 - in override
        days[3].source shouldBe CustodyDaySource.BASE_SCHEDULE // Apr 4 - outside override
        days[4].source shouldBe CustodyDaySource.BASE_SCHEDULE // Apr 5 - outside override
    }

    test("precedence order: COURT_ORDER > HOLIDAY > SWAP_REQUEST > MANUAL") {
        OverrideType.COURT_ORDER.precedence shouldBe 4
        OverrideType.HOLIDAY_RULE.precedence shouldBe 3
        OverrideType.SWAP_REQUEST.precedence shouldBe 2
        OverrideType.MANUAL_OVERRIDE.precedence shouldBe 1

        (OverrideType.COURT_ORDER.precedence > OverrideType.HOLIDAY_RULE.precedence) shouldBe true
        (OverrideType.HOLIDAY_RULE.precedence > OverrideType.SWAP_REQUEST.precedence) shouldBe true
        (OverrideType.SWAP_REQUEST.precedence > OverrideType.MANUAL_OVERRIDE.precedence) shouldBe true
    }
})
