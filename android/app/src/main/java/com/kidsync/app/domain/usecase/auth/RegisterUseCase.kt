package com.kidsync.app.domain.usecase.auth

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.UserSession
import com.kidsync.app.domain.repository.AuthRepository
import javax.inject.Inject

class RegisterUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager
) {
    suspend operator fun invoke(
        email: String,
        password: String,
        displayName: String
    ): Result<UserSession> {
        return try {
            // 1. Generate device key pair (X25519 for DEK wrapping)
            val keyPair = cryptoManager.generateX25519KeyPair()

            // 2. Register with server
            val sessionResult = authRepository.register(email, password, displayName)
            if (sessionResult.isFailure) return sessionResult

            val session = sessionResult.getOrThrow()

            // 3. Store key pair securely
            keyManager.storeDeviceKeyPair(session.deviceId, keyPair)

            // 4. Register device with server (send public key)
            val deviceResult = authRepository.registerDevice(
                familyId = session.familyId,
                deviceName = android.os.Build.MODEL,
                publicKey = cryptoManager.encodePublicKey(keyPair.publicKey)
            )

            if (deviceResult.isFailure) {
                return Result.failure(deviceResult.exceptionOrNull()!!)
            }

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
