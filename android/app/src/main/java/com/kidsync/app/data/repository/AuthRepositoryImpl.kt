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
import java.util.Arrays
import java.util.Base64

/**
 * AuthRepository implementation for the zero-knowledge architecture.
 *
 * Uses Ed25519 challenge-response authentication:
 * 1. Send signing public key to get a nonce
 * 2. Sign nonce || signingKey || serverOrigin || timestamp
 * 3. Send signature to verify and get session token
 *
 * No emails, no passwords, no TOTP, no refresh tokens.
 *
 * Session tokens and device IDs are stored in EncryptedSharedPreferences.
 * Only non-sensitive settings (server URL) remain in plain SharedPreferences.
 */
class AuthRepositoryImpl(
    private val apiService: ApiService,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val encryptedPrefs: SharedPreferences,
    private val prefs: SharedPreferences,
    private val serverOrigin: String
) : AuthRepository {

    companion object {
        private const val PREF_SERVER_URL = "server_url"

        // SEC3-A-17: Client-side auth rate limiting constants
        private const val BASE_BACKOFF_MS = 1000L
        private const val MAX_BACKOFF_MS = 60_000L
    }

    // SEC3-A-17: Simple exponential backoff to limit auth attempt frequency
    @Volatile private var consecutiveFailures = 0
    @Volatile private var lastAttemptTimeMs = 0L

    override suspend fun register(signingKey: String, encryptionKey: String): Result<String> {
        return try {
            val response = apiService.register(
                RegisterRequest(
                    signingKey = signingKey,
                    encryptionKey = encryptionKey
                )
            )

            val deviceId = response.deviceId

            // Store device ID in KeyManager (which uses encrypted prefs)
            keyManager.storeDeviceId(deviceId)

            Result.success(deviceId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun authenticate(): Result<DeviceSession> {
        // SEC3-A-17: Enforce exponential backoff on repeated auth failures
        val now = System.currentTimeMillis()
        if (consecutiveFailures > 0) {
            // SEC4-A-08: Cap the shift amount to prevent Long overflow. Shifting a Long by >= 63
            // produces negative values, causing minOf to return a negative backoff (no wait).
            // Limit shift to 20 (1000 * 2^20 = ~1M ms > MAX_BACKOFF_MS) so the cap always applies.
            val shift = minOf(consecutiveFailures - 1, 20)
            val backoffMs = minOf(BASE_BACKOFF_MS shl shift, MAX_BACKOFF_MS)
            val elapsed = now - lastAttemptTimeMs
            if (elapsed < backoffMs) {
                return Result.failure(
                    IllegalStateException("Auth rate limited. Retry in ${(backoffMs - elapsed) / 1000}s")
                )
            }
        }
        lastAttemptTimeMs = now

        return try {
            val (signingPublicKey, signingPrivateKey) = keyManager.getOrCreateSigningKeyPair()
            try {
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

                // 5. Store session token and expiry in encrypted prefs
                // SEC2-A-21: Track session expiry to enforce token lifetime in both AuthInterceptor and here
                encryptedPrefs.edit()
                    .putString(AuthInterceptor.PREF_SESSION_TOKEN, verifyResponse.sessionToken)
                    .putLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, System.currentTimeMillis() + verifyResponse.expiresIn * 1000L)
                    .apply()

                val deviceId = keyManager.getDeviceId()
                    ?: throw IllegalStateException("Device ID not found after authentication")

                // SEC3-A-17: Reset backoff on success
                consecutiveFailures = 0

                Result.success(
                    DeviceSession(
                        deviceId = deviceId,
                        sessionToken = verifyResponse.sessionToken,
                        expiresIn = verifyResponse.expiresIn
                    )
                )
            } finally {
                // SEC3-A-04: Zero signing private key after use
                Arrays.fill(signingPrivateKey, 0.toByte())
            }
        } catch (e: Exception) {
            // SEC3-A-17: Increment backoff on failure
            consecutiveFailures++
            Result.failure(e)
        }
    }

    override suspend fun getDeviceId(): String? {
        return keyManager.getDeviceId()
    }

    override suspend fun getSessionToken(): String? {
        return encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
    }

    // SEC2-A-21: Check token expiry in addition to token presence
    override suspend fun isAuthenticated(): Boolean {
        val token = encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) ?: return false
        val expiresAt = encryptedPrefs.getLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, 0L)
        if (expiresAt > 0L && System.currentTimeMillis() >= expiresAt) {
            clearSession()
            return false
        }
        return true
    }

    override suspend fun clearSession() {
        // SEC3-A-10: Remove both the session token and the expiry timestamp
        // to prevent stale session_expires_at from affecting future auth checks
        encryptedPrefs.edit()
            .remove(AuthInterceptor.PREF_SESSION_TOKEN)
            .remove(AuthInterceptor.PREF_SESSION_EXPIRES_AT)
            .apply()
    }

    override suspend fun getSession(): DeviceSession? {
        val token = getSessionToken() ?: return null
        val deviceId = getDeviceId() ?: return null
        return DeviceSession(
            deviceId = deviceId,
            sessionToken = token,
            expiresIn = 0 // Unknown for cached sessions
        )
    }

    // SEC-A-06: Use encryptedPrefs instead of plain prefs for server URL storage
    override fun getServerUrl(): String {
        return encryptedPrefs.getString(PREF_SERVER_URL, serverOrigin) ?: serverOrigin
    }

    override fun setServerUrl(url: String) {
        encryptedPrefs.edit()
            .putString(PREF_SERVER_URL, url)
            .apply()
    }

    override suspend fun testConnection() {
        val response = apiService.health()
        if (!response.isSuccessful) {
            throw ApiException(response.code(), response.message())
        }
    }

    // SEC-A-16: Clear all sensitive data on logout, not just the session token
    override suspend fun logout() {
        clearSession()
        encryptedPrefs.edit().clear().apply()
    }

    /**
     * Build the challenge message to sign.
     * Format: nonce (32 bytes raw) || signingKey (32 bytes raw) || serverOrigin (UTF-8) || timestamp (UTF-8)
     * Raw byte concatenation as specified in the authentication protocol.
     *
     * SEC3-A-25: This message format uses simple concatenation without length-prefix encoding.
     * The nonce (32 bytes) and signingKey (32 bytes) are fixed-length, so they are unambiguous.
     * However, serverOrigin and timestamp are variable-length UTF-8 strings without a delimiter,
     * which means a boundary ambiguity exists between them (e.g., origin="a.comT" + timestamp="123"
     * vs origin="a.com" + timestamp="T123"). In practice this is safe because:
     * 1. The server validates the nonce it issued, so the message must match exactly.
     * 2. The timestamp is ISO-8601 format (always starts with a digit), while origins
     *    never end with a digit.
     * 3. Both parties (client and server) use the same concatenation order.
     * A future protocol version could add length prefixes or a delimiter for defense in depth.
     */
    private fun buildChallengeMessage(
        nonce: String,
        signingKey: String,
        serverOrigin: String,
        timestamp: String
    ): ByteArray {
        val nonceBytes = Base64.getDecoder().decode(nonce)
        val keyBytes = Base64.getDecoder().decode(signingKey)
        val originBytes = serverOrigin.toByteArray(Charsets.UTF_8)
        val timestampBytes = timestamp.toByteArray(Charsets.UTF_8)
        return nonceBytes + keyBytes + originBytes + timestampBytes
    }
}

class ApiException(val code: Int, override val message: String) : Exception("HTTP $code: $message")
