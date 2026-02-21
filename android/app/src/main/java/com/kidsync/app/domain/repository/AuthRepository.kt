package com.kidsync.app.domain.repository

import com.kidsync.app.domain.model.DeviceSession

/**
 * Authentication repository for the zero-knowledge architecture.
 *
 * Authentication is based on Ed25519 challenge-response:
 * 1. Device sends its signing public key
 * 2. Server returns a nonce
 * 3. Device signs the nonce and sends the signature
 * 4. Server verifies and issues a session token
 *
 * No emails, passwords, TOTP, or refresh tokens.
 */
interface AuthRepository {

    /**
     * Register a new device with the server.
     * Sends the Ed25519 signing key and X25519 encryption key.
     *
     * @param signingKey Base64-encoded Ed25519 public key
     * @param encryptionKey Base64-encoded X25519 public key
     * @return The server-assigned device ID
     */
    suspend fun register(signingKey: String, encryptionKey: String): Result<String>

    /**
     * Authenticate using Ed25519 challenge-response.
     * Requests a nonce, signs it, and verifies to get a session token.
     *
     * @return A DeviceSession with the session token
     */
    suspend fun authenticate(): Result<DeviceSession>

    /**
     * Get the locally stored device ID, or null if not registered.
     */
    suspend fun getDeviceId(): String?

    /**
     * Get the current session token, or null if not authenticated.
     */
    suspend fun getSessionToken(): String?

    /**
     * Check if the device is currently authenticated (has a valid session token).
     */
    suspend fun isAuthenticated(): Boolean

    /**
     * Clear the session (logout). Does not revoke any server-side state.
     */
    suspend fun clearSession()

    /**
     * Get the current DeviceSession if authenticated, or null.
     */
    suspend fun getSession(): DeviceSession?

    /**
     * Get the configured server URL.
     */
    fun getServerUrl(): String

    /**
     * Set the server URL for API calls.
     */
    fun setServerUrl(url: String)

    /**
     * Test server connectivity by calling the health endpoint.
     */
    suspend fun testConnection()

    /**
     * Logout: clear the session. Alias for clearSession().
     */
    suspend fun logout()
}
