package dev.kidsync.server.services

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

class BucketService(
    private val blobStoragePath: String,
    private val snapshotStoragePath: String,
) {

    /**
     * Create a new anonymous bucket. The creator device automatically gets access.
     */
    suspend fun createBucket(deviceId: String): BucketResponse {
        val bucketId = UUID.randomUUID().toString()
        val now = LocalDateTime.now(ZoneOffset.UTC)

        dbQuery {
            Buckets.insert {
                it[id] = bucketId
                it[createdBy] = deviceId
                it[createdAt] = now
            }

            BucketAccess.insert {
                it[BucketAccess.bucketId] = bucketId
                it[BucketAccess.deviceId] = deviceId
                it[grantedAt] = now
            }
        }

        return BucketResponse(bucketId = bucketId)
    }

    /**
     * Delete a bucket. Only the creator device can delete. Cascading delete of all related data.
     */
    suspend fun deleteBucket(bucketId: String, deviceId: String) {
        dbQuery {
            val bucket = Buckets.selectAll().where { Buckets.id eq bucketId }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Bucket not found")

            if (bucket[Buckets.createdBy] != deviceId) {
                throw ApiException(403, "NOT_BUCKET_CREATOR", "Only the bucket creator can delete it")
            }

            // Wrapped keys for devices in this bucket are intentionally NOT deleted here.
            // They are harmless since the bucket's data is already deleted, and deleting
            // them would over-delete keys for devices that have access to other buckets.

            // Delete all related data
            Ops.deleteWhere { Ops.bucketId eq bucketId }
            Checkpoints.deleteWhere { Checkpoints.bucketId eq bucketId }

            // Delete blob files from disk
            val blobRows = Blobs.selectAll().where { Blobs.bucketId eq bucketId }.toList()
            for (row in blobRows) {
                File(row[Blobs.filePath]).delete()
            }
            Blobs.deleteWhere { Blobs.bucketId eq bucketId }

            // Delete snapshot files from disk
            val snapshotRows = Snapshots.selectAll().where { Snapshots.bucketId eq bucketId }.toList()
            for (row in snapshotRows) {
                File(row[Snapshots.filePath]).delete()
            }
            Snapshots.deleteWhere { Snapshots.bucketId eq bucketId }

            InviteTokens.deleteWhere { InviteTokens.bucketId eq bucketId }
            BucketAccess.deleteWhere { BucketAccess.bucketId eq bucketId }
            Buckets.deleteWhere { Buckets.id eq bucketId }
        }
    }

    /**
     * Register an invite token hash for a bucket. The device must have access.
     */
    suspend fun createInvite(bucketId: String, deviceId: String, tokenHash: String): InviteResponse {
        return dbQuery {
            requireBucketAccess(bucketId, deviceId)

            val now = LocalDateTime.now(ZoneOffset.UTC)
            val expiresAt = now.plusHours(24)

            // Rate limit: max 5 active (unused, non-expired) invites per bucket
            val activeInviteCount = InviteTokens.selectAll().where {
                (InviteTokens.bucketId eq bucketId) and
                    InviteTokens.usedAt.isNull() and
                    (InviteTokens.expiresAt greater now)
            }.count()

            if (activeInviteCount >= 5) {
                throw ApiException(429, "RATE_LIMITED", "Too many active invites for this bucket (max 5)")
            }

            // Upsert: replace if same hash exists within this bucket
            InviteTokens.deleteWhere {
                (InviteTokens.tokenHash eq tokenHash) and (InviteTokens.bucketId eq bucketId)
            }

            InviteTokens.insert {
                it[InviteTokens.tokenHash] = tokenHash
                it[InviteTokens.bucketId] = bucketId
                it[InviteTokens.createdAt] = now
                it[InviteTokens.expiresAt] = expiresAt
            }

            InviteResponse(
                expiresAt = expiresAt.atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ISO_INSTANT),
            )
        }
    }

    /**
     * Redeem an invite token to join a bucket.
     */
    suspend fun joinBucket(bucketId: String, deviceId: String, inviteToken: String): JoinResponse {
        return dbQuery {
            val tokenHash = HashUtil.sha256HexString(inviteToken)
            val now = LocalDateTime.now(ZoneOffset.UTC)

            // Stage 1: Find token by hash + bucketId
            val invite = InviteTokens.selectAll().where {
                (InviteTokens.tokenHash eq tokenHash) and
                    (InviteTokens.bucketId eq bucketId)
            }.firstOrNull()
                ?: throw ApiException(404, "INVITE_INVALID", "Invalid invite token")

            // Stage 2: Check if already used
            if (invite[InviteTokens.usedAt] != null) {
                throw ApiException(410, "INVITE_CONSUMED", "Invite token has already been used")
            }

            // Stage 3: Check if expired
            if (invite[InviteTokens.expiresAt] <= now) {
                throw ApiException(410, "INVITE_EXPIRED", "Invite token has expired")
            }

            // Check if already has active access
            val existingActive = BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq deviceId) and
                    BucketAccess.revokedAt.isNull()
            }.firstOrNull()

            if (existingActive != null) {
                throw ApiException(409, "INVALID_REQUEST", "Device already has access to this bucket")
            }

            // Mark invite as used
            InviteTokens.update({ InviteTokens.tokenHash eq tokenHash }) {
                it[usedAt] = now
            }

            // Check for a previously revoked access record and re-activate it
            val existingRevoked = BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq deviceId) and
                    BucketAccess.revokedAt.isNotNull()
            }.firstOrNull()

            if (existingRevoked != null) {
                // Re-activate existing revoked record
                BucketAccess.update({
                    (BucketAccess.bucketId eq bucketId) and
                        (BucketAccess.deviceId eq deviceId) and
                        BucketAccess.revokedAt.isNotNull()
                }) {
                    it[grantedAt] = now
                    it[revokedAt] = null
                }
            } else {
                // Grant new access
                BucketAccess.insert {
                    it[BucketAccess.bucketId] = bucketId
                    it[BucketAccess.deviceId] = deviceId
                    it[grantedAt] = now
                }
            }

            JoinResponse(bucketId = bucketId, deviceId = deviceId)
        }
    }

    /**
     * List all devices with active access to a bucket.
     */
    suspend fun listDevices(bucketId: String, deviceId: String): DeviceListResponse {
        return dbQuery {
            requireBucketAccess(bucketId, deviceId)

            val devices = (BucketAccess innerJoin Devices).selectAll().where {
                (BucketAccess.bucketId eq bucketId) and
                    BucketAccess.revokedAt.isNull()
            }.map { row ->
                DeviceInfo(
                    deviceId = row[BucketAccess.deviceId],
                    signingKey = row[Devices.signingKey],
                    encryptionKey = row[Devices.encryptionKey],
                    grantedAt = row[BucketAccess.grantedAt]
                        .atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_INSTANT),
                )
            }

            DeviceListResponse(devices = devices)
        }
    }

    /**
     * Self-revoke: device removes its own access from a bucket.
     */
    suspend fun selfRevoke(bucketId: String, deviceId: String) {
        dbQuery {
            val access = BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq deviceId) and
                    BucketAccess.revokedAt.isNull()
            }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "No active access to this bucket")

            BucketAccess.update({
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq deviceId) and
                    BucketAccess.revokedAt.isNull()
            }) {
                it[revokedAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    companion object {
        /**
         * Verify that a device has active (non-revoked) access to a bucket.
         * Throws ApiException if not.
         */
        fun requireBucketAccess(bucketId: String, deviceId: String) {
            val access = BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq deviceId) and
                    BucketAccess.revokedAt.isNull()
            }.firstOrNull()

            if (access == null) {
                throw ApiException(403, "BUCKET_ACCESS_DENIED", "Device does not have access to this bucket")
            }
        }
    }
}
