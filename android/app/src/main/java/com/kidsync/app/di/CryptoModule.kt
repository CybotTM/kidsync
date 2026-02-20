package com.kidsync.app.di

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.crypto.TinkCryptoManager
import com.kidsync.app.crypto.TinkKeyManager
import com.kidsync.app.data.local.dao.KeyEpochDao
import com.kidsync.app.data.remote.api.ApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    @Provides
    @Singleton
    fun provideCryptoManager(): CryptoManager {
        return TinkCryptoManager()
    }

    @Provides
    @Singleton
    fun provideKeyManager(
        @Named("encrypted_prefs") encryptedPrefs: SharedPreferences,
        keyEpochDao: KeyEpochDao,
        cryptoManager: CryptoManager,
        apiService: ApiService
    ): KeyManager {
        return TinkKeyManager(
            encryptedPrefs = encryptedPrefs,
            keyEpochDao = keyEpochDao,
            cryptoManager = cryptoManager,
            apiService = apiService
        )
    }
}
