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

    @Query("SELECT * FROM oplog WHERE bucketId = :bucketId AND deviceId = :deviceId AND deviceSequence = :deviceSequence LIMIT 1")
    suspend fun findOp(bucketId: String, deviceId: String, deviceSequence: Long): OpLogEntryEntity?

    @Query("SELECT COUNT(*) FROM oplog WHERE bucketId = :bucketId")
    suspend fun getOpsCountForBucket(bucketId: String): Long

    @Query("SELECT * FROM oplog WHERE bucketId = :bucketId ORDER BY globalSequence DESC LIMIT 1")
    suspend fun getLastOpForBucket(bucketId: String): OpLogEntryEntity?

    @Query("DELETE FROM oplog")
    suspend fun deleteAll()

    /**
     * Get the maximum deviceSequence for each device within a bucket.
     * Used by P2P sendHandshake to avoid loading all ops.
     */
    @Query("SELECT MAX(globalSequence) FROM oplog WHERE bucketId = :bucketId")
    suspend fun getMaxGlobalSequenceForBucket(bucketId: String): Long?

    /**
     * Get the last known currentHash for each device in a bucket.
     * Used by hash chain verification when receiving ops from external sources
     * (P2P, WebDAV, file import) to verify chain continuity against local state.
     *
     * Returns a list of entities (one per device); callers should extract
     * deviceId -> currentHash from the result.
     */
    @Query("""
        SELECT o.* FROM oplog o
        INNER JOIN (
            SELECT deviceId, MAX(deviceSequence) AS maxSeq
            FROM oplog
            WHERE bucketId = :bucketId
            GROUP BY deviceId
        ) latest ON o.deviceId = latest.deviceId AND o.deviceSequence = latest.maxSeq
        WHERE o.bucketId = :bucketId
    """)
    suspend fun getLastOpsPerDeviceForBucket(bucketId: String): List<OpLogEntryEntity>
}
