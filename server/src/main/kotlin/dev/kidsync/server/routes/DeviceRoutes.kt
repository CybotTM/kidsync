package dev.kidsync.server.routes

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.util.SessionUtil
import dev.kidsync.server.util.ValidationUtil
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SEC-S-10: IP-based rate limiter for device registration.
 * Limits each IP to 5 registrations per hour to prevent mass device creation attacks.
 */
object DeviceRegistrationRateLimiter {
    private const val MAX_REGISTRATIONS_PER_IP_PER_HOUR = 5
    private const val WINDOW_MS = 3_600_000L

    private data class IpWindow(val count: AtomicInteger = AtomicInteger(0), val windowStart: AtomicLong = AtomicLong(0))
    private val windows = ConcurrentHashMap<String, IpWindow>()

    fun checkAndIncrement(ip: String): Boolean {
        cleanup()
        val now = System.currentTimeMillis()
        val window = windows.computeIfAbsent(ip) { IpWindow() }

        synchronized(window) {
            val start = window.windowStart.get()
            if (now - start > WINDOW_MS) {
                window.windowStart.set(now)
                window.count.set(1)
                return true
            }
            val current = window.count.incrementAndGet()
            return current <= MAX_REGISTRATIONS_PER_IP_PER_HOUR
        }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val threshold = now - (2 * WINDOW_MS)
        windows.entries.removeIf { (_, window) ->
            window.windowStart.get() < threshold
        }
    }

    /** Visible for testing: reset all rate limit state. */
    fun reset() {
        windows.clear()
    }
}

fun Route.deviceRoutes(sessionUtil: SessionUtil) {
    rateLimit(RateLimitName("auth")) {
        /**
         * POST /register
         * Register a new device with its public keys. No auth required.
         *
         * SEC-S-10: Device registration is rate-limited but has no absolute count limit.
         * TODO: For production, consider adding proof-of-work, CAPTCHA, or invitation-gated
         * registration to prevent mass device creation attacks. The rate limiter ("auth")
         * provides basic protection for now.
         */
        post("/register") {
            // SEC-S-10: IP-based rate limiting for device registration
            val clientIp = call.request.local.remoteAddress
            if (!DeviceRegistrationRateLimiter.checkAndIncrement(clientIp)) {
                throw ApiException(429, "RATE_LIMITED", "Too many registration attempts. Try again later.")
            }

            val request = call.receive<RegisterRequest>()

            if (request.signingKey.isBlank()) {
                throw ApiException(400, "INVALID_REQUEST", "signingKey is required")
            }
            if (request.encryptionKey.isBlank()) {
                throw ApiException(400, "INVALID_REQUEST", "encryptionKey is required")
            }
            if (!ValidationUtil.isValidPublicKey(request.signingKey)) {
                throw ApiException(400, "INVALID_REQUEST", "signingKey is not a valid public key")
            }
            if (!ValidationUtil.isValidPublicKey(request.encryptionKey)) {
                throw ApiException(400, "INVALID_REQUEST", "encryptionKey is not a valid public key")
            }

            val deviceId = UUID.randomUUID().toString()
            val now = LocalDateTime.now(ZoneOffset.UTC)

            dbQuery {
                // Check if signing key already registered
                val existing = Devices.selectAll()
                    .where { Devices.signingKey eq request.signingKey }
                    .firstOrNull()

                if (existing != null) {
                    // SEC3-S-04: Use generic error code to avoid revealing signing key existence
                    throw ApiException(409, "REGISTRATION_FAILED", "Registration failed")
                }

                Devices.insert {
                    it[id] = deviceId
                    it[signingKey] = request.signingKey
                    it[encryptionKey] = request.encryptionKey
                    it[createdAt] = now
                }
            }

            call.respond(HttpStatusCode.Created, RegisterResponse(deviceId = deviceId))
        }
    }

    // SEC5-S-02: Device deregistration endpoint (authenticated)
    authenticate("auth-session") {
        rateLimit(RateLimitName("general")) {
            /**
             * DELETE /devices/me
             * Permanently deregister the authenticated device.
             *
             * SEC7-S-03: Preserves shared buckets and their data (ops, blobs, snapshots)
             * for other members' hash chain integrity. Only deletes buckets with no other
             * active members. Removes: sessions, bucket memberships, wrapped keys,
             * attestations, recovery blobs, push tokens, and the device record itself.
             */
            delete("/devices/me") {
                val principal = call.devicePrincipal()
                val deviceId = principal.deviceId

                // Delete all sessions first (invalidates authentication)
                sessionUtil.deleteSessionsByDevice(deviceId)

                dbQuery {
                    // SEC7-S-03: Remove this device's bucket memberships, but preserve
                    // shared buckets and their ops for other members' hash chain integrity.
                    BucketAccess.deleteWhere { BucketAccess.deviceId eq deviceId }

                    // SEC7-S-03: Do NOT delete ops, blobs, or snapshots uploaded by this
                    // device — they are part of the shared hash chain and must remain
                    // for other members' data integrity.

                    // Remove wrapped keys (both as target and wrapper)
                    WrappedKeys.deleteWhere { WrappedKeys.targetDevice eq deviceId }
                    WrappedKeys.deleteWhere { WrappedKeys.wrappedBy eq deviceId }

                    // Remove key attestations (both as signer and attested)
                    KeyAttestations.deleteWhere { KeyAttestations.signerDevice eq deviceId }
                    KeyAttestations.deleteWhere { KeyAttestations.attestedDevice eq deviceId }

                    // Remove recovery blob
                    RecoveryBlobs.deleteWhere { RecoveryBlobs.deviceId eq deviceId }

                    // Remove push token
                    PushTokens.deleteWhere { PushTokens.deviceId eq deviceId }

                    // Remove challenges for this device's signing key
                    val device = Devices.selectAll()
                        .where { Devices.id eq deviceId }
                        .firstOrNull()
                    if (device != null) {
                        Challenges.deleteWhere { Challenges.signingKey eq device[Devices.signingKey] }
                    }

                    // SEC7-S-03: Only delete buckets that have NO other active members.
                    // Shared buckets (with other members) are preserved — only this
                    // device's membership was removed above.
                    val createdBuckets = Buckets.selectAll()
                        .where { Buckets.createdBy eq deviceId }
                        .map { it[Buckets.id] }
                    for (bId in createdBuckets) {
                        val otherMembers = BucketAccess.selectAll()
                            .where { (BucketAccess.bucketId eq bId) and BucketAccess.revokedAt.isNull() }
                            .count()
                        if (otherMembers == 0L) {
                            // No other members — safe to delete the entire bucket
                            Ops.deleteWhere { Ops.bucketId eq bId }
                            Checkpoints.deleteWhere { Checkpoints.bucketId eq bId }
                            Blobs.deleteWhere { Blobs.bucketId eq bId }
                            Snapshots.deleteWhere { Snapshots.bucketId eq bId }
                            InviteTokens.deleteWhere { InviteTokens.bucketId eq bId }
                            BucketAccess.deleteWhere { BucketAccess.bucketId eq bId }
                            Buckets.deleteWhere { Buckets.id eq bId }
                        }
                    }

                    // Finally, delete the device record
                    Devices.deleteWhere { Devices.id eq deviceId }
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
