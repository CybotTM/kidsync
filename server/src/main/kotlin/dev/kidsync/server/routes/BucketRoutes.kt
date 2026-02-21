package dev.kidsync.server.routes

import dev.kidsync.server.db.Devices
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.ApiException
import dev.kidsync.server.services.BucketService
import dev.kidsync.server.services.WebSocketManager
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.selectAll

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
                        val bucketId = call.parameters["id"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

                        bucketService.deleteBucket(bucketId, principal.deviceId)
                        call.respond(HttpStatusCode.NoContent)
                    }

                    /**
                     * POST /buckets/{id}/invite
                     * Register an invite token hash for this bucket.
                     */
                    post("/invite") {
                        val principal = call.devicePrincipal()
                        val bucketId = call.parameters["id"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")
                        val request = call.receive<InviteRequest>()

                        if (request.tokenHash.isBlank()) {
                            throw ApiException(400, "INVALID_REQUEST", "tokenHash is required")
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
                        val bucketId = call.parameters["id"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")
                        val request = call.receive<JoinBucketRequest>()

                        if (request.inviteToken.isBlank()) {
                            throw ApiException(400, "INVALID_REQUEST", "inviteToken is required")
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
                        val bucketId = call.parameters["id"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

                        val devices = bucketService.listDevices(bucketId, principal.deviceId)
                        call.respond(HttpStatusCode.OK, devices)
                    }

                    /**
                     * DELETE /buckets/{id}/devices/me
                     * Self-revoke: remove own access from this bucket.
                     */
                    delete("/devices/me") {
                        val principal = call.devicePrincipal()
                        val bucketId = call.parameters["id"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

                        bucketService.selfRevoke(bucketId, principal.deviceId)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
