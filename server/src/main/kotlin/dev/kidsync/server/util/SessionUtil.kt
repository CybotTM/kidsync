package dev.kidsync.server.util

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.Challenges
import dev.kidsync.server.db.Sessions
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.*

// SEC3-S-01: Session tokens are hashed with SHA-256 before storage.
// The raw token is returned to the client; only the hash is persisted in the DB.

// SEC6-S-05: TODO - Add a type prefix to session tokens (e.g., "sess_") and challenge tokens
// (e.g., "chal_") to prevent cross-use attacks where a challenge token is submitted as a
// session token or vice versa.

/** SEC2-S-07: Maximum number of concurrent sessions per device */
private const val MAX_SESSIONS_PER_DEVICE = 5

/**
 * Session data stored server-side for authenticated devices.
 * SEC-S-02: Sessions are now persisted in the database to survive server restarts.
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
 * SEC-S-02: Uses database-backed storage instead of in-memory ConcurrentHashMaps
 * so sessions survive server restarts.
 */
class SessionUtil(private val config: AppConfig) {

    private val random = SecureRandom()

    /**
     * Generate a 32-byte nonce for challenge-response auth, base64url encoded.
     * Stored with 60s TTL, keyed by the signing key.
     */
    suspend fun createChallenge(signingKey: String): PendingChallenge {
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

        dbQuery {
            // Invalidate any existing challenge for this key
            Challenges.deleteWhere { Challenges.signingKey eq signingKey }

            Challenges.insert {
                it[Challenges.nonce] = nonce
                it[Challenges.signingKey] = signingKey
                it[createdAt] = now.epochSecond
                it[expiresAt] = challenge.expiresAt.epochSecond
            }
        }

        return challenge
    }

    /**
     * Consume a challenge nonce. Returns the challenge if valid (exists, not expired, matches key),
     * or null if invalid.
     *
     * SEC6-S-09: The nonce is only deleted after validation succeeds. If the signing key
     * doesn't match or the nonce is expired, it is left for the legitimate holder.
     */
    suspend fun consumeChallenge(nonce: String, signingKey: String): PendingChallenge? {
        return dbQuery {
            val row = Challenges.selectAll()
                .where { Challenges.nonce eq nonce }
                .firstOrNull()

            if (row == null) return@dbQuery null

            // SEC5-S-05/S-10: Constant-time comparison to prevent timing side-channel attacks
            if (!MessageDigest.isEqual(row[Challenges.signingKey].toByteArray(), signingKey.toByteArray())) return@dbQuery null

            val expiresAt = Instant.ofEpochSecond(row[Challenges.expiresAt])
            if (Instant.now().isAfter(expiresAt)) return@dbQuery null

            // SEC6-S-09: Only delete the nonce after validation succeeds
            Challenges.deleteWhere { Challenges.nonce eq nonce }

            PendingChallenge(
                nonce = row[Challenges.nonce],
                signingKey = row[Challenges.signingKey],
                createdAt = Instant.ofEpochSecond(row[Challenges.createdAt]),
                expiresAt = expiresAt,
            )
        }
    }

    /**
     * Create a new session for a device. Returns the opaque session token.
     *
     * SEC2-S-07: Enforces MAX_SESSIONS_PER_DEVICE limit. When the limit is reached,
     * the oldest sessions are deleted to make room for the new one.
     */
    suspend fun createSession(deviceId: String, signingKey: String): Pair<String, Session> {
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

        // SEC3-S-01: Hash the token before storing in the database
        val hashedToken = HashUtil.sha256HexString(token)

        dbQuery {
            // SEC2-S-07: Enforce max sessions per device (limit 5).
            // Delete oldest sessions if limit would be exceeded.
            val existingCount = Sessions.selectAll()
                .where { Sessions.deviceId eq deviceId }
                .count()

            if (existingCount >= MAX_SESSIONS_PER_DEVICE) {
                // Find the oldest sessions to delete
                val sessionsToKeep = MAX_SESSIONS_PER_DEVICE - 1
                val oldestTokenHashes = Sessions.selectAll()
                    .where { Sessions.deviceId eq deviceId }
                    .orderBy(Sessions.createdAt, SortOrder.ASC)
                    .limit((existingCount - sessionsToKeep).toInt())
                    .map { it[Sessions.tokenHash] }

                for (oldTokenHash in oldestTokenHashes) {
                    Sessions.deleteWhere { Sessions.tokenHash eq oldTokenHash }
                }
            }

            Sessions.insert {
                it[Sessions.tokenHash] = hashedToken
                it[Sessions.deviceId] = deviceId
                it[Sessions.signingKey] = signingKey
                it[createdAt] = now.epochSecond
                it[expiresAt] = session.expiresAt.epochSecond
            }
        }

        return Pair(token, session)
    }

    /**
     * Validate a session token. Returns the session if valid, null otherwise.
     * SEC3-S-01: Hashes the input token with SHA-256 and queries against the stored hash.
     */
    suspend fun validateSession(token: String): Session? {
        val hashedToken = HashUtil.sha256HexString(token)
        return dbQuery {
            val row = Sessions.selectAll()
                .where { Sessions.tokenHash eq hashedToken }
                .firstOrNull() ?: return@dbQuery null

            val expiresAt = Instant.ofEpochSecond(row[Sessions.expiresAt])
            if (Instant.now().isAfter(expiresAt)) {
                Sessions.deleteWhere { Sessions.tokenHash eq hashedToken }
                return@dbQuery null
            }

            Session(
                deviceId = row[Sessions.deviceId],
                signingKey = row[Sessions.signingKey],
                createdAt = Instant.ofEpochSecond(row[Sessions.createdAt]),
                expiresAt = expiresAt,
            )
        }
    }

    /**
     * SEC4-S-07: Delete all sessions for a specific device.
     * Used when a device is revoked from its last bucket to ensure session invalidation.
     */
    suspend fun deleteSessionsByDevice(deviceId: String) {
        dbQuery {
            Sessions.deleteWhere { Sessions.deviceId eq deviceId }
        }
    }

    /**
     * Remove expired sessions and challenges. Called periodically.
     */
    suspend fun cleanup() {
        val nowEpoch = Instant.now().epochSecond
        dbQuery {
            Sessions.deleteWhere { expiresAt less nowEpoch }
            Challenges.deleteWhere { Challenges.expiresAt less nowEpoch }
        }
    }
}
