package com.kidsync.app.domain.usecase.auth

import com.kidsync.app.domain.model.AuthTokens
import com.kidsync.app.domain.repository.AuthRepository
import javax.inject.Inject

class RefreshTokenUseCase @Inject constructor(
    private val authRepository: AuthRepository
) {
    suspend operator fun invoke(): Result<AuthTokens> {
        return authRepository.refreshToken()
    }
}
