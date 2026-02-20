package com.kidsync.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kidsync.app.data.local.converter.Converters
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.*

@Database(
    entities = [
        FamilyEntity::class,
        FamilyMemberEntity::class,
        CustodyScheduleEntity::class,
        ScheduleOverrideEntity::class,
        ExpenseEntity::class,
        ExpenseStatusEntity::class,
        OpLogEntryEntity::class,
        SyncStateEntity::class,
        KeyEpochEntity::class,
        DeviceEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class KidSyncDatabase : RoomDatabase() {

    abstract fun custodyScheduleDao(): CustodyScheduleDao
    abstract fun overrideDao(): OverrideDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun opLogDao(): OpLogDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun keyEpochDao(): KeyEpochDao

    companion object {
        const val DATABASE_NAME = "kidsync_db"
    }
}
