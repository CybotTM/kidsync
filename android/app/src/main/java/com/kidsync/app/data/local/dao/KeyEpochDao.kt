package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.KeyEpochEntity
import java.util.UUID

@Dao
interface KeyEpochDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEpoch(epoch: KeyEpochEntity)

    @Query("SELECT * FROM key_epochs WHERE familyId = :familyId ORDER BY epoch ASC")
    suspend fun getEpochsForFamily(familyId: UUID): List<KeyEpochEntity>

    @Query("SELECT * FROM key_epochs WHERE familyId = :familyId AND epoch = :epoch")
    suspend fun getEpoch(familyId: UUID, epoch: Int): KeyEpochEntity?

    @Query("SELECT MAX(epoch) FROM key_epochs WHERE familyId = :familyId")
    suspend fun getLatestEpoch(familyId: UUID): Int?

    @Query("DELETE FROM key_epochs")
    suspend fun deleteAll()
}
