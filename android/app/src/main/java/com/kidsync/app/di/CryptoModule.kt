package com.kidsync.app.di

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.crypto.RecoveryKeyGenerator
import com.kidsync.app.crypto.RecoveryKeyGeneratorImpl
import com.kidsync.app.crypto.TinkCryptoManager
import com.kidsync.app.crypto.TinkKeyManager
import com.kidsync.app.data.local.dao.KeyEpochDao
import com.kidsync.app.data.remote.api.ApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    init {
        // SEC2-A-20: Register BouncyCastle as the FIRST security provider.
        // Android ships a limited/outdated BouncyCastle provider. Using addProvider()
        // puts it LAST, meaning the limited Android provider would be used for overlapping
        // algorithms. insertProviderAt(_, 1) ensures our full BouncyCastle is preferred.
        // If already registered (e.g., from a previous init), remove and re-insert at position 1.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }

    @Provides
    @Singleton
    fun provideCryptoManager(keyManager: dagger.Lazy<KeyManager>): CryptoManager {
        return TinkCryptoManager(keyManager)
    }

    @Provides
    @Singleton
    fun provideRecoveryKeyGenerator(): RecoveryKeyGenerator {
        return RecoveryKeyGeneratorImpl()
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
