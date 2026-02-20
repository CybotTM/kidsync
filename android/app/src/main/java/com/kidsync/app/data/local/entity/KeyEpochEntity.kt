package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import java.util.UUID

@Entity(
    tableName = "key_epochs",
    primaryKeys = ["epoch", "familyId"],
    indices = [Index("familyId")]
)
data class KeyEpochEntity(
    val epoch: Int,
    val familyId: UUID,
    val wrappedDek: String,
    val createdAt: String,
    val revokedDeviceId: String? = null
)
