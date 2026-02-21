package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local bucket info entity.
 *
 * A bucket is an anonymous, opaque storage namespace on the server.
 * The server does not know what a bucket represents (family, organization, etc.).
 * Client-side metadata (like a display name) is stored in encrypted ops.
 */
@Entity(tableName = "buckets")
data class BucketEntity(
    @PrimaryKey
    val bucketId: String,
    val createdByDeviceId: String,
    val createdAt: String
)
