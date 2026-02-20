package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.SyncStateEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface SyncStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(syncState: SyncStateEntity)

    @Query("SELECT * FROM sync_state WHERE familyId = :familyId")
    suspend fun getSyncState(familyId: UUID): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE familyId = :familyId")
    fun observeSyncState(familyId: UUID): Flow<SyncStateEntity?>

    @Query("DELETE FROM sync_state")
    suspend fun deleteAll()
}
