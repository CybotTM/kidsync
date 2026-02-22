package dev.kidsync.server.services

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SEC6-S-13: Push tokens are encrypted at rest using AES-256-GCM when the
 * KIDSYNC_PUSH_TOKEN_KEY environment variable is configured (base64-encoded 256-bit key).
 * If the key is not configured, tokens are stored in plaintext with a warning logged.
 */
class PushService(private val encryptionKeyBase64: String? = null) {

    private val logger = LoggerFactory.getLogger(PushService::class.java)
    private val random = SecureRandom()

    // SEC6-S-13: Parse and validate the encryption key on construction
    private val encryptionKey: SecretKeySpec? = encryptionKeyBase64?.let { keyStr ->
        try {
            val keyBytes = Base64.getDecoder().decode(keyStr)
            if (keyBytes.size != 32) {
                logger.error("KIDSYNC_PUSH_TOKEN_KEY must be exactly 32 bytes (256 bits), got {} bytes", keyBytes.size)
                null
            } else {
                SecretKeySpec(keyBytes, "AES")
            }
        } catch (e: Exception) {
            logger.error("Failed to decode KIDSYNC_PUSH_TOKEN_KEY: {}", e.message)
            null
        }
    }

    init {
        if (encryptionKey == null && encryptionKeyBase64 != null) {
            logger.warn("Push token encryption key was provided but is invalid. Tokens will be stored in PLAINTEXT.")
        } else if (encryptionKey == null) {
            logger.warn("KIDSYNC_PUSH_TOKEN_KEY not set. Push tokens will be stored in PLAINTEXT.")
        } else {
            logger.info("Push token encryption enabled (AES-256-GCM)")
        }
    }

    /**
     * SEC6-S-13: Encrypt a push token using AES-256-GCM.
     * Returns base64-encoded "IV:ciphertext" format.
     */
    fun encryptToken(plaintext: String): String {
        if (encryptionKey == null) return plaintext

        val iv = ByteArray(12) // 96-bit IV for GCM
        random.nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val encoder = Base64.getEncoder()
        return "${encoder.encodeToString(iv)}:${encoder.encodeToString(ciphertext)}"
    }

    /**
     * SEC6-S-13: Decrypt a push token from AES-256-GCM "IV:ciphertext" format.
     * If encryption is not configured, returns the raw value.
     * If the value looks like plaintext (no ':'), returns it directly (legacy/unencrypted).
     *
     * SEC7-S-05: Returns null on decryption failure instead of returning the raw ciphertext.
     * Callers must handle null (skip sending to that device) rather than accidentally
     * using encrypted data as a push token.
     */
    fun decryptToken(stored: String): String? {
        if (encryptionKey == null) return stored
        if (!stored.contains(':')) return stored // Plaintext (legacy/unencrypted)

        return try {
            val parts = stored.split(':', limit = 2)
            val decoder = Base64.getDecoder()
            val iv = decoder.decode(parts[0])
            val ciphertext = decoder.decode(parts[1])

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to decrypt push token for device, skipping: {}", e.message)
            null
        }
    }

    /**
     * Register or update a push notification token for a device.
     * SEC6-S-13: Token is encrypted before storage if encryption key is configured.
     */
    suspend fun registerToken(deviceId: String, token: String, platform: String) {
        if (platform !in listOf("FCM", "APNS")) {
            throw ApiException(400, "INVALID_REQUEST", "Platform must be FCM or APNS")
        }
        if (token.isBlank() || token.length > 4096) {
            throw ApiException(400, "INVALID_REQUEST", "Invalid push token")
        }

        // SEC6-S-13: Encrypt token before storing
        val storedToken = encryptToken(token)

        dbQuery {
            val now = LocalDateTime.now(ZoneOffset.UTC)

            // Upsert: delete old, insert new
            PushTokens.deleteWhere { PushTokens.deviceId eq deviceId }
            PushTokens.insert {
                it[PushTokens.deviceId] = deviceId
                it[PushTokens.token] = storedToken
                it[PushTokens.platform] = platform
                it[updatedAt] = now
            }
        }
    }

    /**
     * Send push notifications to all devices in a bucket (except the source device).
     * This is a stub -- in production, this would call FCM/APNS APIs.
     * SEC6-S-13: Tokens are decrypted before use.
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
                // SEC6-S-13: Decrypt token for use
                // SEC7-S-05: Skip devices whose token cannot be decrypted
                val pushToken = decryptToken(tokenRow[PushTokens.token]) ?: continue

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
