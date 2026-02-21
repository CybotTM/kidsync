package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.OpLogEntryEntity

@Dao
interface OpLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpLogEntry(entry: OpLogEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOpLogEntries(entries: List<OpLogEntryEntity>)

    @Query(
        """
        SELECT * FROM oplog
        WHERE deviceId = :deviceId
        ORDER BY deviceSequence DESC
        LIMIT 1
        """
    )
    suspend fun getLastOpForDevice(deviceId: String): OpLogEntryEntity?

    @Query(
        """
        SELECT * FROM oplog
        WHERE bucketId = :bucketId AND isPending = 1
        ORDER BY deviceSequence ASC
        """
    )
    suspend fun getPendingOps(bucketId: String): List<OpLogEntryEntity>

    @Query(
        """
        UPDATE oplog
        SET isPending = 0, globalSequence = :globalSequence, serverTimestamp = :serverTimestamp
        WHERE id = :id
        """
    )
    suspend fun markAsSynced(id: Long, globalSequence: Long, serverTimestamp: String)

    @Query(
        """
        SELECT * FROM oplog
        WHERE bucketId = :bucketId
        ORDER BY globalSequence ASC
        """
    )
    suspend fun getAllOpsForBucket(bucketId: String): List<OpLogEntryEntity>

    @Query(
        """
        SELECT * FROM oplog
        WHERE bucketId = :bucketId AND globalSequence > :afterSequence
        ORDER BY globalSequence ASC
        LIMIT :limit
        """
    )
    suspend fun getOpsAfterSequence(
        bucketId: String,
        afterSequence: Long,
        limit: Int = 100
    ): List<OpLogEntryEntity>

    @Query("SELECT COUNT(*) FROM oplog WHERE bucketId = :bucketId AND isPending = 1")
    suspend fun getPendingOpsCount(bucketId: String): Int

    @Query("DELETE FROM oplog")
    suspend fun deleteAll()
}
