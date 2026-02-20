package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.InfoBankEntryEntity
import java.util.UUID

@Dao
interface InfoBankDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: InfoBankEntryEntity)

    @Query("SELECT * FROM info_bank_entries WHERE childId = :childId AND deleted = 0 ORDER BY clientTimestamp DESC")
    suspend fun getEntriesForChild(childId: UUID): List<InfoBankEntryEntity>

    @Query("SELECT * FROM info_bank_entries WHERE childId = :childId AND category = :category AND deleted = 0 ORDER BY clientTimestamp DESC")
    suspend fun getEntriesForChildByCategory(childId: UUID, category: String): List<InfoBankEntryEntity>

    @Query("SELECT * FROM info_bank_entries WHERE entryId = :entryId AND deleted = 0")
    suspend fun getEntryById(entryId: UUID): InfoBankEntryEntity?

    @Query("SELECT * FROM info_bank_entries WHERE deleted = 0 ORDER BY clientTimestamp DESC")
    suspend fun getAllEntries(): List<InfoBankEntryEntity>

    @Query("UPDATE info_bank_entries SET deleted = 1 WHERE entryId = :entryId")
    suspend fun markDeleted(entryId: UUID)

    @Query("DELETE FROM info_bank_entries")
    suspend fun deleteAllEntries()
}
