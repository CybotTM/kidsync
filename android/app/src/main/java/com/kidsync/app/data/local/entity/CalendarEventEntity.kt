package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "calendar_events",
    indices = [Index("childId")]
)
data class CalendarEventEntity(
    @PrimaryKey
    val eventId: String,
    val childId: String,
    val title: String,
    val description: String? = null,
    val startTime: String,
    val endTime: String,
    val allDay: Boolean = false,
    val location: String? = null,
    val createdBy: String = "",
    val clientTimestamp: String
)
