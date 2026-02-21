package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "schedule_overrides",
    indices = [Index("childId"), Index("status"), Index("startDate", "endDate")]
)
data class ScheduleOverrideEntity(
    @PrimaryKey
    val overrideId: String,
    val type: String,
    val childId: String,
    val startDate: String,
    val endDate: String,
    val assignedParentId: String,
    val status: String,
    val proposerId: String,
    val responderId: String? = null,
    val note: String? = null,
    val clientTimestamp: String? = null
)
