package dev.kidsync.server.services

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset

class PushService {

    private val logger = LoggerFactory.getLogger(PushService::class.java)

    /**
     * Register or update a push notification token for a device.
     */
    suspend fun registerToken(deviceId: String, token: String, platform: String): Result<Unit> {
        if (platform !in listOf("FCM", "APNS")) {
            return Result.failure(ApiException(400, "INVALID_REQUEST", "Platform must be FCM or APNS"))
        }
        if (token.isBlank() || token.length > 4096) {
            return Result.failure(ApiException(400, "INVALID_REQUEST", "Invalid push token"))
        }

        dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)

            // Upsert: delete old, insert new
            PushTokens.deleteWhere { PushTokens.deviceId eq deviceId }
            PushTokens.insert {
                it[PushTokens.deviceId] = deviceId
                it[PushTokens.token] = token
                it[PushTokens.platform] = platform
                it[updatedAt] = now
            }
        }

        return Result.success(Unit)
    }

    /**
     * Send push notifications to all family devices (except the source device).
     * This is a stub implementation -- in production, this would call FCM/APNS APIs.
     */
    suspend fun notifyFamilyDevices(familyId: String, excludeDeviceId: String, latestSequence: Long) {
        dbQuery {
            // Get all devices in the family
            val familyUserIds = FamilyMembers.selectAll()
                .where { FamilyMembers.familyId eq familyId }
                .map { it[FamilyMembers.userId] }

            val deviceIds = Devices.selectAll()
                .where {
                    (Devices.userId inList familyUserIds) and
                        Devices.revokedAt.isNull() and
                        (Devices.id neq excludeDeviceId)
                }
                .map { it[Devices.id] }

            // Get push tokens for those devices
            val tokens = PushTokens.selectAll()
                .where { PushTokens.deviceId inList deviceIds }
                .toList()

            for (tokenRow in tokens) {
                val pushToken = tokenRow[PushTokens.token]
                val platform = tokenRow[PushTokens.platform]

                // In production, this would call the actual push API
                logger.info(
                    "PUSH [{}] -> device={} family={} latestSeq={}",
                    platform,
                    tokenRow[PushTokens.deviceId],
                    familyId,
                    latestSequence,
                )
            }
        }
    }
}
