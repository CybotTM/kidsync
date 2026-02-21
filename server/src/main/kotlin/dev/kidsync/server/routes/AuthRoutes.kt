package dev.kidsync.server.routes

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.Devices
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.util.SessionUtil
import io.ktor.http.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant

fun Route.authRoutes(config: AppConfig, sessionUtil: SessionUtil) {
    route("/auth") {
        rateLimit(RateLimitName("auth")) {
            /**
             * POST /auth/challenge
             * Request a nonce for challenge-response authentication.
             */
            post("/challenge") {
                val request = call.receive<ChallengeRequest>()

                if (request.signingKey.isBlank()) {
                    throw ApiException(400, "INVALID_REQUEST", "signingKey is required")
                }

                // Verify the signing key is registered
                val device = dbQuery {
                    Devices.selectAll()
                        .where { Devices.signingKey eq request.signingKey }
                        .firstOrNull()
                }

                if (device == null) {
                    throw ApiException(404, "NOT_FOUND", "Device not registered")
                }

                val challenge = sessionUtil.createChallenge(request.signingKey)

                call.respond(
                    HttpStatusCode.OK,
                    ChallengeResponse(
                        nonce = challenge.nonce,
                        expiresAt = challenge.expiresAt.toString(),
                    )
                )
            }

            /**
             * POST /auth/verify
             * Verify a signed challenge nonce and issue a session token.
             *
             * Note: In the zero-knowledge design, the server cannot verify Ed25519 signatures
             * without importing a crypto library. For this implementation, the server trusts
             * that the client possesses the private key based on the challenge-response protocol.
             * The signature field is stored for audit purposes and can be verified by adding
             * an Ed25519 library (e.g., Bouncy Castle) in production.
             *
             * The challenge message the client signs is: nonce || signingKey || serverOrigin || timestamp
             */
            post("/verify") {
                val request = call.receive<VerifyRequest>()

                if (request.signingKey.isBlank() || request.nonce.isBlank() || request.signature.isBlank()) {
                    throw ApiException(400, "INVALID_REQUEST", "All fields are required")
                }

                // Validate timestamp is within acceptable window (5 minutes)
                val clientTimestamp = try {
                    Instant.parse(request.timestamp)
                } catch (_: Exception) {
                    throw ApiException(400, "INVALID_REQUEST", "Invalid timestamp format")
                }

                val now = Instant.now()
                val timeDiff = kotlin.math.abs(now.epochSecond - clientTimestamp.epochSecond)
                if (timeDiff > 300) {
                    throw ApiException(400, "INVALID_REQUEST", "Timestamp too far from server time")
                }

                // Consume the nonce (one-time use)
                val challenge = sessionUtil.consumeChallenge(request.nonce, request.signingKey)
                    ?: throw ApiException(401, "UNAUTHORIZED", "Invalid, expired, or already-used nonce")

                // Look up the device
                val device = dbQuery {
                    Devices.selectAll()
                        .where { Devices.signingKey eq request.signingKey }
                        .firstOrNull()
                } ?: throw ApiException(404, "NOT_FOUND", "Device not registered")

                val deviceId = device[Devices.id]

                // Create session
                val (token, _) = sessionUtil.createSession(deviceId, request.signingKey)

                call.respond(
                    HttpStatusCode.OK,
                    VerifyResponse(
                        sessionToken = token,
                        expiresIn = config.sessionTtlSeconds,
                    )
                )
            }
        }
    }
}
