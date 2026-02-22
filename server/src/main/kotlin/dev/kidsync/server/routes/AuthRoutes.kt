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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SEC2-S-11: Per-signing-key rate limiter for challenge requests.
 * Limits each signing key to MAX_CHALLENGES_PER_KEY_PER_MINUTE requests per minute.
 * SEC3-S-02: Only tracks registered signing keys (called after device existence check).
 * Includes periodic eviction of stale entries to prevent unbounded memory growth.
 */
private object ChallengeKeyRateLimiter {
    private const val MAX_CHALLENGES_PER_KEY_PER_MINUTE = 5
    private const val WINDOW_MS = 60_000L

    private data class KeyWindow(val count: AtomicInteger = AtomicInteger(0), val windowStart: AtomicLong = AtomicLong(0))
    private val windows = ConcurrentHashMap<String, KeyWindow>()

    fun checkAndIncrement(signingKey: String): Boolean {
        // SEC3-S-02: Evict stale entries older than 2x the window period
        cleanup()

        val now = System.currentTimeMillis()
        val window = windows.computeIfAbsent(signingKey) { KeyWindow() }

        // SEC3-S-03: Synchronized block to prevent TOCTOU race on window reset-or-increment
        synchronized(window) {
            val start = window.windowStart.get()
            if (now - start > WINDOW_MS) {
                window.windowStart.set(now)
                window.count.set(1)
                return true
            }

            val current = window.count.incrementAndGet()
            return current <= MAX_CHALLENGES_PER_KEY_PER_MINUTE
        }
    }

    /**
     * SEC3-S-02: Remove entries where windowStart is older than 2x the window period
     * to prevent unbounded memory growth from accumulated signing keys.
     */
    fun cleanup() {
        val now = System.currentTimeMillis()
        val threshold = now - (2 * WINDOW_MS)
        windows.entries.removeIf { (_, window) ->
            window.windowStart.get() < threshold
        }
    }
}

fun Route.authRoutes(config: AppConfig, sessionUtil: SessionUtil) {
    route("/auth") {
        rateLimit(RateLimitName("auth")) {
            /**
             * POST /auth/challenge
             * Request a nonce for challenge-response authentication.
             *
             * SEC4-S-08: KNOWN BEHAVIOR - The device existence check before nonce generation
             * creates a timing difference between registered and unregistered signing keys.
             * This is accepted because signing keys are public (stored in the Devices table
             * as public keys), so their existence is not a secret. The per-key rate limiter
             * further mitigates any enumeration concerns.
             */
            post("/challenge") {
                val request = call.receive<ChallengeRequest>()

                if (request.signingKey.isBlank()) {
                    throw ApiException(400, "INVALID_REQUEST", "signingKey is required")
                }

                // Verify the signing key is registered BEFORE rate limiting
                // SEC3-S-02: Only track registered keys to prevent unbounded memory growth
                // from unregistered/random signing keys.
                val device = dbQuery {
                    Devices.selectAll()
                        .where { Devices.signingKey eq request.signingKey }
                        .firstOrNull()
                }

                if (device == null) {
                    throw ApiException(404, "UNKNOWN_SIGNING_KEY", "Device not registered")
                }

                // SEC2-S-11: Per-signing-key rate limit to prevent challenge DoS
                if (!ChallengeKeyRateLimiter.checkAndIncrement(request.signingKey)) {
                    throw ApiException(429, "RATE_LIMITED", "Too many challenge requests for this key")
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
             *
             * SEC4-S-17: DESIGN NOTE - Challenge nonces are not bound to the client's IP address.
             * This is partially mitigated by: (1) TLS preventing MITM interception of nonces,
             * (2) the 60-second TTL limiting the replay window, (3) one-time use enforcement
             * (nonce is consumed on verification attempt), and (4) the signature including the
             * server origin which prevents cross-origin replay. IP binding was considered but
             * would break mobile clients that switch networks (WiFi<->cellular) mid-auth.
             */
            post("/verify") {
                val request = call.receive<VerifyRequest>()

                if (request.signingKey.isBlank() || request.nonce.isBlank() || request.signature.isBlank()) {
                    throw ApiException(400, "INVALID_REQUEST", "All fields are required")
                }

                // SEC-M2: Validate timestamp is within acceptable window.
                // Allow +-1 time step (30s each) beyond the base 60s window to account
                // for clock skew between devices, giving an effective 90-second tolerance.
                val clientTimestamp = try {
                    Instant.parse(request.timestamp)
                } catch (_: Exception) {
                    throw ApiException(400, "INVALID_REQUEST", "Invalid timestamp format")
                }

                val now = Instant.now()
                val timeDiff = kotlin.math.abs(now.epochSecond - clientTimestamp.epochSecond)
                val maxTimestampDrift = 90L // 60s base + 30s tolerance for clock skew
                if (timeDiff > maxTimestampDrift) {
                    throw ApiException(400, "TIMESTAMP_DRIFT", "Timestamp too far from server time")
                }

                // Consume the nonce (one-time use)
                val challenge = sessionUtil.consumeChallenge(request.nonce, request.signingKey)
                    ?: throw ApiException(401, "NONCE_EXPIRED", "Invalid, expired, or already-used nonce")

                // Verify Ed25519 signature
                // Challenge message is binary concatenation:
                // base64Decode(nonce) || base64Decode(signingKey) || utf8(serverOrigin) || utf8(timestamp)
                val nonceBytes = try {
                    // Nonce is base64url-encoded (no padding)
                    Base64.getUrlDecoder().decode(request.nonce)
                } catch (_: Exception) {
                    throw ApiException(400, "INVALID_REQUEST", "Invalid nonce encoding")
                }
                val signingKeyBytes = try {
                    Base64.getDecoder().decode(request.signingKey)
                } catch (_: Exception) {
                    throw ApiException(400, "INVALID_REQUEST", "Invalid signing key encoding")
                }
                val originBytes = config.serverOrigin.toByteArray(Charsets.UTF_8)
                val timestampBytes = request.timestamp.toByteArray(Charsets.UTF_8)
                val messageBytes = nonceBytes + signingKeyBytes + originBytes + timestampBytes

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
                    throw ApiException(401, "INVALID_SIGNATURE", "Invalid signature")
                }

                // Look up the device
                val device = dbQuery {
                    Devices.selectAll()
                        .where { Devices.signingKey eq request.signingKey }
                        .firstOrNull()
                } ?: throw ApiException(404, "NOT_FOUND", "Device not registered")

                val deviceId = device[Devices.id]

                // SEC5-S-01: Invalidate all existing sessions for this device on re-authentication.
                // This prevents session accumulation and reduces the attack surface of stolen tokens.
                sessionUtil.deleteSessionsByDevice(deviceId)

                // Create session
                val (token, _) = sessionUtil.createSession(deviceId, request.signingKey)

                call.respond(
                    HttpStatusCode.OK,
                    VerifyResponse(
                        sessionToken = token,
                        expiresIn = config.sessionTtlSeconds.toInt(),
                    )
                )
            }
        }
    }
}
