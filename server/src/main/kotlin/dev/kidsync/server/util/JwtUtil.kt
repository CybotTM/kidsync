package dev.kidsync.server.util

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import dev.kidsync.server.AppConfig
import java.util.*

class JwtUtil(private val config: AppConfig) {

    private val algorithm = Algorithm.HMAC256(config.jwtSecret)

    /**
     * Generate a short-lived access JWT.
     */
    fun generateAccessToken(userId: String, deviceId: String, familyIds: List<String>): String {
        val now = Date()
        val expiry = Date(now.time + config.jwtAccessExpirationMinutes * 60 * 1000)

        return JWT.create()
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .withSubject(userId)
            .withClaim("did", deviceId)
            .withClaim("fam", familyIds)
            .withIssuedAt(now)
            .withExpiresAt(expiry)
            .sign(algorithm)
    }

    /**
     * Generate a long-lived refresh token string (random UUID-based).
     */
    fun generateRefreshToken(): String {
        return UUID.randomUUID().toString() + "-" + UUID.randomUUID().toString()
    }

    /**
     * Verify and decode an access token.
     */
    fun verifyAccessToken(token: String): DecodedJWT {
        val verifier = JWT.require(algorithm)
            .withIssuer(config.jwtIssuer)
            .withAudience(config.jwtAudience)
            .build()
        return verifier.verify(token)
    }

    /**
     * Get the HMAC algorithm for Ktor JWT auth plugin configuration.
     */
    fun getAlgorithm(): Algorithm = algorithm
}
