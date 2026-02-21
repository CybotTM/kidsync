package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local oplog entry entity.
 *
 * In the zero-knowledge architecture, ALL metadata (entityType, entityId, operation,
 * clientTimestamp, protocolVersion, transitionTo) lives inside the encryptedPayload.
 * The server and local database only see opaque fields needed for sync mechanics.
 */
@Entity(
    tableName = "oplog",
    indices = [
        Index("bucketId"),
        Index("deviceId"),
        Index("deviceId", "deviceSequence"),
        Index("isPending")
    ]
)
data class OpLogEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val globalSequence: Long,
    val bucketId: String,
    val deviceId: String,
    val deviceSequence: Long,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val serverTimestamp: String? = null,
    val isPending: Boolean = false
)
