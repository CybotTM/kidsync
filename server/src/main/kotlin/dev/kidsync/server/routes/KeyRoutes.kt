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

fun Route.keyRoutes() {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("general")) {
            route("/keys") {
                // POST /keys/wrapped
                post("/wrapped") {
                    val principal = call.userPrincipal()
                    val request = call.receive<UploadWrappedKeyRequest>()

                    dbQuery {
                        // Verify target device exists
                        val targetDevice = Devices.selectAll().where { Devices.id eq request.targetDeviceId }
                            .firstOrNull()
                            ?: throw ApiException(404, "NOT_FOUND", "Target device not found")

                        // Verify both users are in the same family
                        val callerFamilies = FamilyMembers.selectAll()
                            .where { FamilyMembers.userId eq principal.userId }
                            .map { it[FamilyMembers.familyId] }
                            .toSet()

                        val targetUserFamilies = FamilyMembers.selectAll()
                            .where { FamilyMembers.userId eq targetDevice[Devices.userId] }
                            .map { it[FamilyMembers.familyId] }
                            .toSet()

                        if (callerFamilies.intersect(targetUserFamilies).isEmpty()) {
                            throw ApiException(403, "FORBIDDEN", "Not in the same family as the target device")
                        }

                        // Upsert: replace existing wrapped key for this device + epoch
                        WrappedKeys.deleteWhere {
                            (targetDeviceId eq request.targetDeviceId) and (keyEpoch eq request.keyEpoch)
                        }

                        WrappedKeys.insert {
                            it[targetDeviceId] = request.targetDeviceId
                            it[wrappedDek] = request.wrappedDek
                            it[keyEpoch] = request.keyEpoch
                            it[wrappedByUserId] = principal.userId
                            it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
                        }
                    }

                    call.respond(HttpStatusCode.Created)
                }

                // GET /keys/wrapped/{deviceId}
                get("/wrapped/{deviceId}") {
                    val principal = call.userPrincipal()
                    val deviceId = call.parameters["deviceId"]
                        ?: throw ApiException(400, "INVALID_REQUEST", "Missing deviceId")
                    val keyEpoch = call.request.queryParameters["keyEpoch"]?.toIntOrNull()

                    val result = dbQuery {
                        // Only the device owner can get their wrapped key
                        val device = Devices.selectAll().where { Devices.id eq deviceId }.firstOrNull()
                            ?: throw ApiException(404, "NOT_FOUND", "Device not found")

                        if (device[Devices.userId] != principal.userId) {
                            throw ApiException(403, "FORBIDDEN", "Can only retrieve your own device's wrapped key")
                        }

                        val query = if (keyEpoch != null) {
                            WrappedKeys.selectAll().where {
                                (WrappedKeys.targetDeviceId eq deviceId) and (WrappedKeys.keyEpoch eq keyEpoch)
                            }
                        } else {
                            WrappedKeys.selectAll()
                                .where { WrappedKeys.targetDeviceId eq deviceId }
                                .orderBy(WrappedKeys.keyEpoch, SortOrder.DESC)
                                .limit(1)
                        }

                        query.firstOrNull()
                            ?: throw ApiException(404, "NOT_FOUND", "No wrapped key found for this device")
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        WrappedKeyResponse(
                            wrappedDek = result[WrappedKeys.wrappedDek],
                            keyEpoch = result[WrappedKeys.keyEpoch],
                            wrappedBy = result[WrappedKeys.wrappedByUserId],
                        )
                    )
                }

                // POST /keys/recovery
                post("/recovery") {
                    val principal = call.userPrincipal()
                    val request = call.receive<UploadRecoveryBlobRequest>()

                    dbQuery {
                        // Upsert: replace existing recovery blob
                        RecoveryBlobs.deleteWhere { userId eq principal.userId }

                        RecoveryBlobs.insert {
                            it[userId] = principal.userId
                            it[encryptedRecoveryBlob] = request.encryptedRecoveryBlob
                            it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
                        }
                    }

                    call.respond(HttpStatusCode.Created)
                }

                // GET /keys/recovery
                get("/recovery") {
                    val principal = call.userPrincipal()

                    val result = dbQuery {
                        RecoveryBlobs.selectAll().where { RecoveryBlobs.userId eq principal.userId }
                            .firstOrNull()
                            ?: throw ApiException(404, "NOT_FOUND", "No recovery blob found")
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        RecoveryBlobResponse(
                            encryptedRecoveryBlob = result[RecoveryBlobs.encryptedRecoveryBlob],
                        )
                    )
                }
            }
        }
    }
}
