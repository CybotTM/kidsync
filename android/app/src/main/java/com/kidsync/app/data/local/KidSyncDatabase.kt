package com.kidsync.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kidsync.app.data.local.converter.Converters
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.local.entity.*

/**
 * SEC2-A-17: Room migration strategy for production:
 * - Schema is exported (exportSchema = true) to enable auto-migration verification.
 * - autoMigrations should be defined for each version bump (e.g., 4->5).
 * - fallbackToDestructiveMigration() is only used in debug builds (see DatabaseModule).
 * - For release builds, if a migration is missing, Room will throw an
 *   IllegalStateException at runtime rather than silently destroying data.
 *
 * Before each release with schema changes:
 * 1. Increment the version number
 * 2. Add an AutoMigration spec or manual Migration object
 * 3. Test migration with MigrationTestHelper in androidTest
 */
@Database(
    entities = [
        CustodyScheduleEntity::class,
        ScheduleOverrideEntity::class,
        ExpenseEntity::class,
        ExpenseStatusEntity::class,
        OpLogEntryEntity::class,
        SyncStateEntity::class,
        KeyEpochEntity::class,
        DeviceEntity::class,
        KeyAttestationEntity::class,
        BucketEntity::class,
        InfoBankEntryEntity::class,
        CalendarEventEntity::class
    ],
    version = 4,
    exportSchema = true,
    // SEC2-A-17: Add auto-migrations here as schema evolves. Example:
    // autoMigrations = [AutoMigration(from = 4, to = 5)]
)
@TypeConverters(Converters::class)
abstract class KidSyncDatabase : RoomDatabase() {

    abstract fun custodyScheduleDao(): CustodyScheduleDao
    abstract fun overrideDao(): OverrideDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun opLogDao(): OpLogDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun keyEpochDao(): KeyEpochDao
    abstract fun keyAttestationDao(): KeyAttestationDao
    abstract fun bucketDao(): BucketDao
    abstract fun infoBankDao(): InfoBankDao
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        const val DATABASE_NAME = "kidsync_db"
    }
}
