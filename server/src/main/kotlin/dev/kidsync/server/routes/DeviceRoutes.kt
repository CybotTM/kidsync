package dev.kidsync.server.routes

import dev.kidsync.server.db.Devices
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.services.ApiException
import io.ktor.http.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

fun Route.deviceRoutes() {
    rateLimit(RateLimitName("auth")) {
        /**
         * POST /register
         * Register a new device with its public keys. No auth required.
         */
        post("/register") {
            val request = call.receive<RegisterRequest>()

            if (request.signingKey.isBlank()) {
                throw ApiException(400, "INVALID_REQUEST", "signingKey is required")
            }
            if (request.encryptionKey.isBlank()) {
                throw ApiException(400, "INVALID_REQUEST", "encryptionKey is required")
            }

            val deviceId = UUID.randomUUID().toString()
            val now = LocalDateTime.now(ZoneOffset.UTC)

            dbQuery {
                // Check if signing key already registered
                val existing = Devices.selectAll()
                    .where { Devices.signingKey eq request.signingKey }
                    .firstOrNull()

                if (existing != null) {
                    throw ApiException(409, "CONFLICT", "Signing key already registered")
                }

                Devices.insert {
                    it[id] = deviceId
                    it[signingKey] = request.signingKey
                    it[encryptionKey] = request.encryptionKey
                    it[createdAt] = now
                }
            }

            call.respond(HttpStatusCode.Created, RegisterResponse(deviceId = deviceId))
        }
    }
}
