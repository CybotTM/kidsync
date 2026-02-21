package com.kidsync.app.domain.usecase.custody

import com.kidsync.app.domain.model.CustodyDay
import com.kidsync.app.domain.model.CustodyDaySource
import com.kidsync.app.domain.model.CustodySchedule
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Generates custody day assignments from the base schedule pattern.
 *
 * Formula (from wire-format.md):
 *   dayIndex = (date - anchorDate) mod cycleLengthDays
 *   return pattern[dayIndex]
 *
 * The anchorDate is the reference point. Pattern repeats every cycleLengthDays.
 * dayIndex is always non-negative (uses floorMod for dates before anchorDate).
 */
class PatternGenerator @Inject constructor() {

    /**
     * Get the assigned parent for a specific date.
     */
    fun getAssignedParent(schedule: CustodySchedule, date: LocalDate): String {
        val dayIndex = computeDayIndex(schedule, date)
        return schedule.pattern[dayIndex]
    }

    /**
     * Generate custody assignments for a date range from the base schedule.
     */
    fun generateRange(
        schedule: CustodySchedule,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<CustodyDay> {
        require(!startDate.isAfter(endDate)) { "startDate must not be after endDate" }

        val days = mutableListOf<CustodyDay>()
        var current = startDate

        while (!current.isAfter(endDate)) {
            val dayIndex = computeDayIndex(schedule, current)
            days.add(
                CustodyDay(
                    date = current,
                    assignedParentId = schedule.pattern[dayIndex],
                    source = CustodyDaySource.BASE_SCHEDULE
                )
            )
            current = current.plusDays(1)
        }

        return days
    }

    /**
     * Generate a materialized 12-month projection from a given start date.
     */
    fun generateProjection(
        schedule: CustodySchedule,
        fromDate: LocalDate = LocalDate.now(),
        months: Int = 12
    ): List<CustodyDay> {
        val endDate = fromDate.plusMonths(months.toLong()).minusDays(1)
        return generateRange(schedule, fromDate, endDate)
    }

    /**
     * Compute the day index in the pattern for a given date.
     *
     * Uses Math.floorMod to handle dates before the anchor date correctly.
     * For example, if anchorDate is 2026-03-16 and date is 2026-03-14 (2 days before),
     * with cycleLengthDays=14: floorMod(-2, 14) = 12, so pattern[12] is used.
     */
    internal fun computeDayIndex(schedule: CustodySchedule, date: LocalDate): Int {
        val daysSinceAnchor = ChronoUnit.DAYS.between(schedule.anchorDate, date).toInt()
        return Math.floorMod(daysSinceAnchor, schedule.cycleLengthDays)
    }
}
