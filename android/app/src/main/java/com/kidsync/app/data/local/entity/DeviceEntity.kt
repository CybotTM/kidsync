package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local device identity entity.
 *
 * In the zero-knowledge architecture, devices are identified by their
 * cryptographic keys, not by user IDs or names. The server does not
 * know which user a device belongs to.
 */
@Entity(
    tableName = "devices",
    indices = [Index("signingKey")]
)
data class DeviceEntity(
    @PrimaryKey
    val deviceId: String,
    val signingKey: String,
    val encryptionKey: String,
    val createdAt: String
)
