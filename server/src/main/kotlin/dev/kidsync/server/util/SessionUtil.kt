package dev.kidsync.server.util

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.Challenges
import dev.kidsync.server.db.Sessions
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import java.security.SecureRandom
import java.time.Instant
import java.util.*

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
     * or null if invalid. The nonce is always deleted (one-time use).
     */
    suspend fun consumeChallenge(nonce: String, signingKey: String): PendingChallenge? {
        return dbQuery {
            val row = Challenges.selectAll()
                .where { Challenges.nonce eq nonce }
                .firstOrNull()

            // Always delete the nonce (one-time use)
            Challenges.deleteWhere { Challenges.nonce eq nonce }

            if (row == null) return@dbQuery null
            if (row[Challenges.signingKey] != signingKey) return@dbQuery null
            val expiresAt = Instant.ofEpochSecond(row[Challenges.expiresAt])
            if (Instant.now().isAfter(expiresAt)) return@dbQuery null

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

        dbQuery {
            Sessions.insert {
                it[Sessions.token] = token
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
     */
    suspend fun validateSession(token: String): Session? {
        return dbQuery {
            val row = Sessions.selectAll()
                .where { Sessions.token eq token }
                .firstOrNull() ?: return@dbQuery null

            val expiresAt = Instant.ofEpochSecond(row[Sessions.expiresAt])
            if (Instant.now().isAfter(expiresAt)) {
                Sessions.deleteWhere { Sessions.token eq token }
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
     * Remove expired sessions and challenges. Called periodically.
     */
    suspend fun cleanup() {
        val nowEpoch = Instant.now().epochSecond
        dbQuery {
            Sessions.deleteWhere { Sessions.expiresAt less nowEpoch }
            Challenges.deleteWhere { Challenges.expiresAt less nowEpoch }
        }
    }
}
