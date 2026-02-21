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
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CryptoModule {

    init {
        // Ensure BouncyCastle is registered as a security provider for Ed25519 operations.
        // Android includes a limited BouncyCastle; we add the full one for Ed25519 support.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Provides
    @Singleton
    fun provideCryptoManager(keyManager: dagger.Lazy<KeyManager>): CryptoManager {
        return TinkCryptoManager(keyManager)
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
