package dev.kidsync.server.routes

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.*
import dev.kidsync.server.util.SessionUtil
import dev.kidsync.server.util.ValidationUtil
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.utils.io.*
import io.ktor.websocket.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

fun Route.syncRoutes(
    config: AppConfig,
    syncService: SyncService,
    pushService: PushService,
    wsManager: WebSocketManager,
    sessionUtil: SessionUtil,
) {
    authenticate("auth-session") {
        route("/buckets/{id}") {
            // POST /buckets/{id}/ops
            rateLimit(RateLimitName("sync-upload")) {
                post("/ops") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                    val request = call.receive<OpsBatchRequest>()
                    val (response, checkpoint) = syncService.uploadOps(bucketId, principal.deviceId, request)

                    call.respond(HttpStatusCode.Created, response)

                    // Notify via WebSocket and push
                    val latestSequence = response.accepted.lastOrNull()?.globalSequence ?: 0L
                    wsManager.notifyOpsAvailable(bucketId, latestSequence, principal.deviceId)
                    pushService.notifyBucketDevices(bucketId, principal.deviceId, latestSequence)

                    // Fire checkpoint notification if one was created
                    if (checkpoint != null) {
                        wsManager.notifyCheckpointAvailable(bucketId, checkpoint.startSequence, checkpoint.endSequence)
                    }
                }
            }

            // GET /buckets/{id}/ops?since={seq}
            rateLimit(RateLimitName("sync-pull")) {
                get("/ops") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                    val since = call.request.queryParameters["since"]?.toLongOrNull()
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing or invalid 'since' parameter")
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                    val pullResponse = syncService.pullOps(bucketId, principal.deviceId, since, limit)
                    call.respond(HttpStatusCode.OK, pullResponse)
                }
            }

            // GET /buckets/{id}/checkpoint
            rateLimit(RateLimitName("general")) {
                get("/checkpoint") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                    val checkpoint = syncService.getCheckpoint(bucketId, principal.deviceId)
                    if (checkpoint == null) {
                        throw ApiException(404, "NO_CHECKPOINT", "No checkpoint available")
                    }
                    call.respond(HttpStatusCode.OK, checkpoint)
                }
            }

            // POST /buckets/{id}/snapshots
            rateLimit(RateLimitName("snapshot")) {
                post("/snapshots") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                    // Verify bucket access
                    dbQuery { BucketService.requireBucketAccess(bucketId, principal.deviceId) }

                    val multipart = call.receiveMultipart()
                    var metadata: SnapshotMetadata? = null
                    var snapshotBytes: ByteArray? = null

                    multipart.forEachPart { part ->
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "metadata") {
                                    metadata = Json.decodeFromString<SnapshotMetadata>(part.value)
                                }
                            }
                            is PartData.FileItem -> {
                                if (part.name == "snapshot") {
                                    // SEC-S-05: Check size immediately after reading to fail fast
                                    val bytes = part.provider().toByteArray()
                                    if (bytes.size > config.maxSnapshotSizeBytes) {
                                        part.dispose()
                                        throw ApiException(413, "SNAPSHOT_TOO_LARGE", "Snapshot exceeds size limit")
                                    }
                                    snapshotBytes = bytes
                                }
                            }
                            else -> {}
                        }
                        part.dispose()
                    }

                    val meta = metadata
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing metadata part")
                    val blob = snapshotBytes
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing snapshot part")

                    if (blob.size > config.maxSnapshotSizeBytes) {
                        throw ApiException(413, "SNAPSHOT_TOO_LARGE", "Snapshot exceeds size limit")
                    }

                    // Verify SHA-256 of the uploaded snapshot matches declared hash
                    // SEC-S-03: Use constant-time comparison to prevent timing side-channel attacks
                    val computedHash = MessageDigest.getInstance("SHA-256")
                        .digest(blob)
                        .joinToString("") { "%02x".format(it) }
                    if (!MessageDigest.isEqual(computedHash.toByteArray(), meta.sha256.toByteArray())) {
                        throw ApiException(
                            400,
                            "HASH_MISMATCH",
                            "Snapshot SHA-256 mismatch: expected ${meta.sha256}, got $computedHash",
                            details = kotlinx.serialization.json.buildJsonObject {
                                put("declared", kotlinx.serialization.json.JsonPrimitive(meta.sha256))
                                put("actual", kotlinx.serialization.json.JsonPrimitive(computedHash))
                            },
                        )
                    }

                    // Derive server-side values
                    val sizeBytes = blob.size.toLong()
                    val now = LocalDateTime.now(ZoneOffset.UTC)

                    val snapshotId = UUID.randomUUID().toString()
                    val snapshotDir = File(config.snapshotStoragePath)
                    snapshotDir.mkdirs()
                    val snapshotFile = File(snapshotDir, snapshotId)
                    snapshotFile.writeBytes(blob)

                    try {
                        dbQuery {
                            Snapshots.insert {
                                it[id] = snapshotId
                                it[Snapshots.bucketId] = bucketId
                                it[deviceId] = principal.deviceId
                                it[atSequence] = meta.atSequence
                                it[keyEpoch] = meta.keyEpoch
                                it[Snapshots.sizeBytes] = sizeBytes
                                it[sha256Hash] = meta.sha256
                                it[signature] = meta.signature
                                // SEC-S-15: Store only the filename, not the absolute path
                                it[filePath] = snapshotId
                                it[createdAt] = now
                            }
                        }
                    } catch (e: Exception) {
                        // Clean up orphaned file on DB insert failure
                        snapshotFile.delete()
                        throw e
                    }

                    wsManager.notifySnapshotAvailable(bucketId, meta.atSequence, snapshotId)

                    call.respond(
                        HttpStatusCode.Created,
                        UploadSnapshotResponse(
                            snapshotId = snapshotId,
                            atSequence = meta.atSequence,
                        )
                    )
                }
            }

            // GET /buckets/{id}/snapshots/latest
            rateLimit(RateLimitName("general")) {
                get("/snapshots/latest") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                    dbQuery { BucketService.requireBucketAccess(bucketId, principal.deviceId) }

                    val snapshot = dbQuery {
                        Snapshots.selectAll()
                            .where { Snapshots.bucketId eq bucketId }
                            .orderBy(Snapshots.atSequence, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                    }

                    if (snapshot == null) {
                        throw ApiException(404, "NO_SNAPSHOT", "No snapshot available")
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        SnapshotResponse(
                            snapshotId = snapshot[Snapshots.id],
                            deviceId = snapshot[Snapshots.deviceId],
                            atSequence = snapshot[Snapshots.atSequence],
                            keyEpoch = snapshot[Snapshots.keyEpoch],
                            sizeBytes = snapshot[Snapshots.sizeBytes],
                            sha256 = snapshot[Snapshots.sha256Hash],
                            signature = snapshot[Snapshots.signature],
                            createdAt = snapshot[Snapshots.createdAt]
                                .atOffset(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_INSTANT),
                        )
                    )
                }
            }
        }
    }

    // WebSocket /buckets/{id}/ws
    webSocket("/buckets/{id}/ws") {
        val json = Json { ignoreUnknownKeys = true }
        val bucketId = call.parameters["id"]
        if (bucketId == null || !ValidationUtil.isValidUUID(bucketId)) {
            close(CloseReason(4000, "Missing or invalid bucket id"))
            return@webSocket
        }

        var connection: WebSocketManager.WsConnection? = null

        try {
            // Wait for auth message with timeout
            val authFrame = withTimeoutOrNull(5000) { incoming.receive() }
            if (authFrame == null) {
                close(CloseReason(4001, "Auth timeout"))
                return@webSocket
            }
            if (authFrame !is Frame.Text) {
                close(CloseReason(4001, "Expected text frame for auth"))
                return@webSocket
            }

            val authText = authFrame.readText()
            val authJsonObj = json.parseToJsonElement(authText).jsonObject
            val authType = authJsonObj["type"]?.jsonPrimitive?.content

            if (authType != "auth") {
                close(CloseReason(4001, "Expected auth message"))
                return@webSocket
            }

            val token = authJsonObj["token"]?.jsonPrimitive?.content
            if (token == null) {
                close(CloseReason(4001, "Missing auth token"))
                return@webSocket
            }

            val session = sessionUtil.validateSession(token)
            if (session == null) {
                send(
                    Frame.Text(
                        json.encodeToString(
                            WsAuthFailed.serializer(),
                            WsAuthFailed(error = "TOKEN_INVALID", message = "Session token invalid or expired")
                        )
                    )
                )
                close(CloseReason(4001, "Auth failed"))
                return@webSocket
            }

            // Verify bucket access
            val hasAccess = try {
                dbQuery { BucketService.requireBucketAccess(bucketId, session.deviceId) }
                true
            } catch (_: ApiException) {
                false
            }

            if (!hasAccess) {
                send(
                    Frame.Text(
                        json.encodeToString(
                            WsAuthFailed.serializer(),
                            WsAuthFailed(error = "NO_ACCESS", message = "No access to this bucket")
                        )
                    )
                )
                close(CloseReason(4001, "No access"))
                return@webSocket
            }

            val latestSeq = syncService.getLatestSequence(bucketId)
            connection = WebSocketManager.WsConnection(this, session.deviceId, bucketId)

            // SEC-S-06: Enforce connection limits
            if (!wsManager.addConnection(bucketId, connection!!)) {
                close(CloseReason(4003, "Connection limit exceeded"))
                return@webSocket
            }

            send(
                Frame.Text(
                    json.encodeToString(
                        WsAuthOk.serializer(),
                        WsAuthOk(
                            deviceId = session.deviceId,
                            bucketId = bucketId,
                            latestSequence = latestSeq,
                        )
                    )
                )
            )

            // Main message loop
            for (frame in incoming) {
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    try {
                        val jsonObj = json.parseToJsonElement(text).jsonObject
                        val type = jsonObj["type"]?.jsonPrimitive?.content

                        when (type) {
                            "ping" -> {
                                send(
                                    Frame.Text(
                                        json.encodeToString(
                                            WsPong.serializer(),
                                            WsPong(ts = Instant.now().toString())
                                        )
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore malformed messages
                    }
                }
            }
        } finally {
            if (connection != null) {
                wsManager.removeConnection(bucketId, connection!!)
            }
        }
    }
}
