package com.kidsync.app.custody

import com.kidsync.app.domain.model.CustodyDaySource
import com.kidsync.app.domain.model.CustodySchedule
import com.kidsync.app.domain.usecase.custody.PatternGenerator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Tests for PatternGenerator covering all pattern types:
 * - Week-on/week-off (14-day cycle)
 * - 2-2-3 rotation (7-day cycle)
 * - 5-2-2-5 (every other weekend) (14-day cycle)
 * - Simple alternating days (2-day cycle)
 * - Dates before anchor date (negative daysSinceAnchor)
 * - Single day range
 * - Long range (multi-cycle)
 */
class PatternGeneratorTest : FunSpec({

    val generator = PatternGenerator()
    val parentA = "d1e2f3a4-5678-9abc-def0-aaaaaaaaaaaa"
    val parentB = "e2f3a4b5-6789-abcd-ef01-bbbbbbbbbbbb"

    fun schedule(
        anchorDate: LocalDate,
        cycleLengthDays: Int,
        pattern: List<String>
    ) = CustodySchedule(
        scheduleId = UUID.randomUUID().toString(),
        childId = UUID.randomUUID().toString(),
        anchorDate = anchorDate,
        cycleLengthDays = cycleLengthDays,
        pattern = pattern,
        effectiveFrom = Instant.now(),
        timeZone = "America/New_York"
    )

    // ---- Week-on/week-off (14-day cycle) ----

    test("week-on/week-off: Parent A gets first 7 days") {
        val pattern = List(7) { parentA } + List(7) { parentB }
        val sched = schedule(LocalDate.of(2026, 4, 1), 14, pattern)

        // Day 0 (anchor) = parentA
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 1)) shouldBe parentA
        // Day 6 = still parentA
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 7)) shouldBe parentA
        // Day 7 = parentB
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 8)) shouldBe parentB
        // Day 13 = parentB
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 14)) shouldBe parentB
        // Day 14 = back to parentA (new cycle)
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 15)) shouldBe parentA
    }

    // ---- 2-2-3 rotation (7-day cycle) ----

    test("2-2-3 rotation: Mon-Tue A, Wed-Thu B, Fri-Sun A") {
        val pattern = listOf(parentA, parentA, parentB, parentB, parentA, parentA, parentA)
        val sched = schedule(LocalDate.of(2026, 4, 6), 7, pattern) // Monday

        val days = generator.generateRange(sched, LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 12))

        days.size shouldBe 7
        days[0].assignedParentId shouldBe parentA // Mon
        days[1].assignedParentId shouldBe parentA // Tue
        days[2].assignedParentId shouldBe parentB // Wed
        days[3].assignedParentId shouldBe parentB // Thu
        days[4].assignedParentId shouldBe parentA // Fri
        days[5].assignedParentId shouldBe parentA // Sat
        days[6].assignedParentId shouldBe parentA // Sun
    }

    // ---- Simple alternating days (2-day cycle) ----

    test("alternating days: A-B-A-B repeating") {
        val pattern = listOf(parentA, parentB)
        val sched = schedule(LocalDate.of(2026, 4, 1), 2, pattern)

        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 1)) shouldBe parentA
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 2)) shouldBe parentB
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 3)) shouldBe parentA
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 4)) shouldBe parentB
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 5)) shouldBe parentA
    }

    // ---- 5-2-2-5 every-other-weekend (14-day cycle) ----

    test("5-2-2-5 pattern: weekdays with one parent, alternating weekends") {
        // Week 1: Mon-Fri=A, Sat-Sun=B
        // Week 2: Mon-Tue=B, Wed-Sun=A
        val pattern = listOf(
            parentA, parentA, parentA, parentA, parentA, parentB, parentB, // week 1
            parentB, parentB, parentA, parentA, parentA, parentA, parentA  // week 2
        )
        val sched = schedule(LocalDate.of(2026, 4, 6), 14, pattern)

        val days = generator.generateRange(sched, LocalDate.of(2026, 4, 6), LocalDate.of(2026, 4, 19))

        days.size shouldBe 14
        // Week 1
        days[0].assignedParentId shouldBe parentA   // Mon
        days[4].assignedParentId shouldBe parentA   // Fri
        days[5].assignedParentId shouldBe parentB   // Sat
        days[6].assignedParentId shouldBe parentB   // Sun
        // Week 2
        days[7].assignedParentId shouldBe parentB   // Mon
        days[8].assignedParentId shouldBe parentB   // Tue
        days[9].assignedParentId shouldBe parentA   // Wed
        days[13].assignedParentId shouldBe parentA  // Sun
    }

    // ---- Dates before anchor date ----

    test("dates before anchor use floorMod for correct negative indexing") {
        val pattern = List(7) { parentA } + List(7) { parentB }
        val sched = schedule(LocalDate.of(2026, 4, 15), 14, pattern)

        // 2 days before anchor: floorMod(-2, 14) = 12 -> parentB
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 13)) shouldBe parentB

        // 7 days before anchor: floorMod(-7, 14) = 7 -> parentB
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 8)) shouldBe parentB

        // 14 days before anchor: floorMod(-14, 14) = 0 -> parentA
        generator.getAssignedParent(sched, LocalDate.of(2026, 4, 1)) shouldBe parentA
    }

    // ---- computeDayIndex ----

    test("computeDayIndex on anchor date returns 0") {
        val pattern = listOf(parentA, parentB)
        val sched = schedule(LocalDate.of(2026, 4, 1), 2, pattern)

        generator.computeDayIndex(sched, LocalDate.of(2026, 4, 1)) shouldBe 0
    }

    test("computeDayIndex wraps correctly at cycle boundary") {
        val pattern = List(14) { parentA }
        val sched = schedule(LocalDate.of(2026, 4, 1), 14, pattern)

        generator.computeDayIndex(sched, LocalDate.of(2026, 4, 15)) shouldBe 0 // 14 mod 14 = 0
        generator.computeDayIndex(sched, LocalDate.of(2026, 4, 16)) shouldBe 1 // 15 mod 14 = 1
    }

    // ---- generateRange ----

    test("generateRange single day") {
        val pattern = listOf(parentA, parentB)
        val sched = schedule(LocalDate.of(2026, 4, 1), 2, pattern)

        val days = generator.generateRange(sched, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 1))

        days.size shouldBe 1
        days[0].date shouldBe LocalDate.of(2026, 4, 1)
        days[0].assignedParentId shouldBe parentA
        days[0].source shouldBe CustodyDaySource.BASE_SCHEDULE
    }

    test("generateRange multi-cycle") {
        val pattern = listOf(parentA, parentB)
        val sched = schedule(LocalDate.of(2026, 4, 1), 2, pattern)

        // 6 days = 3 full cycles
        val days = generator.generateRange(sched, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 6))

        days.size shouldBe 6
        days[0].assignedParentId shouldBe parentA
        days[1].assignedParentId shouldBe parentB
        days[2].assignedParentId shouldBe parentA
        days[3].assignedParentId shouldBe parentB
        days[4].assignedParentId shouldBe parentA
        days[5].assignedParentId shouldBe parentB
    }

    test("generateRange all days have BASE_SCHEDULE source") {
        val pattern = listOf(parentA)
        val sched = schedule(LocalDate.of(2026, 4, 1), 1, pattern)

        val days = generator.generateRange(sched, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 3))

        days.forEach { day ->
            day.source shouldBe CustodyDaySource.BASE_SCHEDULE
            day.overrideId shouldBe null
        }
    }

    // ---- generateProjection ----

    test("generateProjection covers approximately 12 months") {
        val pattern = listOf(parentA, parentB)
        val sched = schedule(LocalDate.of(2026, 1, 1), 2, pattern)

        val days = generator.generateProjection(sched, LocalDate.of(2026, 1, 1), 12)

        // ~365 days
        (days.size >= 364) shouldBe true
        (days.size <= 366) shouldBe true
    }
})
