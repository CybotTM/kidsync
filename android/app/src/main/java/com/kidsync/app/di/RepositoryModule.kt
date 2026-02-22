package com.kidsync.app.di

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.data.local.dao.ExpenseDao
import com.kidsync.app.data.local.dao.KeyAttestationDao
import android.content.Context
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.dao.SyncStateDao
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.repository.AuthRepositoryImpl
import com.kidsync.app.data.repository.BlobRepositoryImpl
import com.kidsync.app.data.repository.BucketRepositoryImpl
import com.kidsync.app.data.repository.ExpenseRepositoryImpl
import com.kidsync.app.data.repository.SyncRepositoryImpl
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BlobRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.repository.ExpenseRepository
import com.kidsync.app.domain.repository.SyncRepository
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import com.kidsync.app.sync.filetransfer.FileTransferManager
import com.kidsync.app.sync.p2p.P2PSyncManager
import com.kidsync.app.sync.webdav.WebDavSyncManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    fun provideAuthRepository(
        apiService: ApiService,
        cryptoManager: CryptoManager,
        keyManager: KeyManager,
        @Named("encrypted_prefs") encryptedPrefs: SharedPreferences,
        @Named("prefs") prefs: SharedPreferences,
        @Named("baseUrl") baseUrl: String,
        okHttpClientManager: OkHttpClientManager
    ): AuthRepository {
        return AuthRepositoryImpl(
            apiService = apiService,
            cryptoManager = cryptoManager,
            keyManager = keyManager,
            encryptedPrefs = encryptedPrefs,
            prefs = prefs,
            serverOrigin = baseUrl,
            okHttpClientManager = okHttpClientManager
        )
    }

    @Provides
    @Singleton
    fun provideSyncRepository(
        apiService: ApiService,
        opLogDao: OpLogDao,
        syncStateDao: SyncStateDao
    ): SyncRepository {
        return SyncRepositoryImpl(
            apiService = apiService,
            opLogDao = opLogDao,
            syncStateDao = syncStateDao
        )
    }

    @Provides
    @Singleton
    fun provideExpenseRepository(
        expenseDao: ExpenseDao
    ): ExpenseRepository {
        return ExpenseRepositoryImpl(expenseDao)
    }

    @Provides
    @Singleton
    fun provideBlobRepository(
        apiService: ApiService
    ): BlobRepository {
        return BlobRepositoryImpl(apiService)
    }

    @Provides
    @Singleton
    fun provideFileTransferManager(
        opLogDao: OpLogDao,
        keyManager: KeyManager,
        hashChainVerifier: HashChainVerifier
    ): FileTransferManager {
        return FileTransferManager(opLogDao, keyManager, hashChainVerifier)
    }

    @Provides
    @Singleton
    fun provideWebDavSyncManager(
        opLogDao: OpLogDao,
        syncStateDao: SyncStateDao,
        json: Json,
        hashChainVerifier: HashChainVerifier
    ): WebDavSyncManager {
        return WebDavSyncManager(opLogDao, syncStateDao, json, hashChainVerifier)
    }

    @Provides
    @Singleton
    fun provideP2PSyncManager(
        @ApplicationContext context: Context,
        opLogDao: OpLogDao,
        keyManager: KeyManager,
        cryptoManager: CryptoManager,
        hashChainVerifier: HashChainVerifier
    ): P2PSyncManager {
        return P2PSyncManager(
            context = context,
            opLogDao = opLogDao,
            keyManager = keyManager,
            cryptoManager = cryptoManager,
            hashChainVerifier = hashChainVerifier
        )
    }

    @Provides
    @Singleton
    fun provideBucketRepository(
        apiService: ApiService,
        bucketDao: BucketDao,
        keyAttestationDao: KeyAttestationDao,
        cryptoManager: CryptoManager,
        @Named("encrypted_prefs") encryptedPrefs: SharedPreferences
    ): BucketRepository {
        return BucketRepositoryImpl(
            apiService = apiService,
            bucketDao = bucketDao,
            keyAttestationDao = keyAttestationDao,
            cryptoManager = cryptoManager,
            encryptedPrefs = encryptedPrefs
        )
    }
}
