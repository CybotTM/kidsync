package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.CalendarEventEntity

@Dao
interface CalendarEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: CalendarEventEntity)

    @Query("SELECT * FROM calendar_events WHERE childId = :childId ORDER BY startTime")
    suspend fun getEventsForChild(childId: String): List<CalendarEventEntity>

    @Query("SELECT * FROM calendar_events WHERE eventId = :eventId")
    suspend fun getEventById(eventId: String): CalendarEventEntity?

    @Query("DELETE FROM calendar_events WHERE eventId = :eventId")
    suspend fun deleteEvent(eventId: String)

    @Query("SELECT * FROM calendar_events ORDER BY startTime")
    suspend fun getAllEvents(): List<CalendarEventEntity>
}
