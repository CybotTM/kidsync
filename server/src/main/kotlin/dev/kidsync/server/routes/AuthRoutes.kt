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
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.jetbrains.exposed.sql.selectAll
import java.time.Instant
import java.util.Base64

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
             * The server verifies the Ed25519 signature using BouncyCastle to prove
             * the client possesses the private key corresponding to the registered public key.
             *
             * The challenge message the client signs is: nonce || signingKey || serverOrigin || timestamp
             */
            post("/verify") {
                val request = call.receive<VerifyRequest>()

                if (request.signingKey.isBlank() || request.nonce.isBlank() || request.signature.isBlank()) {
                    throw ApiException(400, "INVALID_REQUEST", "All fields are required")
                }

                // Validate timestamp is within acceptable window (60 seconds)
                val clientTimestamp = try {
                    Instant.parse(request.timestamp)
                } catch (_: Exception) {
                    throw ApiException(400, "INVALID_REQUEST", "Invalid timestamp format")
                }

                val now = Instant.now()
                val timeDiff = kotlin.math.abs(now.epochSecond - clientTimestamp.epochSecond)
                if (timeDiff > 60) {
                    throw ApiException(400, "INVALID_REQUEST", "Timestamp too far from server time")
                }

                // Consume the nonce (one-time use)
                val challenge = sessionUtil.consumeChallenge(request.nonce, request.signingKey)
                    ?: throw ApiException(401, "UNAUTHORIZED", "Invalid, expired, or already-used nonce")

                // Verify Ed25519 signature
                val challengeMessage = "${request.nonce}${request.signingKey}${config.serverOrigin}${request.timestamp}"
                val messageBytes = challengeMessage.toByteArray(Charsets.UTF_8)

                val signatureBytes = try {
                    Base64.getDecoder().decode(request.signature)
                } catch (_: Exception) {
                    throw ApiException(401, "UNAUTHORIZED", "Invalid signature encoding")
                }

                val publicKeyEncoded = try {
                    Base64.getDecoder().decode(request.signingKey)
                } catch (_: Exception) {
                    throw ApiException(401, "UNAUTHORIZED", "Invalid signing key encoding")
                }

                // Extract raw 32-byte Ed25519 key from X.509 SubjectPublicKeyInfo encoding
                // JDK Ed25519 public keys are encoded as: 12-byte X.509 prefix + 32-byte raw key
                val rawKeyBytes = if (publicKeyEncoded.size == 44) {
                    publicKeyEncoded.copyOfRange(12, 44)
                } else if (publicKeyEncoded.size == 32) {
                    publicKeyEncoded
                } else {
                    throw ApiException(401, "UNAUTHORIZED", "Invalid public key format")
                }

                val verified = try {
                    val pubKeyParams = Ed25519PublicKeyParameters(rawKeyBytes, 0)
                    val verifier = Ed25519Signer()
                    verifier.init(false, pubKeyParams)
                    verifier.update(messageBytes, 0, messageBytes.size)
                    verifier.verifySignature(signatureBytes)
                } catch (_: Exception) {
                    false
                }

                if (!verified) {
                    throw ApiException(401, "UNAUTHORIZED", "Invalid signature")
                }

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
