package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "schedule_overrides",
    indices = [Index("childId"), Index("status"), Index("startDate", "endDate")]
)
data class ScheduleOverrideEntity(
    @PrimaryKey
    val overrideId: UUID,
    val type: String,
    val childId: UUID,
    val startDate: String,
    val endDate: String,
    val assignedParentId: UUID,
    val status: String,
    val proposerId: UUID,
    val responderId: UUID? = null,
    val note: String? = null,
    val clientTimestamp: String? = null
)
