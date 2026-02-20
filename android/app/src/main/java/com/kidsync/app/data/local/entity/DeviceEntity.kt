package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "devices",
    indices = [Index("userId"), Index("status")]
)
data class DeviceEntity(
    @PrimaryKey
    val deviceId: UUID,
    val userId: UUID,
    val name: String,
    val publicKey: String,
    val status: String,
    val registeredAt: String,
    val revokedAt: String? = null
)
