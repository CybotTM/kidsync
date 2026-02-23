package dev.kidsync.server.routes

import dev.kidsync.server.db.Devices
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.BucketService
import dev.kidsync.server.services.WebSocketManager
import dev.kidsync.server.util.ValidationUtil
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll

// SEC5-S-08: Creator-driven device revocation endpoint added as DELETE /buckets/{id}/devices/{deviceId}.

fun Route.bucketRoutes(bucketService: BucketService, wsManager: WebSocketManager) {
    authenticate("auth-session") {
        rateLimit(RateLimitName("general")) {
            route("/buckets") {
                /**
                 * POST /buckets
                 * Create a new anonymous bucket.
                 */
                post {
                    val principal = call.devicePrincipal()
                    val response = bucketService.createBucket(principal.deviceId)
                    call.respond(HttpStatusCode.Created, response)
                }

                route("/{id}") {
                    /**
                     * DELETE /buckets/{id}
                     * Delete a bucket (creator only). Cascading delete of all data.
                     */
                    delete {
                        val principal = call.devicePrincipal()
                        val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                        bucketService.deleteBucket(bucketId, principal.deviceId)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    /**
                     * POST /buckets/{id}/invite
                     * Register an invite token hash for this bucket.
                     */
                    post("/invite") {
                        val principal = call.devicePrincipal()
                        val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")
                        val request = call.receive<InviteRequest>()

                        if (request.tokenHash.isBlank()) {
                            throw ApiException(400, "INVALID_REQUEST", "tokenHash is required")
                        }

                        // SEC2-S-22: Max length validation for tokenHash
                        if (request.tokenHash.length > 128) {
                            throw ApiException(400, "INVALID_REQUEST", "tokenHash exceeds maximum length")
                        }

                        // SEC2-S-13: Validate tokenHash is a valid SHA-256 hex string (64 hex chars)
                        if (!ValidationUtil.isValidSha256Hex(request.tokenHash)) {
                            throw ApiException(400, "INVALID_REQUEST", "tokenHash must be exactly 64 lowercase hex characters (SHA-256)")
                        }

                        val response = bucketService.createInvite(bucketId, principal.deviceId, request.tokenHash)
                        call.respond(HttpStatusCode.Created, response)
                    }

                    /**
                     * POST /buckets/{id}/join
                     * Redeem an invite token to join this bucket.
                     */
                    post("/join") {
                        val principal = call.devicePrincipal()
                        val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")
                        val request = call.receive<JoinBucketRequest>()

                        if (request.inviteToken.isBlank()) {
                            throw ApiException(400, "INVALID_REQUEST", "inviteToken is required")
                        }

                        // SEC3-S-13: Max length check on inviteToken before hashing
                        if (request.inviteToken.length > 256) {
                            throw ApiException(400, "INVALID_REQUEST", "inviteToken exceeds maximum length")
                        }

                        val response = bucketService.joinBucket(bucketId, principal.deviceId, request.inviteToken)

                        // Look up the joining device's encryption key for the WS notification
                        val encryptionKey = dbQuery {
                            Devices.selectAll()
                                .where { Devices.id eq principal.deviceId }
                                .firstOrNull()
                                ?.get(Devices.encryptionKey) ?: ""
                        }

                        // Notify existing devices about new device joining
                        wsManager.notifyDeviceJoined(bucketId, principal.deviceId, encryptionKey)

                        call.respond(HttpStatusCode.OK, response)
                    }

                    /**
                     * GET /buckets/{id}/devices
                     * List all devices with active access to this bucket.
                     */
                    get("/devices") {
                        val principal = call.devicePrincipal()
                        val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                        val devices = bucketService.listDevices(bucketId, principal.deviceId)
                        call.respond(HttpStatusCode.OK, devices)
                    }

                    /**
                     * PATCH /buckets/{id}/creator
                     * SEC3-S-08: Transfer bucket creator role to another device.
                     * Only the current creator can transfer ownership.
                     */
                    patch("/creator") {
                        val principal = call.devicePrincipal()
                        val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")
                        val request = call.receive<TransferCreatorRequest>()

                        if (request.targetDeviceId.isBlank()) {
                            throw ApiException(400, "INVALID_REQUEST", "targetDeviceId is required")
                        }

                        bucketService.transferCreator(bucketId, principal.deviceId, request.targetDeviceId)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "transferred"))
                    }

                    /**
                     * DELETE /buckets/{id}/devices/me
                     * Self-revoke: remove own access from this bucket.
                     */
                    delete("/devices/me") {
                        val principal = call.devicePrincipal()
                        val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                        bucketService.selfRevoke(bucketId, principal.deviceId)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    /**
                     * DELETE /buckets/{id}/devices/{deviceId}
                     * SEC5-S-08: Creator-driven device revocation. Only the bucket creator
                     * can remove another device from the bucket.
                     */
                    delete("/devices/{deviceId}") {
                        val principal = call.devicePrincipal()
                        val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")
                        val targetDeviceId = ValidationUtil.requireUuidPathParam(call, "deviceId", "device id")

                        bucketService.creatorRevoke(bucketId, principal.deviceId, targetDeviceId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
