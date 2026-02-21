package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.BucketEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BucketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBucket(bucket: BucketEntity)

    @Query("SELECT * FROM buckets WHERE bucketId = :bucketId")
    suspend fun getBucket(bucketId: String): BucketEntity?

    @Query("SELECT * FROM buckets")
    suspend fun getAllBuckets(): List<BucketEntity>

    @Query("SELECT * FROM buckets")
    fun observeAllBuckets(): Flow<List<BucketEntity>>

    @Query("DELETE FROM buckets WHERE bucketId = :bucketId")
    suspend fun deleteBucket(bucketId: String)

    @Query("DELETE FROM buckets")
    suspend fun deleteAll()
}
