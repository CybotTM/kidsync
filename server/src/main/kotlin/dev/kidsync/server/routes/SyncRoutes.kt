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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** WebSocket close codes for application-level errors (RFC 6455 §7.4.2: 4000-4999). */
private const val WS_CLOSE_INVALID_PARAMS: Short = 4000
private const val WS_CLOSE_AUTH_FAILED: Short = 4001
private const val WS_CLOSE_RATE_LIMITED: Short = 4003

/**
 * SEC4-S-10: IP-based rate limiter for WebSocket upgrade attempts.
 * Limits each IP to MAX_WS_CONNECTIONS_PER_IP_PER_MINUTE WebSocket connections per minute.
 */
private object WebSocketConnectionRateLimiter {
    private const val MAX_WS_CONNECTIONS_PER_IP_PER_MINUTE = 10
    private const val WINDOW_MS = 60_000L

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
            return current <= MAX_WS_CONNECTIONS_PER_IP_PER_MINUTE
        }
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        val threshold = now - (2 * WINDOW_MS)
        windows.entries.removeIf { (_, window) ->
            window.windowStart.get() < threshold
        }
    }
}

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
                    // SEC3-S-10: Validate since is non-negative
                    if (since < 0) {
                        throw ApiException(400, "INVALID_REQUEST", "'since' parameter must be >= 0")
                    }
                    // SEC4-S-20: Validate limit parameter consistently with since
                    val limitParam = call.request.queryParameters["limit"]
                    val limit = if (limitParam != null) {
                        limitParam.toIntOrNull()
                            ?: throw ApiException(400, "INVALID_REQUEST", "Invalid 'limit' parameter: must be an integer")
                    } else {
                        100
                    }

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

            // SEC5-S-14: Checkpoint acknowledgment for op pruning
            rateLimit(RateLimitName("general")) {
                post("/checkpoints/acknowledge") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")
                    val request = call.receive<dev.kidsync.server.models.AcknowledgeCheckpointRequest>()

                    syncService.acknowledgeCheckpoint(bucketId, principal.deviceId, request.checkpointId)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "acknowledged"))
                }
            }

            // SEC6-S-06: Per-bucket snapshot quota enforced below.

            // POST /buckets/{id}/snapshots
            rateLimit(RateLimitName("snapshot")) {
                post("/snapshots") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")

                    // SEC7-S-04: Verify bucket access and enforce snapshot quota in a single
                    // transaction to prevent TOCTOU race where concurrent uploads both pass
                    // the quota check before either insert completes.
                    dbQuery {
                        BucketService.requireBucketAccess(bucketId, principal.deviceId)

                        // SEC6-S-06: Enforce per-bucket snapshot quota
                        val snapshotCount = Snapshots.selectAll()
                            .where { Snapshots.bucketId eq bucketId }
                            .count()
                        if (snapshotCount >= config.maxSnapshotsPerBucket) {
                            throw ApiException(
                                409,
                                "SNAPSHOT_QUOTA_EXCEEDED",
                                "Maximum number of snapshots (${config.maxSnapshotsPerBucket}) per bucket reached"
                            )
                        }
                    }

                    val multipart = call.receiveMultipart()
                    var metadata: SnapshotMetadata? = null
                    var snapshotBytes: ByteArray? = null
                    // SEC5-S-04: Bound multipart part count to prevent abuse
                    var partCount = 0

                    multipart.forEachPart { part ->
                        partCount++
                        if (partCount > 5) {
                            part.dispose()
                            throw ApiException(400, "INVALID_REQUEST", "Too many multipart parts")
                        }
                        when (part) {
                            is PartData.FormItem -> {
                                if (part.name == "metadata") {
                                    // SEC6-S-14: Size limit on FormItem parts to prevent abuse
                                    if (part.value.length > 10_240) {
                                        part.dispose()
                                        throw ApiException(400, "INVALID_REQUEST", "Form field too large")
                                    }
                                    metadata = Json.decodeFromString<SnapshotMetadata>(part.value)
                                }
                            }
                            is PartData.FileItem -> {
                                if (part.name == "snapshot") {
                                    // SEC2-S-04: Track bytes read during multipart processing to
                                    // guard against chunked transfer encoding bypassing Content-Length.
                                    val channel = part.provider()
                                    val buffer = ByteArrayOutputStream()
                                    val chunk = ByteArray(8192)
                                    var totalRead = 0L
                                    while (!channel.isClosedForRead) {
                                        val read = channel.readAvailable(chunk)
                                        if (read == -1) break
                                        totalRead += read
                                        if (totalRead > config.maxSnapshotSizeBytes) {
                                            part.dispose()
                                            throw ApiException(413, "SNAPSHOT_TOO_LARGE", "Snapshot exceeds size limit")
                                        }
                                        buffer.write(chunk, 0, read)
                                    }
                                    snapshotBytes = buffer.toByteArray()
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

                    // SEC5-S-09: Validate snapshot keyEpoch >= 1
                    if (meta.keyEpoch < 1) {
                        throw ApiException(400, "INVALID_REQUEST", "keyEpoch must be >= 1")
                    }

                    // SEC3-S-25: Validate snapshot metadata fields format
                    if (!ValidationUtil.isValidSha256Hex(meta.sha256)) {
                        throw ApiException(400, "INVALID_REQUEST", "meta.sha256 must be a valid 64-character hex SHA-256 hash")
                    }
                    if (!ValidationUtil.isValidBase64(meta.signature)) {
                        throw ApiException(400, "INVALID_REQUEST", "meta.signature must be valid base64")
                    }

                    // SEC3-S-20: Validate atSequence is within valid range for this bucket
                    val latestOpSeq = dbQuery {
                        Ops.selectAll()
                            .where { Ops.bucketId eq bucketId }
                            .orderBy(Ops.sequence, SortOrder.DESC)
                            .limit(1)
                            .firstOrNull()
                            ?.get(Ops.sequence) ?: 0L
                    }
                    // SEC5-S-20: Do not expose latestOpSeq in error message
                    if (meta.atSequence < 0 || meta.atSequence > latestOpSeq) {
                        throw ApiException(
                            400,
                            "INVALID_REQUEST",
                            "atSequence is out of valid range"
                        )
                    }

                    // Verify SHA-256 of the uploaded snapshot matches declared hash
                    // SEC-S-03: Use constant-time comparison to prevent timing side-channel attacks
                    val computedHash = MessageDigest.getInstance("SHA-256")
                        .digest(blob)
                        .joinToString("") { "%02x".format(it) }
                    if (!MessageDigest.isEqual(computedHash.toByteArray(), meta.sha256.toByteArray())) {
                        // SEC3-S-09: Generic error without revealing server-computed hash
                        throw ApiException(
                            400,
                            "HASH_MISMATCH",
                            "SHA-256 mismatch",
                        )
                    }

                    // Derive server-side values
                    val sizeBytes = blob.size.toLong()
                    val now = LocalDateTime.now(ZoneOffset.UTC)

                    // SEC4-S-11: Write to temp file first, rename after DB commit to
                    // prevent orphaned final files on crash between write and commit.
                    val snapshotId = UUID.randomUUID().toString()
                    val snapshotDir = File(config.snapshotStoragePath)
                    snapshotDir.mkdirs()
                    val tempFile = File(snapshotDir, "$snapshotId.tmp")
                    val finalFile = File(snapshotDir, snapshotId)
                    tempFile.writeBytes(blob)

                    // SEC6-S-15: Set file permissions to 600 (owner read/write only)
                    try {
                        Files.setPosixFilePermissions(tempFile.toPath(), PosixFilePermissions.fromString("rw-------"))
                    } catch (_: UnsupportedOperationException) {
                        // Windows doesn't support POSIX file permissions
                    }

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
                        // DB commit succeeded -- rename temp to final
                        tempFile.renameTo(finalFile)
                    } catch (e: Exception) {
                        // Clean up temp file on DB insert failure
                        tempFile.delete()
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

            // SEC3-S-06: Snapshot download endpoint
            rateLimit(RateLimitName("general")) {
                get("/snapshots/{snapshotId}/download") {
                    val principal = call.devicePrincipal()
                    val bucketId = ValidationUtil.requireUuidPathParam(call, "id", "bucket id")
                    val snapshotId = ValidationUtil.requireUuidPathParam(call, "snapshotId", "snapshot id")

                    // Verify bucket access
                    dbQuery { BucketService.requireBucketAccess(bucketId, principal.deviceId) }

                    // Look up snapshot and verify it belongs to this bucket
                    val snapshot = dbQuery {
                        Snapshots.selectAll()
                            .where { Snapshots.id eq snapshotId }
                            .firstOrNull()
                    } ?: throw ApiException(HttpStatusCode.NotFound.value, "NOT_FOUND", "Snapshot not found")

                    if (snapshot[Snapshots.bucketId] != bucketId) {
                        throw ApiException(HttpStatusCode.Forbidden.value, "BUCKET_ACCESS_DENIED", "Snapshot does not belong to this bucket")
                    }

                    // Resolve file path with path traversal protection
                    val snapshotDir = File(config.snapshotStoragePath).canonicalFile
                    val snapshotFile = File(config.snapshotStoragePath, snapshot[Snapshots.filePath])
                    if (!snapshotFile.canonicalFile.startsWith(snapshotDir)) {
                        throw ApiException(HttpStatusCode.Forbidden.value, "BUCKET_ACCESS_DENIED", "Invalid file path")
                    }

                    if (!snapshotFile.exists()) {
                        throw ApiException(HttpStatusCode.NotFound.value, "NOT_FOUND", "Snapshot file not found on disk")
                    }

                    // Compute SHA-256 and add header
                    val sha256 = MessageDigest.getInstance("SHA-256")
                        .digest(snapshotFile.readBytes())
                        .joinToString("") { "%02x".format(it) }
                    call.response.header("X-Snapshot-SHA256", sha256)
                    call.respondFile(snapshotFile)
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

    // SEC6-S-07: The session token is hashed with SHA-256 before being stored in the WebSocket
    // connection's in-memory state. This limits exposure if the server process memory is dumped.

    // SEC5-S-03: WebSocket auth supports both query param (?token=...) and in-band auth message.
    // Query param auth allows the server to reject unauthenticated connections at upgrade time,
    // reducing the attack surface for resource exhaustion.

    // WebSocket /buckets/{id}/ws
    webSocket("/buckets/{id}/ws") {
        // SEC4-S-10: IP-based rate limiting for WebSocket upgrade attempts
        val clientIp = call.request.local.remoteAddress
        if (!WebSocketConnectionRateLimiter.checkAndIncrement(clientIp)) {
            close(CloseReason(WS_CLOSE_RATE_LIMITED, "Rate limit exceeded"))
            return@webSocket
        }

        val json = Json { ignoreUnknownKeys = true }
        val bucketId = call.parameters["id"]
        if (bucketId == null || !ValidationUtil.isValidUUID(bucketId)) {
            close(CloseReason(WS_CLOSE_INVALID_PARAMS, "Missing or invalid bucket id"))
            return@webSocket
        }

        var connection: WebSocketManager.WsConnection? = null
        // SEC6-S-07: Store only the hash of the session token, not the raw token
        var sessionTokenHash: String? = null

        try {
            // SEC5-S-03: Check for query param auth first, fall back to in-band auth
            val queryToken = call.request.queryParameters["token"]
            val session: dev.kidsync.server.util.Session?

            if (queryToken != null) {
                // Query param auth: validate token from URL
                session = sessionUtil.validateSession(queryToken)
                if (session == null) {
                    close(CloseReason(WS_CLOSE_AUTH_FAILED, "Invalid token"))
                    return@webSocket
                }
                sessionTokenHash = dev.kidsync.server.util.HashUtil.sha256HexString(queryToken)
            } else {
                // In-band auth: wait for auth message (backward compatible)
                val authFrame = withTimeoutOrNull(5000) { incoming.receive() }
                if (authFrame == null) {
                    close(CloseReason(WS_CLOSE_AUTH_FAILED, "Auth timeout"))
                    return@webSocket
                }
                if (authFrame !is Frame.Text) {
                    close(CloseReason(WS_CLOSE_AUTH_FAILED, "Expected text frame for auth"))
                    return@webSocket
                }

                val authText = authFrame.readText()
                val authJsonObj = json.parseToJsonElement(authText).jsonObject
                val authType = authJsonObj["type"]?.jsonPrimitive?.content

                if (authType != "auth") {
                    close(CloseReason(WS_CLOSE_AUTH_FAILED, "Expected auth message"))
                    return@webSocket
                }

                val token = authJsonObj["token"]?.jsonPrimitive?.content
                if (token == null) {
                    close(CloseReason(WS_CLOSE_AUTH_FAILED, "Missing auth token"))
                    return@webSocket
                }

                session = sessionUtil.validateSession(token)
                if (session == null) {
                    send(
                        Frame.Text(
                            json.encodeToString(
                                WsAuthFailed.serializer(),
                                WsAuthFailed(error = "TOKEN_INVALID", message = "Session token invalid or expired")
                            )
                        )
                    )
                    close(CloseReason(WS_CLOSE_AUTH_FAILED, "Auth failed"))
                    return@webSocket
                }

                // SEC6-S-07: Hash the token immediately; discard the raw value
                sessionTokenHash = dev.kidsync.server.util.HashUtil.sha256HexString(token)
            }

            // Verify bucket access (shared for both auth paths)
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
                close(CloseReason(WS_CLOSE_AUTH_FAILED, "No access"))
                return@webSocket
            }

            val latestSeq = syncService.getLatestSequence(bucketId)
            connection = WebSocketManager.WsConnection(this, session.deviceId, bucketId)

            // SEC-S-06: Enforce connection limits
            if (!wsManager.addConnection(bucketId, connection!!)) {
                close(CloseReason(WS_CLOSE_RATE_LIMITED, "Connection limit exceeded"))
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

            // SEC5-S-16: Timer-based WebSocket re-validation in a separate coroutine
            val revalidationJob = launch {
                val revalidationIntervalMs = 60_000L
                while (isActive) {
                    delay(revalidationIntervalMs)
                    // SEC6-S-07: Re-validate using the stored hash instead of raw token
                    val revalidatedSession = sessionUtil.validateSessionByHash(sessionTokenHash!!)
                    if (revalidatedSession == null) {
                        close(CloseReason(WS_CLOSE_AUTH_FAILED, "Session expired"))
                        return@launch
                    }
                    val stillHasAccess = try {
                        dbQuery { BucketService.requireBucketAccess(bucketId, revalidatedSession.deviceId) }
                        true
                    } catch (_: ApiException) {
                        false
                    }
                    if (!stillHasAccess) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY.code, "Access revoked"))
                        return@launch
                    }
                }
            }

            // Main message loop
            // SEC3-S-19: Simple counter-based rate limiter (max 10 messages per second)
            var messageCount = 0
            var windowStart = System.currentTimeMillis()

            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        // SEC3-S-19: Rate limit check
                        val now = System.currentTimeMillis()
                        if (now - windowStart > 1000) {
                            messageCount = 0
                            windowStart = now
                        }
                        messageCount++
                        if (messageCount > 10) {
                            // Drop excess messages silently
                            continue
                        }

                        val text = frame.readText()
                        // SEC5-S-12: Narrow catch to JSON parsing only; let send exceptions propagate
                        val jsonObj = try {
                            json.parseToJsonElement(text).jsonObject
                        } catch (_: Exception) {
                            // Ignore malformed messages
                            null
                        }

                        if (jsonObj != null) {
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
                        }
                    }
                }
            } finally {
                revalidationJob.cancel()
            }
        } finally {
            if (connection != null) {
                wsManager.removeConnection(bucketId, connection!!)
            }
        }
    }
}
