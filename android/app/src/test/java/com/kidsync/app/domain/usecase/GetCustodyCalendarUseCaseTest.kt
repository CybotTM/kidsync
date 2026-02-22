package com.kidsync.app.domain.usecase

import com.kidsync.app.data.local.dao.CustodyScheduleDao
import com.kidsync.app.data.local.dao.OverrideDao
import com.kidsync.app.data.local.entity.CustodyScheduleEntity
import com.kidsync.app.data.local.entity.ScheduleOverrideEntity
import com.kidsync.app.domain.model.*
import com.kidsync.app.domain.usecase.custody.GetCustodyCalendarUseCase
import com.kidsync.app.domain.usecase.custody.OverrideResolver
import com.kidsync.app.domain.usecase.custody.PatternGenerator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import java.time.Instant
import java.time.LocalDate

class GetCustodyCalendarUseCaseTest : FunSpec({

    val custodyScheduleDao = mockk<CustodyScheduleDao>()
    val overrideDao = mockk<OverrideDao>()
    val overrideResolver = mockk<OverrideResolver>()
    val patternGenerator = mockk<PatternGenerator>()

    fun createUseCase() = GetCustodyCalendarUseCase(
        custodyScheduleDao, overrideDao, overrideResolver, patternGenerator
    )

    beforeEach {
        clearAllMocks()
    }

    val childId = "child-1"
    val startDate = LocalDate.of(2026, 3, 1)
    val endDate = LocalDate.of(2026, 3, 7)

    fun makeScheduleEntity(
        scheduleId: String = "sched-1",
        status: String = "ACTIVE",
        effectiveFrom: String = "2026-01-01T00:00:00Z"
    ) = CustodyScheduleEntity(
        scheduleId = scheduleId,
        childId = childId,
        anchorDate = "2026-01-01",
        cycleLengthDays = 14,
        patternJson = "[\"parent-A\",\"parent-A\",\"parent-B\",\"parent-B\",\"parent-A\",\"parent-A\",\"parent-B\",\"parent-B\",\"parent-A\",\"parent-A\",\"parent-B\",\"parent-B\",\"parent-A\",\"parent-A\"]",
        effectiveFrom = effectiveFrom,
        timeZone = "America/New_York",
        status = status
    )

    fun makeBaseDays(start: LocalDate, end: LocalDate): List<CustodyDay> {
        val days = mutableListOf<CustodyDay>()
        var current = start
        var idx = 0
        val pattern = listOf("parent-A", "parent-A", "parent-B", "parent-B", "parent-A", "parent-A", "parent-B")
        while (!current.isAfter(end)) {
            days.add(CustodyDay(current, pattern[idx % pattern.size], CustodyDaySource.BASE_SCHEDULE))
            current = current.plusDays(1)
            idx++
        }
        return days
    }

    // ── Basic calendar generation ───────────────────────────────────────────

    test("returns calendar days from schedule with no overrides") {
        val scheduleEntity = makeScheduleEntity()
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns listOf(scheduleEntity)
        coEvery { overrideDao.getOverridesForChildInRange(childId, any(), any()) } returns emptyList()

        val baseDays = makeBaseDays(startDate, endDate)
        every { overrideResolver.resolve(any(), emptyList(), startDate, endDate) } returns baseDays

        val useCase = createUseCase()
        val result = useCase(childId, startDate, endDate)

        result.isSuccess shouldBe true
        result.getOrNull()!!.size shouldBe 7
        result.getOrNull()!![0].source shouldBe CustodyDaySource.BASE_SCHEDULE
    }

    test("returns failure when no active schedule exists") {
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns emptyList()

        val useCase = createUseCase()
        val result = useCase(childId, startDate, endDate)

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "No active custody schedule"
    }

    test("returns failure when only inactive schedules exist") {
        val inactive = makeScheduleEntity(status = "SUPERSEDED")
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns listOf(inactive)

        val useCase = createUseCase()
        val result = useCase(childId, startDate, endDate)

        result.isFailure shouldBe true
    }

    // ── Schedule selection ───────────────────────────────────────────────────

    test("selects the most recent active schedule when multiple exist") {
        val older = makeScheduleEntity(scheduleId = "sched-old", effectiveFrom = "2025-01-01T00:00:00Z")
        val newer = makeScheduleEntity(scheduleId = "sched-new", effectiveFrom = "2026-02-01T00:00:00Z")
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns listOf(older, newer)
        coEvery { overrideDao.getOverridesForChildInRange(childId, any(), any()) } returns emptyList()

        val baseDays = makeBaseDays(startDate, endDate)
        every { overrideResolver.resolve(any(), any(), startDate, endDate) } returns baseDays

        val useCase = createUseCase()
        val result = useCase(childId, startDate, endDate)

        result.isSuccess shouldBe true

        // Verify that the resolve was called (with the newer schedule)
        verify { overrideResolver.resolve(match { it.scheduleId == "sched-new" }, any(), startDate, endDate) }
    }

    // ── Override integration ────────────────────────────────────────────────

    test("applies approved overrides to calendar") {
        val scheduleEntity = makeScheduleEntity()
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns listOf(scheduleEntity)

        val overrideEntity = ScheduleOverrideEntity(
            overrideId = "override-1",
            type = "MANUAL_OVERRIDE",
            childId = childId,
            startDate = "2026-03-03",
            endDate = "2026-03-04",
            assignedParentId = "parent-B",
            status = "APPROVED",
            proposerId = "parent-A"
        )
        coEvery { overrideDao.getOverridesForChildInRange(childId, any(), any()) } returns listOf(overrideEntity)

        val daysWithOverride = makeBaseDays(startDate, endDate).mapIndexed { idx, day ->
            if (day.date == LocalDate.of(2026, 3, 3) || day.date == LocalDate.of(2026, 3, 4)) {
                CustodyDay(day.date, "parent-B", CustodyDaySource.OVERRIDE, "override-1")
            } else day
        }
        every { overrideResolver.resolve(any(), any(), startDate, endDate) } returns daysWithOverride

        val useCase = createUseCase()
        val result = useCase(childId, startDate, endDate)

        result.isSuccess shouldBe true
        val overrideDays = result.getOrNull()!!.filter { it.source == CustodyDaySource.OVERRIDE }
        overrideDays.size shouldBe 2
    }

    // ── Error handling ──────────────────────────────────────────────────────

    test("DAO exception returns failure") {
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } throws RuntimeException("DB corrupted")

        val useCase = createUseCase()
        val result = useCase(childId, startDate, endDate)

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "DB corrupted"
    }

    test("override DAO exception returns failure") {
        val scheduleEntity = makeScheduleEntity()
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns listOf(scheduleEntity)
        coEvery { overrideDao.getOverridesForChildInRange(childId, any(), any()) } throws RuntimeException("Override table missing")

        val useCase = createUseCase()
        val result = useCase(childId, startDate, endDate)

        result.isFailure shouldBe true
    }

    // ── Single day range ────────────────────────────────────────────────────

    test("single day range returns one entry") {
        val singleDay = LocalDate.of(2026, 3, 5)
        val scheduleEntity = makeScheduleEntity()
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns listOf(scheduleEntity)
        coEvery { overrideDao.getOverridesForChildInRange(childId, any(), any()) } returns emptyList()

        val baseDays = listOf(CustodyDay(singleDay, "parent-A", CustodyDaySource.BASE_SCHEDULE))
        every { overrideResolver.resolve(any(), emptyList(), singleDay, singleDay) } returns baseDays

        val useCase = createUseCase()
        val result = useCase(childId, singleDay, singleDay)

        result.isSuccess shouldBe true
        result.getOrNull()!!.size shouldBe 1
    }

    // ── Pattern parsing ─────────────────────────────────────────────────────

    test("parses patternJson correctly from schedule entity") {
        val scheduleEntity = makeScheduleEntity()
        coEvery { custodyScheduleDao.getActiveSchedulesForChild(childId) } returns listOf(scheduleEntity)
        coEvery { overrideDao.getOverridesForChildInRange(childId, any(), any()) } returns emptyList()

        val capturedSchedule = slot<CustodySchedule>()
        every { overrideResolver.resolve(capture(capturedSchedule), any(), any(), any()) } returns emptyList()

        val useCase = createUseCase()
        useCase(childId, startDate, endDate)

        capturedSchedule.captured.pattern.size shouldBe 14
        capturedSchedule.captured.cycleLengthDays shouldBe 14
        capturedSchedule.captured.pattern[0] shouldBe "parent-A"
        capturedSchedule.captured.pattern[2] shouldBe "parent-B"
    }
})
