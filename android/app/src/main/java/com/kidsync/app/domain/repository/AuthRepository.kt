package com.kidsync.app.domain.repository

import com.kidsync.app.domain.model.AuthTokens
import com.kidsync.app.domain.model.UserSession
import java.util.UUID

interface AuthRepository {
    suspend fun register(email: String, password: String, displayName: String): Result<UserSession>
    suspend fun login(email: String, password: String, deviceId: UUID): Result<UserSession>
    suspend fun refreshToken(): Result<AuthTokens>
    suspend fun logout()
    suspend fun getSession(): UserSession?
    suspend fun isLoggedIn(): Boolean
    suspend fun registerDevice(
        familyId: UUID,
        deviceName: String,
        publicKey: String
    ): Result<UUID>

    suspend fun acceptInvite(inviteCode: String, deviceId: UUID): Result<UserSession>
    suspend fun setupTotp(userId: UUID): Result<TotpSetup>
    suspend fun verifyTotp(userId: UUID, code: String): Result<Boolean>
}

data class TotpSetup(
    val secret: String,
    val provisioningUri: String
)
