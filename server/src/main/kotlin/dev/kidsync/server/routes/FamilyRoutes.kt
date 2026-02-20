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

fun Route.familyRoutes() {
    authenticate("auth-jwt") {
        rateLimit(RateLimitName("general")) {
            route("/families") {
                post {
                    val principal = call.userPrincipal()
                    val request = call.receive<CreateFamilyRequest>()

                    if (request.name.isBlank() || request.name.length > 100) {
                        throw ApiException(400, "INVALID_REQUEST", "Family name must be 1-100 characters")
                    }

                    val familyId = UUID.randomUUID().toString()
                    val now = LocalDateTime.now(ZoneOffset.UTC)

                    dbQuery {
                        Families.insert {
                            it[id] = familyId
                            it[name] = request.name
                            it[createdAt] = now
                        }

                        FamilyMembers.insert {
                            it[userId] = principal.userId
                            it[FamilyMembers.familyId] = familyId
                            it[role] = "ADMIN"
                            it[joinedAt] = now
                        }
                    }

                    call.respond(HttpStatusCode.Created, CreateFamilyResponse(familyId = familyId))
                }

                route("/{familyId}") {
                    post("/invite") {
                        val principal = call.userPrincipal()
                        val familyId = call.parameters["familyId"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing familyId")

                        dbQuery {
                            // Verify user is an ADMIN member of this family
                            val membership = FamilyMembers.selectAll().where {
                                (FamilyMembers.userId eq principal.userId) and
                                    (FamilyMembers.familyId eq familyId)
                            }.firstOrNull()
                                ?: throw ApiException(403, "FORBIDDEN", "You are not a member of this family")

                            if (membership[FamilyMembers.role] != "ADMIN") {
                                throw ApiException(403, "FORBIDDEN", "Only admins can create invitations")
                            }

                            val inviteId = UUID.randomUUID().toString()
                            val inviteToken = "inv_" + UUID.randomUUID().toString().replace("-", "").take(24)
                            val now = LocalDateTime.now(ZoneOffset.UTC)
                            val expiresAt = now.plusHours(48)

                            Invites.insert {
                                it[id] = inviteId
                                it[Invites.familyId] = familyId
                                it[createdBy] = principal.userId
                                it[token] = inviteToken
                                it[Invites.expiresAt] = expiresAt
                            }

                            call.respond(
                                HttpStatusCode.Created,
                                InviteResponse(
                                    inviteToken = inviteToken,
                                    expiresAt = expiresAt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                                )
                            )
                        }
                    }

                    post("/join") {
                        val principal = call.userPrincipal()
                        val familyId = call.parameters["familyId"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing familyId")
                        val request = call.receive<JoinFamilyRequest>()

                        dbQuery {
                            // Validate invite token
                            val now = LocalDateTime.now(ZoneOffset.UTC)
                            val invite = Invites.selectAll().where {
                                (Invites.token eq request.inviteToken) and
                                    (Invites.familyId eq familyId) and
                                    Invites.usedAt.isNull() and
                                    (Invites.expiresAt greater now)
                            }.firstOrNull()
                                ?: throw ApiException(404, "NOT_FOUND", "Invalid or expired invite token")

                            // Check if already a member
                            val existingMembership = FamilyMembers.selectAll().where {
                                (FamilyMembers.userId eq principal.userId) and
                                    (FamilyMembers.familyId eq familyId)
                            }.firstOrNull()

                            if (existingMembership != null) {
                                throw ApiException(409, "CONFLICT", "Already a member of this family")
                            }

                            // Mark invite as used
                            Invites.update({ Invites.id eq invite[Invites.id] }) {
                                it[usedAt] = now
                            }

                            // Add user as MEMBER
                            FamilyMembers.insert {
                                it[userId] = principal.userId
                                it[FamilyMembers.familyId] = familyId
                                it[role] = "MEMBER"
                                it[joinedAt] = now
                            }

                            // Update device public key if provided
                            if (request.devicePublicKey.isNotBlank()) {
                                Devices.update({ Devices.id eq principal.deviceId }) {
                                    it[publicKey] = request.devicePublicKey
                                }
                            }

                            // Return current members
                            val members = getMembersDto(familyId)

                            call.respond(
                                HttpStatusCode.OK,
                                JoinFamilyResponse(familyId = familyId, members = members)
                            )
                        }
                    }

                    get("/members") {
                        val principal = call.userPrincipal()
                        val familyId = call.parameters["familyId"]
                            ?: throw ApiException(400, "INVALID_REQUEST", "Missing familyId")

                        dbQuery {
                            // Verify membership
                            val membership = FamilyMembers.selectAll().where {
                                (FamilyMembers.userId eq principal.userId) and
                                    (FamilyMembers.familyId eq familyId)
                            }.firstOrNull()
                                ?: throw ApiException(403, "FORBIDDEN", "You are not a member of this family")

                            val members = getMembersDto(familyId)
                            call.respond(HttpStatusCode.OK, MembersResponse(members = members))
                        }
                    }
                }
            }
        }
    }
}

private fun getMembersDto(familyId: String): List<FamilyMemberDto> {
    val memberRows = (FamilyMembers innerJoin Users)
        .selectAll()
        .where { FamilyMembers.familyId eq familyId }
        .toList()

    return memberRows.map { memberRow ->
        val userId = memberRow[FamilyMembers.userId]

        val devices = Devices.selectAll()
            .where { Devices.userId eq userId }
            .map { deviceRow ->
                DeviceSummaryDto(
                    deviceId = deviceRow[Devices.id],
                    deviceName = deviceRow[Devices.deviceName],
                    publicKey = deviceRow[Devices.publicKey],
                    createdAt = deviceRow[Devices.createdAt].atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT),
                    revokedAt = deviceRow[Devices.revokedAt]?.atOffset(ZoneOffset.UTC)
                        ?.format(DateTimeFormatter.ISO_INSTANT),
                )
            }

        // Get primary device public key (first active device)
        val primaryKey = devices.firstOrNull { it.revokedAt == null }?.publicKey ?: ""

        FamilyMemberDto(
            userId = userId,
            displayName = memberRow[Users.displayName],
            role = memberRow[FamilyMembers.role],
            publicKey = primaryKey,
            devices = devices,
        )
    }
}
