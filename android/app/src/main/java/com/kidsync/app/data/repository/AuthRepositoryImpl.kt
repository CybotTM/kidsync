package com.kidsync.app.data.repository

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.ChallengeRequest
import com.kidsync.app.data.remote.dto.RegisterRequest
import com.kidsync.app.data.remote.dto.VerifyRequest
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.repository.AuthRepository
import java.time.Instant
import java.util.Base64
import javax.inject.Inject

/**
 * AuthRepository implementation for the zero-knowledge architecture.
 *
 * Uses Ed25519 challenge-response authentication:
 * 1. Send signing public key to get a nonce
 * 2. Sign nonce || signingKey || serverOrigin || timestamp
 * 3. Send signature to verify and get session token
 *
 * No emails, no passwords, no TOTP, no refresh tokens.
 */
class AuthRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val prefs: SharedPreferences,
    private val serverOrigin: String
) : AuthRepository {

    companion object {
        private const val PREF_DEVICE_ID = "device_id"
    }

    override suspend fun register(signingKey: String, encryptionKey: String): Result<String> {
        return try {
            val response = apiService.register(
                RegisterRequest(
                    signingKey = signingKey,
                    encryptionKey = encryptionKey
                )
            )

            val deviceId = response.deviceId

            // Store device ID
            prefs.edit()
                .putString(PREF_DEVICE_ID, deviceId)
                .apply()
            keyManager.storeDeviceId(deviceId)

            Result.success(deviceId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun authenticate(): Result<DeviceSession> {
        return try {
            val (signingPublicKey, signingPrivateKey) = keyManager.getOrCreateSigningKeyPair()
            val signingKeyBase64 = Base64.getEncoder().encodeToString(signingPublicKey)

            // 1. Request challenge nonce
            val challengeResponse = apiService.requestChallenge(
                ChallengeRequest(signingKey = signingKeyBase64)
            )

            val nonce = challengeResponse.nonce
            val timestamp = Instant.now().toString()

            // 2. Construct challenge message: nonce || signingKey || serverOrigin || timestamp
            val message = buildChallengeMessage(nonce, signingKeyBase64, serverOrigin, timestamp)

            // 3. Sign the challenge
            val signature = cryptoManager.signEd25519(message, signingPrivateKey)
            val signatureBase64 = Base64.getEncoder().encodeToString(signature)

            // 4. Verify with server
            val verifyResponse = apiService.verifyChallenge(
                VerifyRequest(
                    signingKey = signingKeyBase64,
                    nonce = nonce,
                    signature = signatureBase64,
                    timestamp = timestamp
                )
            )

            // 5. Store session token
            prefs.edit()
                .putString(AuthInterceptor.PREF_SESSION_TOKEN, verifyResponse.sessionToken)
                .apply()

            val deviceId = keyManager.getDeviceId()
                ?: throw IllegalStateException("Device ID not found after authentication")

            Result.success(
                DeviceSession(
                    deviceId = deviceId,
                    sessionToken = verifyResponse.sessionToken,
                    expiresIn = verifyResponse.expiresIn
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDeviceId(): String? {
        return prefs.getString(PREF_DEVICE_ID, null) ?: keyManager.getDeviceId()
    }

    override suspend fun getSessionToken(): String? {
        return prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
    }

    override suspend fun isAuthenticated(): Boolean {
        return prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) != null
    }

    override suspend fun clearSession() {
        prefs.edit()
            .remove(AuthInterceptor.PREF_SESSION_TOKEN)
            .apply()
    }

    /**
     * Build the challenge message to sign.
     * Format: nonce || signingKey || serverOrigin || timestamp
     * Each component is concatenated as UTF-8 bytes with "|" separator.
     */
    private fun buildChallengeMessage(
        nonce: String,
        signingKey: String,
        serverOrigin: String,
        timestamp: String
    ): ByteArray {
        return "$nonce|$signingKey|$serverOrigin|$timestamp".toByteArray(Charsets.UTF_8)
    }
}

class ApiException(val code: Int, override val message: String) : Exception("HTTP $code: $message")
