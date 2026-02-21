package com.kidsync.app.domain.usecase.auth

import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.repository.AuthRepository
import javax.inject.Inject

/**
 * Handles re-authentication via Ed25519 challenge-response.
 *
 * Called when the session token expires. The flow is:
 * 1. Send signing public key to server
 * 2. Receive a nonce
 * 3. Sign the nonce with the private key
 * 4. Send signature to server for verification
 * 5. Receive a new session token
 *
 * After authentication, fetch wrapped DEKs for known buckets.
 */
class AuthenticateUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val keyManager: KeyManager
) {
    suspend operator fun invoke(): Result<DeviceSession> {
        return try {
            // 1. Authenticate via challenge-response
            val authResult = authRepository.authenticate()
            if (authResult.isFailure) return authResult

            val session = authResult.getOrThrow()

            // DEK fetching is handled per-bucket by the sync flow,
            // not as part of authentication.

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
