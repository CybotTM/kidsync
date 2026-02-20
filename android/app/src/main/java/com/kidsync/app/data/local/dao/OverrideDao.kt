package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.kidsync.app.data.local.entity.ScheduleOverrideEntity
import java.util.UUID

@Dao
interface OverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverride(override: ScheduleOverrideEntity)

    @Update
    suspend fun updateOverride(override: ScheduleOverrideEntity)

    @Query("SELECT * FROM schedule_overrides WHERE overrideId = :overrideId")
    suspend fun getOverrideById(overrideId: UUID): ScheduleOverrideEntity?

    @Query(
        """
        SELECT * FROM schedule_overrides
        WHERE childId = :childId
          AND endDate >= :rangeStart
          AND startDate <= :rangeEnd
        """
    )
    suspend fun getOverridesForChildInRange(
        childId: UUID,
        rangeStart: String,
        rangeEnd: String
    ): List<ScheduleOverrideEntity>

    @Query("SELECT * FROM schedule_overrides WHERE childId = :childId AND status = 'APPROVED'")
    suspend fun getApprovedOverridesForChild(childId: UUID): List<ScheduleOverrideEntity>

    @Query("SELECT * FROM schedule_overrides")
    suspend fun getAllOverrides(): List<ScheduleOverrideEntity>

    @Query("DELETE FROM schedule_overrides")
    suspend fun deleteAll()
}
