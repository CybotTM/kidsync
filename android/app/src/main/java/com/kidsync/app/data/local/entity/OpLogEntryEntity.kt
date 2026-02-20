package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "oplog",
    indices = [
        Index("familyId"),
        Index("deviceId"),
        Index("deviceId", "deviceSequence"),
        Index("isPending")
    ]
)
data class OpLogEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val globalSequence: Long,
    val familyId: UUID,
    val deviceId: UUID,
    val deviceSequence: Long,
    val entityType: String,
    val entityId: UUID,
    val operation: String,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val clientTimestamp: String,
    val serverTimestamp: String? = null,
    val transitionTo: String? = null,
    val isPending: Boolean = false
)
