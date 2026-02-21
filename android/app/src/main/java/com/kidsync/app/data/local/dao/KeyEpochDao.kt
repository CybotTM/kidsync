package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.KeyEpochEntity

@Dao
interface KeyEpochDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpoch(epoch: KeyEpochEntity)

    @Query("SELECT * FROM key_epochs WHERE bucketId = :bucketId ORDER BY epoch ASC")
    suspend fun getEpochsForBucket(bucketId: String): List<KeyEpochEntity>

    @Query("SELECT * FROM key_epochs WHERE bucketId = :bucketId AND epoch = :epoch")
    suspend fun getEpoch(bucketId: String, epoch: Int): KeyEpochEntity?

    @Query("SELECT MAX(epoch) FROM key_epochs WHERE bucketId = :bucketId")
    suspend fun getLatestEpoch(bucketId: String): Int?

    @Query("DELETE FROM key_epochs")
    suspend fun deleteAll()
}
