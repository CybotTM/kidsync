package com.kidsync.app.domain.usecase.auth

import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.UserSession
import com.kidsync.app.domain.repository.AuthRepository
import java.util.UUID
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authRepository: AuthRepository,
    private val keyManager: KeyManager
) {
    suspend operator fun invoke(
        email: String,
        password: String
    ): Result<UserSession> {
        return try {
            val deviceId = keyManager.getOrCreateDeviceId()
            val sessionResult = authRepository.login(email, password, deviceId)
            if (sessionResult.isFailure) return sessionResult

            val session = sessionResult.getOrThrow()

            // Fetch and store wrapped DEK for current epoch
            keyManager.fetchAndStoreWrappedDeks(session.familyId, session.deviceId)

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
