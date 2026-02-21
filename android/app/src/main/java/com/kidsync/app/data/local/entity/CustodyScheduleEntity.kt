package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "custody_schedules",
    indices = [Index("childId"), Index("status")]
)
data class CustodyScheduleEntity(
    @PrimaryKey
    val scheduleId: String,
    val childId: String,
    val anchorDate: String,
    val cycleLengthDays: Int,
    val patternJson: String,
    val effectiveFrom: String,
    val timeZone: String,
    val status: String = "ACTIVE",
    val clientTimestamp: String? = null
)
