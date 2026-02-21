package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "key_epochs",
    primaryKeys = ["epoch", "bucketId"],
    indices = [Index("bucketId")]
)
data class KeyEpochEntity(
    val epoch: Int,
    val bucketId: String,
    val wrappedDek: String,
    val createdAt: String,
    val revokedDeviceId: String? = null
)
