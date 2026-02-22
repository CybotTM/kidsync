package com.kidsync.app.data.repository

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.ChallengeRequest
import com.kidsync.app.data.remote.dto.RegisterRequest
import com.kidsync.app.data.remote.dto.VerifyRequest
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.di.OkHttpClientManager
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
    private val serverOrigin: String,
    private val okHttpClientManager: OkHttpClientManager? = null
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
        // SEC6-A-02: Synchronized access to the read-check-modify pattern on
        // consecutiveFailures and lastAttemptTimeMs. @Volatile alone is insufficient
        // because the compound check (read failures, compute backoff, compare elapsed,
        // update lastAttemptTimeMs) must be atomic.
        synchronized(this) {
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
        }

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
                // SEC5-A-03: Use commit() instead of apply() for session token storage
                // to ensure the token is persisted before returning to the caller
                encryptedPrefs.edit()
                    .putString(AuthInterceptor.PREF_SESSION_TOKEN, verifyResponse.sessionToken)
                    .putLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, System.currentTimeMillis() + verifyResponse.expiresIn * 1000L)
                    .commit()

                val deviceId = keyManager.getDeviceId()
                    ?: throw IllegalStateException("Device ID not found after authentication")

                // SEC3-A-17: Reset backoff on success
                synchronized(this@AuthRepositoryImpl) { consecutiveFailures = 0 }

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
            synchronized(this@AuthRepositoryImpl) { consecutiveFailures++ }
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
        // SEC5-A-04: Use commit() to ensure session is cleared synchronously
        encryptedPrefs.edit()
            .remove(AuthInterceptor.PREF_SESSION_TOKEN)
            .remove(AuthInterceptor.PREF_SESSION_EXPIRES_AT)
            .commit()
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

    // SEC6-A-03: Notify OkHttpClientManager when the server URL changes so it can
    // rebuild the OkHttpClient with updated CertificatePinner for the new host.
    override fun setServerUrl(url: String) {
        encryptedPrefs.edit()
            .putString(PREF_SERVER_URL, url)
            .commit()
        okHttpClientManager?.onServerUrlChanged(url)
    }

    override suspend fun testConnection() {
        val response = apiService.health()
        if (!response.isSuccessful) {
            throw ApiException(response.code(), response.message())
        }
    }

    // SEC-A-16: Clear all sensitive data on logout, not just the session token
    // SEC5-A-04: Use commit() for synchronous clearing
    // SEC5-A-12: Also clear non-encrypted SharedPreferences (kidsync_prefs)
    override suspend fun logout() {
        clearSession()
        encryptedPrefs.edit().clear().commit()
        prefs.edit().clear().commit()
    }

    /**
     * Build the challenge message to sign.
     *
     * SEC5-A-14: Uses length-prefix encoding for variable-length fields to prevent
     * boundary ambiguity attacks. Each variable-length field is preceded by a 4-byte
     * big-endian length prefix. Fixed-length fields (nonce: 32 bytes, signingKey: 32 bytes)
     * are not prefixed since their boundaries are unambiguous.
     *
     * Format:
     *   nonce (32 bytes raw)
     *   || signingKey (32 bytes raw)
     *   || len(serverOrigin) (4 bytes big-endian) || serverOrigin (UTF-8)
     *   || len(timestamp) (4 bytes big-endian) || timestamp (UTF-8)
     *
     * This eliminates the theoretical boundary ambiguity between serverOrigin and
     * timestamp that existed in the old simple concatenation format.
     */
    internal fun buildChallengeMessage(
        nonce: String,
        signingKey: String,
        serverOrigin: String,
        timestamp: String
    ): ByteArray {
        val nonceBytes = Base64.getDecoder().decode(nonce)
        // SEC6-A-11: Validate nonce and signing key sizes to prevent truncated or padded values
        require(nonceBytes.size == 32) { "Invalid nonce size: ${nonceBytes.size}" }
        val keyBytes = Base64.getDecoder().decode(signingKey)
        require(keyBytes.size == 32) { "Invalid signing key size: ${keyBytes.size}" }
        val originBytes = serverOrigin.toByteArray(Charsets.UTF_8)
        val timestampBytes = timestamp.toByteArray(Charsets.UTF_8)

        // SEC5-A-14: Length-prefix variable-length fields with 4-byte big-endian length
        return nonceBytes + keyBytes +
            lengthPrefix(originBytes) + originBytes +
            lengthPrefix(timestampBytes) + timestampBytes
    }

    /**
     * Encode a 4-byte big-endian length prefix for a byte array.
     */
    private fun lengthPrefix(data: ByteArray): ByteArray {
        val len = data.size
        return byteArrayOf(
            (len shr 24 and 0xFF).toByte(),
            (len shr 16 and 0xFF).toByte(),
            (len shr 8 and 0xFF).toByte(),
            (len and 0xFF).toByte()
        )
    }
}

class ApiException(val code: Int, override val message: String) : Exception("HTTP $code: $message")
