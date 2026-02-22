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
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

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
             * Permanently deregister the authenticated device, cleaning up all associated data:
             * sessions, bucket access records, wrapped keys, attestations, recovery blobs,
             * push tokens, and the device record itself.
             */
            delete("/devices/me") {
                val principal = call.devicePrincipal()
                val deviceId = principal.deviceId

                // Delete all sessions first (invalidates authentication)
                sessionUtil.deleteSessionsByDevice(deviceId)

                dbQuery {
                    // Remove bucket access records
                    BucketAccess.deleteWhere { BucketAccess.deviceId eq deviceId }

                    // Remove ops uploaded by this device
                    Ops.deleteWhere { Ops.deviceId eq deviceId }

                    // Remove blobs uploaded by this device
                    Blobs.deleteWhere { Blobs.uploadedBy eq deviceId }

                    // Remove snapshots uploaded by this device
                    Snapshots.deleteWhere { Snapshots.deviceId eq deviceId }

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

                    // Handle buckets created by this device:
                    // Delete empty buckets, transfer creator for non-empty ones is not supported.
                    // For now, delete buckets created by this device (cascading all related data).
                    val createdBuckets = Buckets.selectAll()
                        .where { Buckets.createdBy eq deviceId }
                        .map { it[Buckets.id] }
                    for (bId in createdBuckets) {
                        // Clean up bucket data
                        Ops.deleteWhere { Ops.bucketId eq bId }
                        Checkpoints.deleteWhere { Checkpoints.bucketId eq bId }
                        Blobs.deleteWhere { Blobs.bucketId eq bId }
                        Snapshots.deleteWhere { Snapshots.bucketId eq bId }
                        InviteTokens.deleteWhere { InviteTokens.bucketId eq bId }
                        BucketAccess.deleteWhere { BucketAccess.bucketId eq bId }
                        Buckets.deleteWhere { Buckets.id eq bId }
                    }

                    // Finally, delete the device record
                    Devices.deleteWhere { Devices.id eq deviceId }
                }

                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
