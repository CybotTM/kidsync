package dev.kidsync.server.routes

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.devicePrincipal
import dev.kidsync.server.services.*
import dev.kidsync.server.util.SessionUtil
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
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import java.io.File
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
                    val bucketId = call.parameters["id"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

                    val request = call.receive<OpsBatchRequest>()
                    val response = syncService.uploadOps(bucketId, principal.deviceId, request)

                    call.respond(HttpStatusCode.OK, response)

                    // Notify via WebSocket and push
                    wsManager.notifyOpsAvailable(bucketId, response.latestSequence, principal.deviceId)
                    pushService.notifyBucketDevices(bucketId, principal.deviceId, response.latestSequence)
                }
            }

            // GET /buckets/{id}/ops?since={seq}
            rateLimit(RateLimitName("sync-pull")) {
                get("/ops") {
                    val principal = call.devicePrincipal()
                    val bucketId = call.parameters["id"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

                    val since = call.request.queryParameters["since"]?.toLongOrNull()
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing or invalid 'since' parameter")
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                    val ops = syncService.pullOps(bucketId, principal.deviceId, since, limit)
                    call.respond(HttpStatusCode.OK, ops)
                }
            }

            // GET /buckets/{id}/checkpoint
            rateLimit(RateLimitName("general")) {
                get("/checkpoint") {
                    val principal = call.devicePrincipal()
                    val bucketId = call.parameters["id"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

                    val checkpoint = syncService.getCheckpoint(bucketId, principal.deviceId)
                    if (checkpoint == null) {
                        throw ApiException(404, "NOT_FOUND", "No checkpoint available")
                    }
                    call.respond(HttpStatusCode.OK, checkpoint)
                }
            }

            // POST /buckets/{id}/snapshots
            rateLimit(RateLimitName("snapshot")) {
                post("/snapshots") {
                    val principal = call.devicePrincipal()
                    val bucketId = call.parameters["id"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

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
                                    snapshotBytes = part.provider().toByteArray()
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

                    val snapshotId = UUID.randomUUID().toString()
                    val snapshotDir = File(config.snapshotStoragePath)
                    snapshotDir.mkdirs()
                    val snapshotFile = File(snapshotDir, snapshotId)
                    snapshotFile.writeBytes(blob)

                    dbQuery {
                        Snapshots.insert {
                            it[id] = snapshotId
                            it[Snapshots.bucketId] = bucketId
                            it[deviceId] = principal.deviceId
                            it[atSequence] = meta.atSequence
                            it[keyEpoch] = meta.keyEpoch
                            it[sizeBytes] = meta.sizeBytes
                            it[sha256Hash] = meta.sha256
                            it[signature] = meta.signature
                            it[filePath] = snapshotFile.absolutePath
                            it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
                        }
                    }

                    wsManager.notifySnapshotAvailable(bucketId, meta.atSequence, snapshotId)

                    call.respond(
                        HttpStatusCode.Created,
                        SnapshotResponse(
                            id = snapshotId,
                            atSequence = meta.atSequence,
                            keyEpoch = meta.keyEpoch,
                            sizeBytes = meta.sizeBytes,
                            sha256Hash = meta.sha256,
                            signature = meta.signature,
                            createdAt = meta.createdAt,
                        )
                    )
                }
            }

            // GET /buckets/{id}/snapshots/latest
            rateLimit(RateLimitName("general")) {
                get("/snapshots/latest") {
                    val principal = call.devicePrincipal()
                    val bucketId = call.parameters["id"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing bucket id")

                    dbQuery { BucketService.requireBucketAccess(bucketId, principal.deviceId) }

                    val snapshot = dbQuery {
                        Snapshots.selectAll()
                            .where { Snapshots.bucketId eq bucketId }
                            .orderBy(Snapshots.atSequence, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                    }

                    if (snapshot == null) {
                        throw ApiException(404, "NOT_FOUND", "No snapshot available")
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        SnapshotResponse(
                            id = snapshot[Snapshots.id],
                            atSequence = snapshot[Snapshots.atSequence],
                            keyEpoch = snapshot[Snapshots.keyEpoch],
                            sizeBytes = snapshot[Snapshots.sizeBytes],
                            sha256Hash = snapshot[Snapshots.sha256Hash],
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
        if (bucketId == null) {
            close(CloseReason(4000, "Missing bucket id"))
            return@webSocket
        }

        var connection: WebSocketManager.WsConnection? = null

        try {
            // Wait for auth message
            val authFrame = incoming.receive()
            if (authFrame is Frame.Text) {
                val text = authFrame.readText()
                val jsonObj = json.parseToJsonElement(text).jsonObject
                val type = jsonObj["type"]?.jsonPrimitive?.content

                if (type == "auth") {
                    val token = jsonObj["token"]?.jsonPrimitive?.content
                    if (token != null) {
                        val session = sessionUtil.validateSession(token)
                        if (session != null) {
                            // Verify bucket access
                            val hasAccess = try {
                                dbQuery { BucketService.requireBucketAccess(bucketId, session.deviceId) }
                                true
                            } catch (_: ApiException) {
                                false
                            }

                            if (hasAccess) {
                                val latestSeq = syncService.getLatestSequence(bucketId)
                                connection = WebSocketManager.WsConnection(this, session.deviceId, bucketId)
                                wsManager.addConnection(bucketId, connection!!)

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
                            } else {
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
                        } else {
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
                    }
                }
            }

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
