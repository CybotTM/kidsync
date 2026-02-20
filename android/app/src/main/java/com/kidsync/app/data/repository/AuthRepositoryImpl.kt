package com.kidsync.app.data.repository

import android.content.SharedPreferences
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.*
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.domain.model.AuthTokens
import com.kidsync.app.domain.model.UserSession
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.TotpSetup
import java.util.UUID
import javax.inject.Inject

class AuthRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val prefs: SharedPreferences
) : AuthRepository {

    companion object {
        private const val PREF_USER_ID = "user_id"
        private const val PREF_FAMILY_ID = "family_id"
        private const val PREF_DEVICE_ID = "device_id"
    }

    override suspend fun register(
        email: String,
        password: String,
        displayName: String
    ): Result<UserSession> {
        return try {
            val response = apiService.register(RegisterRequest(email, password))
            if (!response.isSuccessful) {
                return Result.failure(
                    ApiException(response.code(), response.message())
                )
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            val tokens = AuthTokens(
                accessToken = body.token,
                refreshToken = body.refreshToken,
                expiresIn = 900L // 15 minutes
            )

            saveTokens(tokens)

            val userId = UUID.fromString(body.userId)
            val deviceId = UUID.fromString(body.deviceId)

            prefs.edit()
                .putString(PREF_USER_ID, body.userId)
                .putString(PREF_DEVICE_ID, body.deviceId)
                .apply()

            // Family ID is assigned later when creating/joining a family
            val session = UserSession(
                userId = userId,
                familyId = UUID(0, 0), // Placeholder until family is created/joined
                deviceId = deviceId,
                tokens = tokens
            )

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun login(
        email: String,
        password: String,
        deviceId: UUID
    ): Result<UserSession> {
        return try {
            val response = apiService.login(LoginRequest(email, password))
            if (!response.isSuccessful) {
                return Result.failure(
                    ApiException(response.code(), response.message())
                )
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            val tokens = AuthTokens(
                accessToken = body.token,
                refreshToken = body.refreshToken,
                expiresIn = 900L
            )

            saveTokens(tokens)

            val userId = UUID.fromString(body.userId)
            val familyId = prefs.getString(PREF_FAMILY_ID, null)
                ?.let { UUID.fromString(it) }
                ?: UUID(0, 0)

            prefs.edit()
                .putString(PREF_USER_ID, body.userId)
                .apply()

            val session = UserSession(
                userId = userId,
                familyId = familyId,
                deviceId = deviceId,
                tokens = tokens
            )

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun refreshToken(): Result<AuthTokens> {
        return try {
            val refreshToken = prefs.getString(AuthInterceptor.PREF_REFRESH_TOKEN, null)
                ?: return Result.failure(IllegalStateException("No refresh token available"))

            val response = apiService.refreshToken(RefreshRequest(refreshToken))
            if (!response.isSuccessful) {
                return Result.failure(
                    ApiException(response.code(), response.message())
                )
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            val tokens = AuthTokens(
                accessToken = body.token,
                refreshToken = body.refreshToken,
                expiresIn = 900L
            )

            saveTokens(tokens)
            Result.success(tokens)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout() {
        prefs.edit()
            .remove(AuthInterceptor.PREF_ACCESS_TOKEN)
            .remove(AuthInterceptor.PREF_REFRESH_TOKEN)
            .remove(PREF_USER_ID)
            .apply()
    }

    override suspend fun getSession(): UserSession? {
        val userId = prefs.getString(PREF_USER_ID, null) ?: return null
        val familyId = prefs.getString(PREF_FAMILY_ID, null) ?: return null
        val deviceId = prefs.getString(PREF_DEVICE_ID, null) ?: return null
        val accessToken = prefs.getString(AuthInterceptor.PREF_ACCESS_TOKEN, null) ?: return null
        val refreshToken = prefs.getString(AuthInterceptor.PREF_REFRESH_TOKEN, null) ?: return null

        return UserSession(
            userId = UUID.fromString(userId),
            familyId = UUID.fromString(familyId),
            deviceId = UUID.fromString(deviceId),
            tokens = AuthTokens(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = 900L
            )
        )
    }

    override suspend fun isLoggedIn(): Boolean {
        return prefs.getString(AuthInterceptor.PREF_ACCESS_TOKEN, null) != null
    }

    override suspend fun registerDevice(
        familyId: UUID,
        deviceName: String,
        publicKey: String
    ): Result<UUID> {
        return try {
            val response = apiService.registerDevice(
                RegisterDeviceRequest(deviceName = deviceName, publicKey = publicKey)
            )
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            val deviceId = UUID.fromString(body.deviceId)
            prefs.edit().putString(PREF_DEVICE_ID, body.deviceId).apply()

            Result.success(deviceId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun acceptInvite(inviteCode: String, deviceId: UUID): Result<UserSession> {
        return try {
            val familyId = prefs.getString(PREF_FAMILY_ID, null)
                ?: return Result.failure(IllegalStateException("No family context"))

            val response = apiService.joinFamily(
                familyId,
                JoinFamilyRequest(inviteToken = inviteCode, devicePublicKey = "")
            )
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            prefs.edit().putString(PREF_FAMILY_ID, body.familyId).apply()

            val session = getSession()
                ?: return Result.failure(IllegalStateException("Session not available after join"))

            Result.success(session)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setupTotp(userId: UUID): Result<TotpSetup> {
        return try {
            val response = apiService.totpSetup()
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            Result.success(TotpSetup(secret = body.secret, provisioningUri = body.qrCodeUri))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun verifyTotp(userId: UUID, code: String): Result<Boolean> {
        return try {
            val response = apiService.totpVerify(TotpVerifyRequest(code = code))
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            Result.success(body.verified)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveTokens(tokens: AuthTokens) {
        prefs.edit()
            .putString(AuthInterceptor.PREF_ACCESS_TOKEN, tokens.accessToken)
            .putString(AuthInterceptor.PREF_REFRESH_TOKEN, tokens.refreshToken)
            .apply()
    }
}

class ApiException(val code: Int, override val message: String) : Exception("HTTP $code: $message")
