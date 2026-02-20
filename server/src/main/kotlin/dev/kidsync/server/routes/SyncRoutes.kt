package dev.kidsync.server.routes

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.userPrincipal
import dev.kidsync.server.services.*
import dev.kidsync.server.util.JwtUtil
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.auth.*
import io.ktor.utils.io.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
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
    jwtUtil: JwtUtil,
) {
    authenticate("auth-jwt") {
        route("/sync") {
            // POST /sync/handshake
            rateLimit(RateLimitName("general")) {
                post("/handshake") {
                    val principal = call.userPrincipal()
                    val request = call.receive<HandshakeRequest>()

                    // Check device exists and is not revoked
                    val deviceOk = dbQuery {
                        val device = Devices.selectAll().where { Devices.id eq request.deviceId }.firstOrNull()
                        if (device == null) return@dbQuery "DEVICE_UNKNOWN"
                        if (device[Devices.revokedAt] != null) return@dbQuery "DEVICE_REVOKED"
                        null
                    }

                    if (deviceOk != null) {
                        val status = if (deviceOk == "DEVICE_REVOKED") HttpStatusCode.Forbidden else HttpStatusCode.NotFound
                        call.respond(
                            status,
                            HandshakeResponse(
                                error = deviceOk,
                                message = if (deviceOk == "DEVICE_REVOKED") "Device has been revoked" else "Device not found",
                            )
                        )
                        return@post
                    }

                    val familyId = request.familyId
                    val currentGlobalSeq = syncService.getLatestSequence(familyId)
                    val pendingOps = if (request.lastGlobalSequence < currentGlobalSeq) {
                        currentGlobalSeq - request.lastGlobalSequence
                    } else {
                        0L
                    }

                    // Get the latest key epoch for this family
                    val latestKeyEpoch = dbQuery {
                        OpLog.selectAll()
                            .where { OpLog.familyId eq familyId }
                            .orderBy(OpLog.globalSequence, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                            ?.get(OpLog.keyEpoch) ?: 1
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        HandshakeResponse(
                            serverVersion = 1,
                            currentGlobalSequence = currentGlobalSeq,
                            pendingOpsCount = pendingOps,
                            keyEpoch = latestKeyEpoch,
                        )
                    )
                }
            }

            // POST /sync/ops
            rateLimit(RateLimitName("sync-upload")) {
                post("/ops") {
                    val principal = call.userPrincipal()
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "User is not a member of any family")

                    val request = call.receive<UploadOpsRequest>()
                    val result = syncService.uploadOps(principal.userId, familyId, request)

                    result.fold(
                        onSuccess = { response ->
                            call.respond(HttpStatusCode.OK, response)

                            // Notify via WebSocket and push
                            val latestSeq = response.accepted.lastOrNull()?.globalSequence ?: 0L
                            val sourceDeviceId = request.ops.firstOrNull()?.deviceId ?: principal.deviceId

                            wsManager.notifyOpsAvailable(familyId, latestSeq, sourceDeviceId)
                            pushService.notifyFamilyDevices(familyId, sourceDeviceId, latestSeq)
                        },
                        onFailure = { throw it },
                    )
                }
            }

            // GET /sync/ops
            rateLimit(RateLimitName("sync-pull")) {
                get("/ops") {
                    val principal = call.userPrincipal()
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "User is not a member of any family")

                    val since = call.request.queryParameters["since"]?.toLongOrNull()
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing or invalid 'since' parameter")
                    val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100

                    val response = syncService.pullOps(familyId, since, limit)
                    call.respond(HttpStatusCode.OK, response)
                }
            }

            // POST /sync/snapshot
            rateLimit(RateLimitName("snapshot")) {
                post("/snapshot") {
                    val principal = call.userPrincipal()
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "User is not a member of any family")

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
                        throw ApiException(413, "SNAPSHOT_TOO_LARGE", "Snapshot exceeds 50 MB limit")
                    }

                    val snapshotId = UUID.randomUUID().toString()
                    val snapshotDir = File(config.snapshotStoragePath)
                    snapshotDir.mkdirs()
                    val snapshotFile = File(snapshotDir, snapshotId)
                    snapshotFile.writeBytes(blob)

                    dbQuery {
                        Snapshots.insert {
                            it[id] = snapshotId
                            it[Snapshots.familyId] = familyId
                            it[deviceId] = meta.deviceId
                            it[atSequence] = meta.atSequence
                            it[keyEpoch] = meta.keyEpoch
                            it[sizeBytes] = meta.sizeBytes
                            it[sha256Hash] = meta.sha256
                            it[signature] = meta.signature
                            it[filePath] = snapshotFile.absolutePath
                            it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
                        }
                    }

                    wsManager.notifySnapshotAvailable(familyId, meta.atSequence, snapshotId)

                    call.respond(
                        HttpStatusCode.Created,
                        UploadSnapshotResponse(snapshotId = snapshotId, sequence = meta.atSequence)
                    )
                }
            }

            // GET /sync/snapshot/latest
            rateLimit(RateLimitName("general")) {
                get("/snapshot/latest") {
                    val principal = call.userPrincipal()
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "User is not a member of any family")

                    val snapshot = dbQuery {
                        Snapshots.selectAll()
                            .where { Snapshots.familyId eq familyId }
                            .orderBy(Snapshots.atSequence, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                    }

                    if (snapshot == null) {
                        throw ApiException(404, "NO_SNAPSHOT", "No snapshot has been uploaded for this family")
                    }

                    val expiresAt = Instant.now().plusSeconds(3600)

                    call.respond(
                        HttpStatusCode.OK,
                        LatestSnapshotResponse(
                            snapshotId = snapshot[Snapshots.id],
                            deviceId = snapshot[Snapshots.deviceId],
                            atSequence = snapshot[Snapshots.atSequence],
                            sequence = snapshot[Snapshots.atSequence],
                            keyEpoch = snapshot[Snapshots.keyEpoch],
                            sizeBytes = snapshot[Snapshots.sizeBytes],
                            sha256 = snapshot[Snapshots.sha256Hash],
                            signature = snapshot[Snapshots.signature],
                            createdAt = snapshot[Snapshots.createdAt].atOffset(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_INSTANT),
                            downloadUrl = "/sync/snapshot/${snapshot[Snapshots.id]}/blob",
                            downloadUrlExpiresAt = expiresAt.toString(),
                        )
                    )
                }

                // GET /sync/snapshot/{id}/blob
                get("/snapshot/{snapshotId}/blob") {
                    val principal = call.userPrincipal()
                    val snapshotId = call.parameters["snapshotId"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing snapshotId")
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "Not a family member")

                    val snapshot = dbQuery {
                        Snapshots.selectAll().where {
                            (Snapshots.id eq snapshotId) and (Snapshots.familyId eq familyId)
                        }.firstOrNull()
                    } ?: throw ApiException(404, "NOT_FOUND", "Snapshot not found")

                    val file = File(snapshot[Snapshots.filePath])
                    if (!file.exists()) {
                        throw ApiException(404, "NOT_FOUND", "Snapshot file not found")
                    }

                    call.respondBytes(file.readBytes(), ContentType.Application.OctetStream)
                }
            }

            // GET /sync/checkpoint
            rateLimit(RateLimitName("general")) {
                get("/checkpoint") {
                    val principal = call.userPrincipal()
                    val familyId = principal.familyIds.firstOrNull()
                        ?: throw ApiException(403, "FORBIDDEN", "User is not a member of any family")

                    val atSequence = call.request.queryParameters["atSequence"]?.toLongOrNull()
                    val response = syncService.getCheckpoint(familyId, atSequence)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
    }

    // WebSocket /sync/ws
    webSocket("/sync/ws") {
        val json = Json { ignoreUnknownKeys = true }
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
                        try {
                            val decoded = jwtUtil.verifyAccessToken(token)
                            val userId = decoded.subject
                            val deviceId = decoded.getClaim("did").asString()
                            val familyIds = decoded.getClaim("fam").asList(String::class.java) ?: emptyList()
                            val familyId = familyIds.firstOrNull()

                            if (familyId != null) {
                                val latestSeq = syncService.getLatestSequence(familyId)
                                connection = WebSocketManager.WsConnection(this, userId, deviceId, familyId)
                                wsManager.addConnection(familyId, connection!!)

                                send(
                                    Frame.Text(
                                        json.encodeToString(
                                            WsAuthOk.serializer(),
                                            WsAuthOk(deviceId = deviceId, familyId = familyId, latestSequence = latestSeq)
                                        )
                                    )
                                )
                            } else {
                                send(
                                    Frame.Text(
                                        json.encodeToString(
                                            WsAuthFailed.serializer(),
                                            WsAuthFailed(error = "NO_FAMILY", message = "User not in any family")
                                        )
                                    )
                                )
                                close(CloseReason(4001, "No family"))
                                return@webSocket
                            }
                        } catch (e: Exception) {
                            send(
                                Frame.Text(
                                    json.encodeToString(
                                        WsAuthFailed.serializer(),
                                        WsAuthFailed(error = "TOKEN_INVALID", message = e.message ?: "Token verification failed")
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
                    } catch (e: Exception) {
                        // Ignore malformed messages
                    }
                }
            }
        } finally {
            if (connection != null) {
                wsManager.removeConnection(connection!!.familyId, connection!!)
            }
        }
    }
}
