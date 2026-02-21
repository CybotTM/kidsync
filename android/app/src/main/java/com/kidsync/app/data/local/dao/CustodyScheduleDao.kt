package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.CustodyScheduleEntity

@Dao
interface CustodyScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: CustodyScheduleEntity)

    @Query("SELECT * FROM custody_schedules WHERE childId = :childId AND status = 'ACTIVE'")
    suspend fun getActiveSchedulesForChild(childId: String): List<CustodyScheduleEntity>

    @Query("SELECT * FROM custody_schedules WHERE scheduleId = :scheduleId")
    suspend fun getScheduleById(scheduleId: String): CustodyScheduleEntity?

    @Query("UPDATE custody_schedules SET status = :status WHERE scheduleId = :scheduleId")
    suspend fun updateStatus(scheduleId: String, status: String)

    @Query("SELECT * FROM custody_schedules")
    suspend fun getAllSchedules(): List<CustodyScheduleEntity>

    @Query("DELETE FROM custody_schedules")
    suspend fun deleteAll()
}
