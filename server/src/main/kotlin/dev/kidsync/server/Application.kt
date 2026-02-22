package dev.kidsync.server

import dev.kidsync.server.db.DatabaseFactory
import dev.kidsync.server.db.DatabaseFactory.dbQuery
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

fun Application.module(config: AppConfig = AppConfig()) {
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
    val bucketService = BucketService(config.blobStoragePath, config.snapshotStoragePath, wsManager, sessionUtil)
    val keyService = KeyService()
    val syncService = SyncService(config)
    val blobService = BlobService(config)
    val pushService = PushService()

    // Periodic cleanup of expired sessions and challenges (every 5 minutes)
    // SEC-S-02: Sessions are now DB-backed; cleanup requires suspend context
    // SEC2-S-18: Bind the cleanup coroutine to app lifecycle so it's cancelled on shutdown
    val cleanupScope = CoroutineScope(Dispatchers.Default)
    val cleanupJob = cleanupScope.launch {
        while (isActive) {
            delay(5 * 60 * 1000L)
            try {
                sessionUtil.cleanup()
            } catch (e: Exception) {
                LoggerFactory.getLogger("Application").warn("Session cleanup failed: {}", e.message)
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
    configureRateLimit()
    configureStatusPages()
    configureWebSockets()

    // SEC-S-13: Install ForwardedHeaders so rate limiter uses real client IP behind a proxy
    // SEC2-S-15: WARNING - XForwardedHeaders trusts ALL sources by default. In production,
    // this server MUST be deployed behind a trusted reverse proxy (nginx, Caddy, etc.) that
    // strips/overwrites X-Forwarded-* headers from untrusted clients. Without this, an
    // attacker can spoof their IP address to bypass rate limiting.
    // TODO: When Ktor adds support for configuring trusted proxy addresses, restrict this
    // to only trust the known reverse proxy IPs. Alternatively, set KIDSYNC_TRUST_PROXY=false
    // to disable forwarded headers entirely if not behind a proxy.
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
        if (contentLength != null && contentLength > 10 * 1024 * 1024) {
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
        call.response.header("Strict-Transport-Security", "max-age=63072000; includeSubDomains; preload")
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

        // Public routes (no auth required)
        deviceRoutes()
        authRoutes(config, sessionUtil)

        // Authenticated routes
        bucketRoutes(bucketService, wsManager)
        syncRoutes(config, syncService, pushService, wsManager, sessionUtil)
        blobRoutes(blobService)
        pushRoutes(pushService)
        keyRoutes(keyService)
    }
}
