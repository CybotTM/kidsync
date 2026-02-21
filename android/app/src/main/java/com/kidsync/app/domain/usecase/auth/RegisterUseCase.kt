package com.kidsync.app.domain.usecase.auth

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.repository.AuthRepository
import java.util.Base64
import javax.inject.Inject

/**
 * Handles first-time device registration in the zero-knowledge architecture.
 *
 * Flow:
 * 1. Generate Ed25519 signing key pair (or retrieve existing)
 * 2. Derive X25519 encryption key pair from the same seed
 * 3. Register both public keys with the server
 * 4. Store the server-assigned device ID
 * 5. Authenticate via challenge-response to get a session token
 */
class RegisterUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager
) {
    suspend operator fun invoke(): Result<DeviceSession> {
        return try {
            // 1. Generate or retrieve Ed25519 signing key pair
            val (signingPublicKey, signingPrivateKey) = keyManager.getOrCreateSigningKeyPair()

            // 2. Derive X25519 encryption public key from the Ed25519 public key
            val encryptionPublicKey = cryptoManager.ed25519PublicToX25519(signingPublicKey)

            // 3. Register with server (send both public keys)
            val signingKeyBase64 = Base64.getEncoder().encodeToString(signingPublicKey)
            val encryptionKeyBase64 = Base64.getEncoder().encodeToString(encryptionPublicKey)

            val registerResult = authRepository.register(signingKeyBase64, encryptionKeyBase64)
            if (registerResult.isFailure) {
                return Result.failure(registerResult.exceptionOrNull()!!)
            }

            val deviceId = registerResult.getOrThrow()

            // 4. Store the server-assigned device ID
            keyManager.storeDeviceId(deviceId)

            // 5. Authenticate via challenge-response to get a session token
            val authResult = authRepository.authenticate()
            if (authResult.isFailure) {
                return Result.failure(authResult.exceptionOrNull()!!)
            }

            Result.success(authResult.getOrThrow())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
