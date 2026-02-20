package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val familyId: UUID,
    val lastGlobalSequence: Long,
    val lastSyncTimestamp: String,
    val serverCheckpointHash: String? = null
)
