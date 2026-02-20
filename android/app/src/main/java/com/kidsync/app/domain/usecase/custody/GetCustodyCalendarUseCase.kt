package com.kidsync.app.domain.usecase.custody

import com.kidsync.app.data.local.dao.CustodyScheduleDao
import com.kidsync.app.data.local.dao.OverrideDao
import com.kidsync.app.domain.model.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

/**
 * Builds a complete custody calendar for a child over a date range,
 * combining the base schedule with approved overrides.
 */
class GetCustodyCalendarUseCase @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao,
    private val overrideDao: OverrideDao,
    private val overrideResolver: OverrideResolver,
    private val patternGenerator: PatternGenerator
) {
    suspend operator fun invoke(
        childId: UUID,
        startDate: LocalDate,
        endDate: LocalDate
    ): Result<List<CustodyDay>> {
        return try {
            // 1. Find active schedule for the child
            val schedules = custodyScheduleDao.getActiveSchedulesForChild(childId)
            val activeSchedule = schedules
                .filter { it.status == "ACTIVE" }
                .maxByOrNull { Instant.parse(it.effectiveFrom) }
                ?: return Result.failure(IllegalStateException("No active custody schedule for child $childId"))

            // 2. Convert to domain model
            val schedule = activeSchedule.toDomain()

            // 3. Get approved overrides for the date range
            val overrideEntities = overrideDao.getOverridesForChildInRange(
                childId, startDate.toString(), endDate.toString()
            )
            val overrides = overrideEntities.map { it.toDomain() }

            // 4. Resolve with overrides layered on top
            val calendar = overrideResolver.resolve(schedule, overrides, startDate, endDate)

            Result.success(calendar)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun com.kidsync.app.data.local.entity.CustodyScheduleEntity.toDomain(): CustodySchedule {
        val patternUuids = kotlinx.serialization.json.Json.decodeFromString<List<String>>(patternJson)
            .map { UUID.fromString(it) }

        return CustodySchedule(
            scheduleId = scheduleId,
            childId = childId,
            anchorDate = LocalDate.parse(anchorDate),
            cycleLengthDays = cycleLengthDays,
            pattern = patternUuids,
            effectiveFrom = Instant.parse(effectiveFrom),
            timeZone = timeZone,
            status = status,
            clientTimestamp = clientTimestamp?.let { Instant.parse(it) }
        )
    }

    private fun com.kidsync.app.data.local.entity.ScheduleOverrideEntity.toDomain(): ScheduleOverride {
        return ScheduleOverride(
            overrideId = overrideId,
            type = OverrideType.valueOf(type),
            childId = childId,
            startDate = LocalDate.parse(startDate),
            endDate = LocalDate.parse(endDate),
            assignedParentId = assignedParentId,
            status = OverrideStatus.valueOf(status),
            proposerId = proposerId,
            responderId = responderId,
            note = note,
            createdAt = clientTimestamp?.let { Instant.parse(it) }
        )
    }
}
