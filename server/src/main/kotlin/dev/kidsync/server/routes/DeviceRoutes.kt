package dev.kidsync.server.routes

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.plugins.userPrincipal
import dev.kidsync.server.services.ApiException
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

fun Route.deviceRoutes() {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("general")) {
            route("/devices") {
                post {
                    val principal = call.userPrincipal()
                    val request = call.receive<RegisterDeviceRequest>()

                    if (request.deviceName.isBlank() || request.deviceName.length > 100) {
                        throw ApiException(400, "INVALID_REQUEST", "Device name must be 1-100 characters")
                    }

                    val deviceId = UUID.randomUUID().toString()
                    val now = LocalDateTime.now(ZoneOffset.UTC)

                    dbQuery {
                        Devices.insert {
                            it[id] = deviceId
                            it[userId] = principal.userId
                            it[publicKey] = request.publicKey
                            it[deviceName] = request.deviceName
                            it[createdAt] = now
                        }
                    }

                    call.respond(HttpStatusCode.Created, RegisterDeviceResponse(deviceId = deviceId))
                }

                get {
                    val principal = call.userPrincipal()

                    val devices = dbQuery {
                        Devices.selectAll()
                            .where { Devices.userId eq principal.userId }
                            .map { row ->
                                DeviceDto(
                                    deviceId = row[Devices.id],
                                    deviceName = row[Devices.deviceName],
                                    publicKey = row[Devices.publicKey],
                                    createdAt = row[Devices.createdAt].atOffset(ZoneOffset.UTC)
                                        .format(DateTimeFormatter.ISO_INSTANT),
                                    revokedAt = row[Devices.revokedAt]?.atOffset(ZoneOffset.UTC)
                                        ?.format(DateTimeFormatter.ISO_INSTANT),
                                )
                            }
                    }

                    call.respond(HttpStatusCode.OK, DeviceListResponse(devices = devices))
                }

                delete("/{deviceId}") {
                    val principal = call.userPrincipal()
                    val deviceId = call.parameters["deviceId"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing deviceId")

                    dbQuery {
                        val device = Devices.selectAll().where { Devices.id eq deviceId }.firstOrNull()
                            ?: throw ApiException(404, "NOT_FOUND", "Device not found")

                        // Only the owner can revoke their device
                        if (device[Devices.userId] != principal.userId) {
                            // Check if the caller is a family admin
                            val callerFamilies = FamilyMembers.selectAll()
                                .where { (FamilyMembers.userId eq principal.userId) and (FamilyMembers.role eq "ADMIN") }
                                .map { it[FamilyMembers.familyId] }

                            val deviceOwnerFamilies = FamilyMembers.selectAll()
                                .where { FamilyMembers.userId eq device[Devices.userId] }
                                .map { it[FamilyMembers.familyId] }

                            val sharedAdminFamilies = callerFamilies.intersect(deviceOwnerFamilies.toSet())
                            if (sharedAdminFamilies.isEmpty()) {
                                throw ApiException(403, "FORBIDDEN", "Not authorized to revoke this device")
                            }
                        }

                        if (device[Devices.revokedAt] != null) {
                            throw ApiException(409, "CONFLICT", "Device is already revoked")
                        }

                        Devices.update({ Devices.id eq deviceId }) {
                            it[revokedAt] = LocalDateTime.now(ZoneOffset.UTC)
                        }
                    }

                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
