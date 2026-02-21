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
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import kotlin.concurrent.fixedRateTimer

fun main() {
    val config = AppConfig()
    val logger = LoggerFactory.getLogger("Application")

    logger.info("Starting KidSync server v${config.serverVersion} on ${config.host}:${config.port}")

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig()) {
    // Initialize database
    DatabaseFactory.init(config)

    val startTime = System.currentTimeMillis()

    // Initialize utilities and services
    val sessionUtil = SessionUtil(config)
    val bucketService = BucketService(config.blobStoragePath, config.snapshotStoragePath)
    val keyService = KeyService()
    val syncService = SyncService(config)
    val blobService = BlobService(config)
    val pushService = PushService()
    val wsManager = WebSocketManager()

    // Periodic cleanup of expired sessions and challenges (every 5 minutes)
    fixedRateTimer("session-cleanup", daemon = true, period = 5 * 60 * 1000L) {
        sessionUtil.cleanup()
    }

    // Install plugins
    configureSerialization()
    configureAuth(sessionUtil)
    configureCORS()
    configureRateLimit()
    configureStatusPages()
    configureWebSockets()

    install(CallLogging) {
        level = Level.INFO
    }

    // Configure routes
    routing {
        // Health check (unauthenticated)
        get("/health") {
            val dbOk = try {
                dbQuery { true }
            } catch (_: Exception) {
                false
            }
            val status = if (dbOk) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable
            val uptimeSeconds = (System.currentTimeMillis() - startTime) / 1000
            call.respond(status, HealthResponse(
                status = if (dbOk) "healthy" else "degraded",
                version = config.serverVersion,
                uptime = uptimeSeconds,
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
