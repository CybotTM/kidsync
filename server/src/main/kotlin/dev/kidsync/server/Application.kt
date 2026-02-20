package dev.kidsync.server

import dev.kidsync.server.db.DatabaseFactory
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.plugins.*
import dev.kidsync.server.routes.*
import dev.kidsync.server.services.*
import dev.kidsync.server.util.JwtUtil
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

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

    // Initialize utilities and services
    val jwtUtil = JwtUtil(config)
    val authService = AuthService(config, jwtUtil)
    val syncService = SyncService(config)
    val blobService = BlobService(config)
    val pushService = PushService()
    val wsManager = WebSocketManager()

    // Install plugins
    configureSerialization()
    configureAuth(config, jwtUtil)
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
            call.respond(status, mapOf("status" to if (dbOk) "ok" else "degraded", "db" to dbOk))
        }

        authRoutes(authService)
        familyRoutes()
        deviceRoutes()
        syncRoutes(config, syncService, pushService, wsManager, jwtUtil)
        blobRoutes(blobService)
        pushRoutes(pushService)
        keyRoutes()
    }
}
