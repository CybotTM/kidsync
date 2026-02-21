package com.kidsync.app.di

import android.content.Context
import androidx.room.Room
import com.kidsync.app.data.local.KidSyncDatabase
import com.kidsync.app.data.local.dao.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): KidSyncDatabase {
        // SQLCipher passphrase would be derived from the device key in production
        val passphrase = "kidsync-dev-passphrase".toByteArray()
        val factory = SupportOpenHelperFactory(passphrase)

        return Room.databaseBuilder(
            context,
            KidSyncDatabase::class.java,
            KidSyncDatabase.DATABASE_NAME
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideCustodyScheduleDao(database: KidSyncDatabase): CustodyScheduleDao {
        return database.custodyScheduleDao()
    }

    @Provides
    fun provideOverrideDao(database: KidSyncDatabase): OverrideDao {
        return database.overrideDao()
    }

    @Provides
    fun provideExpenseDao(database: KidSyncDatabase): ExpenseDao {
        return database.expenseDao()
    }

    @Provides
    fun provideOpLogDao(database: KidSyncDatabase): OpLogDao {
        return database.opLogDao()
    }

    @Provides
    fun provideSyncStateDao(database: KidSyncDatabase): SyncStateDao {
        return database.syncStateDao()
    }

    @Provides
    fun provideKeyEpochDao(database: KidSyncDatabase): KeyEpochDao {
        return database.keyEpochDao()
    }

    @Provides
    fun provideKeyAttestationDao(database: KidSyncDatabase): KeyAttestationDao {
        return database.keyAttestationDao()
    }

    @Provides
    fun provideBucketDao(database: KidSyncDatabase): BucketDao {
        return database.bucketDao()
    }

    @Provides
    fun provideInfoBankDao(database: KidSyncDatabase): InfoBankDao {
        return database.infoBankDao()
    }
}
