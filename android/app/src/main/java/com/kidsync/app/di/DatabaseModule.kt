package com.kidsync.app.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kidsync.app.BuildConfig
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
        // SEC-A-01: Derive DB passphrase from Android Keystore via EncryptedSharedPreferences
        val securePrefs = EncryptedSharedPreferences.create(
            context,
            "kidsync_db_key",
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        val dbPassphrase = securePrefs.getString("db_passphrase", null) ?: run {
            val key = ByteArray(32)
            java.security.SecureRandom().nextBytes(key)
            val encoded = java.util.Base64.getEncoder().encodeToString(key)
            securePrefs.edit().putString("db_passphrase", encoded).apply()
            encoded
        }
        val passphrase = dbPassphrase.toByteArray()
        val factory = SupportOpenHelperFactory(passphrase)

        val builder = Room.databaseBuilder(
            context,
            KidSyncDatabase::class.java,
            KidSyncDatabase.DATABASE_NAME
        )
            .openHelperFactory(factory)

        // SEC-A-17: fallbackToDestructiveMigration is acceptable for debug builds only.
        // In production, proper migrations must be provided to avoid data loss.
        if (BuildConfig.DEBUG) {
            Log.w("DatabaseModule", "Using destructive migration fallback (debug build only)")
            builder.fallbackToDestructiveMigration()
        }

        return builder.build()
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

    @Provides
    fun provideCalendarEventDao(database: KidSyncDatabase): CalendarEventDao {
        return database.calendarEventDao()
    }
}
