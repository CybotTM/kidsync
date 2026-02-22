package dev.kidsync.server.services

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset

// SEC6-S-13: TODO - Push tokens should be encrypted at rest in the database.
// Currently stored as plaintext in the push_tokens table. Consider using envelope
// encryption with a server-side key to protect tokens if the database is compromised.
class PushService {

    private val logger = LoggerFactory.getLogger(PushService::class.java)

    /**
     * Register or update a push notification token for a device.
     */
    suspend fun registerToken(deviceId: String, token: String, platform: String) {
        if (platform !in listOf("FCM", "APNS")) {
            throw ApiException(400, "INVALID_REQUEST", "Platform must be FCM or APNS")
        }
        if (token.isBlank() || token.length > 4096) {
            throw ApiException(400, "INVALID_REQUEST", "Invalid push token")
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
    }

    /**
     * Send push notifications to all devices in a bucket (except the source device).
     * This is a stub -- in production, this would call FCM/APNS APIs.
     */
    suspend fun notifyBucketDevices(bucketId: String, excludeDeviceId: String, latestSequence: Long) {
        dbQuery {
            // Get all devices with active access to this bucket
            val deviceIds = BucketAccess.selectAll()
                .where {
                    (BucketAccess.bucketId eq bucketId) and
                        BucketAccess.revokedAt.isNull() and
                        (BucketAccess.deviceId neq excludeDeviceId)
                }
                .map { it[BucketAccess.deviceId] }

            // Get push tokens for those devices
            val tokens = PushTokens.selectAll()
                .where { PushTokens.deviceId inList deviceIds }
                .toList()

            for (tokenRow in tokens) {
                val platform = tokenRow[PushTokens.platform]

                // In production, this would call the actual push API
                // Payload is opaque: { "type": "sync", "bucket": bucketId }
                logger.info(
                    "PUSH [{}] -> device={} bucket={} latestSeq={}",
                    platform,
                    tokenRow[PushTokens.deviceId],
                    bucketId,
                    latestSequence,
                )
            }
        }
    }
}
