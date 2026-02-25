package dev.kidsync.server

import dev.kidsync.server.db.Checkpoints
import dev.kidsync.server.db.DatabaseFactory
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.services.SyncService
import org.jetbrains.exposed.sql.selectAll
import dev.kidsync.server.models.HealthResponse
import dev.kidsync.server.plugins.*
import dev.kidsync.server.routes.*
import dev.kidsync.server.services.*
import dev.kidsync.server.util.SessionUtil
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.File

/** Interval between periodic cleanup runs (sessions, invites, temp files, ops). */
private const val CLEANUP_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

/** Maximum Content-Length for any single request body (JSON, blobs are separately limited). */
private const val MAX_REQUEST_BODY_BYTES = 10L * 1024 * 1024 // 10 MB

/** HSTS max-age in seconds (2 years, per best-practice recommendation). */
private const val HSTS_MAX_AGE_SECONDS = 63_072_000L

fun main() {
    val config = AppConfig()
    val logger = LoggerFactory.getLogger("Application")

    // SEC-S-14: Warn if KIDSYNC_SERVER_ORIGIN is not set (challenge-response relies on origin binding)
    if (System.getenv("KIDSYNC_SERVER_ORIGIN") == null) {
        logger.warn(
            "KIDSYNC_SERVER_ORIGIN environment variable is not set. " +
                "Challenge-response authentication uses the default origin '{}'. " +
                "Set KIDSYNC_SERVER_ORIGIN to the actual server URL in production.",
            config.serverOrigin,
        )
    }

    // SEC-S-19: TLS is expected to be terminated by a reverse proxy (nginx, Caddy, etc.).
    // This server does not configure TLS directly. Ensure a TLS-terminating proxy is
    // deployed in front of this server in production.
    logger.info("Starting KidSync server v${config.serverVersion} on ${config.host}:${config.port}")

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

@Suppress("LongMethod")
fun Application.module(config: AppConfig = AppConfig()) {
    // SEC3-S-16: Validate storage paths on startup (fail fast if invalid)
    AppConfig.validateStoragePaths(config)

    // Reset IP-based rate limiters on startup (important for test isolation)
    dev.kidsync.server.routes.DeviceRegistrationRateLimiter.reset()

    // Initialize database
    DatabaseFactory.init(config)

    val logger = LoggerFactory.getLogger("dev.kidsync.server.Application")

    // SEC6-S-20: Warn if debug logging is enabled (sensitive data may be logged)
    if (logger.isDebugEnabled) {
        logger.warn("DEBUG logging is enabled. Sensitive data may be logged. Do not use in production.")
    }

    // Initialize utilities and services
    val sessionUtil = SessionUtil(config)
    val wsManager = WebSocketManager()
    val bucketService = BucketService(config.blobStoragePath, config.snapshotStoragePath, wsManager, sessionUtil, config.maxDevicesPerBucket)
    val keyService = KeyService()
    val syncService = SyncService(config)
    val blobService = BlobService(config)
    val pushService = PushService(config.pushTokenEncryptionKey)

    // Periodic cleanup of expired sessions and challenges (every 5 minutes)
    // SEC-S-02: Sessions are now DB-backed; cleanup requires suspend context
    // SEC2-S-18: Bind the cleanup coroutine to app lifecycle so it's cancelled on shutdown
    val cleanupScope = CoroutineScope(Dispatchers.Default)
    val cleanupJob = cleanupScope.launch {
        while (isActive) {
            delay(CLEANUP_INTERVAL_MS)
            try {
                sessionUtil.cleanup()
            } catch (e: Exception) {
                LoggerFactory.getLogger("Application").warn("Session cleanup failed: {}", e.message)
            }
            try {
                bucketService.cleanupExpiredInvites()
            } catch (e: Exception) {
                LoggerFactory.getLogger("Application").warn("Invite token cleanup failed: {}", e.message)
            }
            // SEC4-S-11: Clean up orphan .tmp files in snapshot and blob storage
            // that are older than 1 hour (leftover from crashed uploads)
            try {
                cleanupOrphanTempFiles(config.snapshotStoragePath)
                cleanupOrphanTempFiles(config.blobStoragePath)
            } catch (e: Exception) {
                LoggerFactory.getLogger("Application").warn("Temp file cleanup failed: {}", e.message)
            }
            // SEC5-S-14: Prune ops covered by fully-acknowledged checkpoints
            try {
                pruneAllBuckets(syncService)
            } catch (e: Exception) {
                LoggerFactory.getLogger("Application").warn("Op pruning failed: {}", e.message)
            }
        }
    }

    // SEC2-S-18: Cancel cleanup job when the application shuts down
    @Suppress("DEPRECATION")
    monitor.subscribe(ApplicationStopped) {
        cleanupJob.cancel()
        cleanupScope.cancel()
    }

    // Install plugins
    configureSerialization()
    configureAuth(sessionUtil)
    configureCORS()
    configureRateLimit(config)
    configureStatusPages()
    configureWebSockets()

    // SEC-S-13: Install ForwardedHeaders so rate limiter uses real client IP behind a proxy
    // SEC2-S-15: WARNING - XForwardedHeaders trusts ALL sources by default. In production,
    // this server MUST be deployed behind a trusted reverse proxy (nginx, Caddy, etc.) that
    // strips/overwrites X-Forwarded-* headers from untrusted clients. Without this, an
    // attacker can spoof their IP address to bypass rate limiting.
    // DEFERRED(INFRA-01): Ktor framework limitation — XForwardedHeaders trusts all sources and does not
    // support configuring trusted proxy addresses. When Ktor adds this support, restrict to
    // known reverse proxy IPs. Workaround: deploy behind a reverse proxy that strips/overwrites
    // X-Forwarded-* headers from untrusted clients.
    install(XForwardedHeaders)

    // SEC-S-18: Configure CallLogging with a custom format to avoid logging sensitive headers
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} -> ${call.response.status()}"
        }
    }

    // SEC-S-04: Request body size limit for JSON endpoints (10 MB max)
    // SEC3-S-11: Also reject POST/PUT/PATCH requests without Content-Length header
    // (except multipart, which uses chunked encoding by design) to prevent chunked
    // transfer encoding from bypassing the Content-Length size check.
    intercept(ApplicationCallPipeline.Plugins) {
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (contentLength != null && contentLength > MAX_REQUEST_BODY_BYTES) {
            call.respond(HttpStatusCode.PayloadTooLarge)
            finish()
            return@intercept
        }

        val method = call.request.httpMethod
        val isModifying = method == HttpMethod.Post || method == HttpMethod.Put || method == HttpMethod.Patch
        val contentType = call.request.contentType()
        val isMultipart = contentType.match(ContentType.MultiPart.FormData)
        // SEC4-S-06: Require Content-Length for ALL non-multipart modifying requests,
        // regardless of Transfer-Encoding presence, to prevent size limit bypass.
        if (isModifying && !isMultipart && contentLength == null) {
            call.respond(HttpStatusCode.LengthRequired)
            finish()
            return@intercept
        }
    }

    // SEC-S-11: Security headers on all responses
    intercept(ApplicationCallPipeline.Plugins) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Cache-Control", "no-store")
        // SEC2-S-12: HSTS header to enforce HTTPS connections
        call.response.header("Strict-Transport-Security", "max-age=$HSTS_MAX_AGE_SECONDS; includeSubDomains; preload")
        // SEC5-S-21: Additional security headers
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Content-Security-Policy", "default-src 'none'")
        call.response.header("Permissions-Policy", "")
    }

    // Configure routes
    routing {
        // SEC6-S-18: Simplified health endpoint -- only returns status, no version or uptime
        get("/health") {
            val dbOk = try {
                dbQuery { true }
            } catch (_: Exception) {
                false
            }
            val status = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            call.respond(status, HealthResponse(
                status = if (dbOk) "healthy" else "degraded",
            ))
        }

        // Public routes (no auth required) + device management (auth required for DELETE /devices/me)
        deviceRoutes(sessionUtil)
        authRoutes(config, sessionUtil)

        // Authenticated routes
        bucketRoutes(bucketService, wsManager)
        syncRoutes(config, syncService, pushService, wsManager, sessionUtil)
        blobRoutes(blobService, config.allowedBlobContentTypes)
        pushRoutes(pushService)
        keyRoutes(keyService)
    }
}

/**
 * SEC4-S-11: Clean up orphan .tmp files in a storage directory that are older than 1 hour.
 * These are leftover from uploads that crashed between writing the temp file and committing
 * the DB row (or renaming to the final filename).
 */
private fun cleanupOrphanTempFiles(storagePath: String) {
    val dir = File(storagePath)
    if (!dir.exists() || !dir.isDirectory) return

    val oneHourAgo = System.currentTimeMillis() - 3_600_000L
    val logger = LoggerFactory.getLogger("Application")

    dir.listFiles()?.filter { it.name.endsWith(".tmp") && it.lastModified() < oneHourAgo }?.forEach { file ->
        if (file.delete()) {
            logger.info("Cleaned up orphan temp file: {}", file.name)
        }
    }
}

/**
 * SEC5-S-14: Prune acknowledged ops for all buckets that have checkpoints.
 */
private suspend fun pruneAllBuckets(syncService: SyncService) {
    val bucketIds = dbQuery {
        Checkpoints.selectAll()
            .map { row -> row[Checkpoints.bucketId] }
            .distinct()
    }
    for (bucketId in bucketIds) {
        syncService.pruneAcknowledgedOps(bucketId)
    }
}
