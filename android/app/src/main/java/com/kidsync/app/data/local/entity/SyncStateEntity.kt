package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey
    val bucketId: String,
    val lastGlobalSequence: Long,
    val lastSyncTimestamp: String,
    val serverCheckpointHash: String? = null
)
