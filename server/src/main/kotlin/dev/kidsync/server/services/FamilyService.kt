package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import org.jetbrains.exposed.sql.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

class FamilyService(private val config: AppConfig) {

    suspend fun createFamily(userId: String, request: CreateFamilyRequest): CreateFamilyResponse {
        if (request.name.isBlank() || request.name.length > 100) {
            throw ApiException(400, "INVALID_REQUEST", "Family name must be 1-100 characters")
        }

        val familyId = UUID.randomUUID().toString()
        val now = LocalDateTime.now(ZoneOffset.UTC)

        dbQuery {
            Families.insert {
                it[id] = familyId
                it[name] = request.name
                it[isSolo] = request.solo
                it[createdAt] = now
            }

            FamilyMembers.insert {
                it[FamilyMembers.userId] = userId
                it[FamilyMembers.familyId] = familyId
                it[role] = "ADMIN"
                it[joinedAt] = now
            }
        }

        return CreateFamilyResponse(familyId = familyId, isSolo = request.solo)
    }

    suspend fun createInvite(userId: String, familyId: String): InviteResponse {
        return dbQuery {
            // Verify user is an ADMIN member of this family
            val membership = FamilyMembers.selectAll().where {
                (FamilyMembers.userId eq userId) and
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
                it[createdBy] = userId
                it[token] = inviteToken
                it[Invites.expiresAt] = expiresAt
            }

            InviteResponse(
                inviteToken = inviteToken,
                expiresAt = expiresAt.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
            )
        }
    }

    suspend fun joinFamily(
        userId: String,
        deviceId: String,
        familyId: String,
        request: JoinFamilyRequest,
    ): JoinFamilyResponse {
        return dbQuery {
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
                (FamilyMembers.userId eq userId) and
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
                it[FamilyMembers.userId] = userId
                it[FamilyMembers.familyId] = familyId
                it[role] = "MEMBER"
                it[joinedAt] = now
            }

            // Update device public key if provided
            if (request.devicePublicKey.isNotBlank()) {
                Devices.update({ Devices.id eq deviceId }) {
                    it[publicKey] = request.devicePublicKey
                }
            }

            // Auto-convert solo family to shared when a co-parent joins
            Families.update({ Families.id eq familyId }) {
                it[isSolo] = false
            }

            // Return current members
            val members = getMembersDto(familyId)

            JoinFamilyResponse(familyId = familyId, members = members)
        }
    }

    suspend fun convertToShared(userId: String, familyId: String): CreateFamilyResponse {
        return dbQuery {
            // Verify user is an ADMIN member of this family
            val membership = FamilyMembers.selectAll().where {
                (FamilyMembers.userId eq userId) and
                    (FamilyMembers.familyId eq familyId)
            }.firstOrNull()
                ?: throw ApiException(403, "FORBIDDEN", "You are not a member of this family")

            if (membership[FamilyMembers.role] != "ADMIN") {
                throw ApiException(403, "FORBIDDEN", "Only admins can convert a family")
            }

            Families.update({ Families.id eq familyId }) {
                it[isSolo] = false
            }

            CreateFamilyResponse(familyId = familyId, isSolo = false)
        }
    }

    suspend fun getMembers(userId: String, familyId: String): MembersResponse {
        return dbQuery {
            // Verify membership
            FamilyMembers.selectAll().where {
                (FamilyMembers.userId eq userId) and
                    (FamilyMembers.familyId eq familyId)
            }.firstOrNull()
                ?: throw ApiException(403, "FORBIDDEN", "You are not a member of this family")

            val members = getMembersDto(familyId)
            MembersResponse(members = members)
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
}
