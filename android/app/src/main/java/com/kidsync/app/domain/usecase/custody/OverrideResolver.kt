package com.kidsync.app.domain.usecase.custody

import com.kidsync.app.domain.model.*
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * Resolves custody day assignments by layering overrides on top of the base schedule.
 *
 * Override precedence (highest to lowest):
 *   COURT_ORDER (4) > HOLIDAY (3) > SWAP_REQUEST (2) > MANUAL (1) > base schedule
 *
 * Only APPROVED overrides are applied. Multiple overlapping overrides of different types
 * use the highest-precedence one. Same-type overlapping overrides use the latest clientTimestamp.
 */
class OverrideResolver @Inject constructor(
    private val patternGenerator: PatternGenerator
) {
    /**
     * Resolve custody days for a date range, applying approved overrides on top of the base schedule.
     */
    fun resolve(
        schedule: CustodySchedule,
        overrides: List<ScheduleOverride>,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<CustodyDay> {
        // 1. Generate base schedule
        val baseDays = patternGenerator.generateRange(schedule, startDate, endDate)

        // 2. Filter to only APPROVED overrides
        val approvedOverrides = overrides.filter { it.status == OverrideStatus.APPROVED }

        if (approvedOverrides.isEmpty()) return baseDays

        // 3. For each day, find the highest-precedence applicable override
        return baseDays.map { baseDay ->
            val applicableOverrides = approvedOverrides.filter { override ->
                !baseDay.date.isBefore(override.startDate) && !baseDay.date.isAfter(override.endDate)
            }

            if (applicableOverrides.isEmpty()) {
                baseDay
            } else {
                // Pick highest precedence override
                val winner = applicableOverrides
                    .sortedWith(
                        compareByDescending<ScheduleOverride> { it.type.precedence }
                            .thenByDescending { it.createdAt }
                    )
                    .first()

                CustodyDay(
                    date = baseDay.date,
                    assignedParentId = winner.assignedParentId,
                    source = CustodyDaySource.OVERRIDE,
                    overrideId = winner.overrideId
                )
            }
        }
    }
}
