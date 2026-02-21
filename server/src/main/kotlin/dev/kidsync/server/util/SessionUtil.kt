package dev.kidsync.server.util

import dev.kidsync.server.AppConfig
import java.security.SecureRandom
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Session data stored server-side for authenticated devices.
 */
data class Session(
    val deviceId: String,
    val signingKey: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * Pending challenge nonce stored server-side.
 */
data class PendingChallenge(
    val nonce: String,
    val signingKey: String,
    val createdAt: Instant,
    val expiresAt: Instant,
)

/**
 * Manages opaque session tokens and challenge nonces.
 * Uses in-memory ConcurrentHashMaps with TTL-based expiry.
 */
class SessionUtil(private val config: AppConfig) {

    private val sessions = ConcurrentHashMap<String, Session>()
    private val challenges = ConcurrentHashMap<String, PendingChallenge>()
    private val random = SecureRandom()

    /**
     * Generate a 32-byte nonce for challenge-response auth, base64url encoded.
     * Stored with 60s TTL, keyed by the signing key.
     */
    fun createChallenge(signingKey: String): PendingChallenge {
        // Invalidate any existing challenge for this key
        challenges.entries.removeIf { it.value.signingKey == signingKey }

        val nonceBytes = ByteArray(32)
        random.nextBytes(nonceBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val now = Instant.now()
        val challenge = PendingChallenge(
            nonce = nonce,
            signingKey = signingKey,
            createdAt = now,
            expiresAt = now.plusSeconds(config.challengeTtlSeconds),
        )
        challenges[nonce] = challenge
        return challenge
    }

    /**
     * Consume a challenge nonce. Returns the challenge if valid (exists, not expired, matches key),
     * or null if invalid. The nonce is always deleted (one-time use).
     */
    fun consumeChallenge(nonce: String, signingKey: String): PendingChallenge? {
        val challenge = challenges.remove(nonce) ?: return null
        if (challenge.signingKey != signingKey) return null
        if (Instant.now().isAfter(challenge.expiresAt)) return null
        return challenge
    }

    /**
     * Create a new session for a device. Returns the opaque session token.
     */
    fun createSession(deviceId: String, signingKey: String): Pair<String, Session> {
        val tokenBytes = ByteArray(32)
        random.nextBytes(tokenBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
        val now = Instant.now()
        val session = Session(
            deviceId = deviceId,
            signingKey = signingKey,
            createdAt = now,
            expiresAt = now.plusSeconds(config.sessionTtlSeconds),
        )
        sessions[token] = session
        return Pair(token, session)
    }

    /**
     * Validate a session token. Returns the session if valid, null otherwise.
     */
    fun validateSession(token: String): Session? {
        val session = sessions[token] ?: return null
        if (Instant.now().isAfter(session.expiresAt)) {
            sessions.remove(token)
            return null
        }
        return session
    }

    /**
     * Remove expired sessions and challenges. Called periodically.
     */
    fun cleanup() {
        val now = Instant.now()
        sessions.entries.removeIf { now.isAfter(it.value.expiresAt) }
        challenges.entries.removeIf { now.isAfter(it.value.expiresAt) }
    }
}
