package dev.kidsync.server.services

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import dev.kidsync.server.util.SessionUtil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

private fun inviteInvalidException() = ApiException(404, "INVITE_INVALID", "Invalid or expired invite token")

class BucketService(
    private val blobStoragePath: String,
    private val snapshotStoragePath: String,
    // SEC3-S-05: Reference to WebSocketManager to disconnect devices on revocation
    private val wsManager: WebSocketManager? = null,
    // SEC4-S-07: Reference to SessionUtil to invalidate sessions on revocation
    private val sessionUtil: SessionUtil? = null,
    // SEC4-S-19: Maximum number of devices that can join a single bucket (configurable)
    private val maxDevicesPerBucket: Int = MAX_DEVICES_PER_BUCKET_DEFAULT,
) {

    private val logger = LoggerFactory.getLogger(BucketService::class.java)

    companion object {
        // SEC3-S-15: Maximum number of buckets a single device can create
        const val MAX_BUCKETS_PER_DEVICE = 10

        // SEC4-S-19: Default maximum number of devices that can join a single bucket
        const val MAX_DEVICES_PER_BUCKET_DEFAULT = 10

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

    /**
     * Create a new anonymous bucket. The creator device automatically gets access.
     *
     * SEC3-S-08: TODO - The bucket creator role cannot be transferred to another device.
     * If the creator device is lost, the bucket cannot be deleted by any other device.
     * Consider adding a creator transfer mechanism or multi-admin support in a future version.
     */
    suspend fun createBucket(deviceId: String): BucketResponse {
        val bucketId = UUID.randomUUID().toString()
        val now = LocalDateTime.now(ZoneOffset.UTC)

        dbQuery {
            // SEC3-S-15: Enforce per-device bucket creation limit
            val bucketCount = Buckets.selectAll()
                .where { Buckets.createdBy eq deviceId }
                .count()
            if (bucketCount >= MAX_BUCKETS_PER_DEVICE) {
                throw ApiException(429, "RATE_LIMITED", "Maximum number of buckets ($MAX_BUCKETS_PER_DEVICE) per device reached")
            }

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
        // SEC3-S-05: Collect device IDs before deletion to disconnect their WebSocket connections
        val deviceIdsInBucket = mutableListOf<String>()

        dbQuery {
            val bucket = Buckets.selectAll().where { Buckets.id eq bucketId }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Bucket not found")

            if (bucket[Buckets.createdBy] != deviceId) {
                throw ApiException(403, "NOT_BUCKET_CREATOR", "Only the bucket creator can delete it")
            }

            // SEC3-S-05: Collect all active device IDs for WS disconnection
            BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and BucketAccess.revokedAt.isNull()
            }.forEach { row ->
                deviceIdsInBucket.add(row[BucketAccess.deviceId])
            }

            // SEC3-S-24: Cascade-delete wrapped keys and key attestations for devices
            // that ONLY had access through this bucket (no other active buckets).
            // For devices with other bucket memberships, their keys remain intact.
            val deviceIdsOnlyInThisBucket = deviceIdsInBucket.filter { devId ->
                val otherBucketCount = BucketAccess.selectAll().where {
                    (BucketAccess.deviceId eq devId) and
                        (BucketAccess.bucketId neq bucketId) and
                        BucketAccess.revokedAt.isNull()
                }.count()
                otherBucketCount == 0L
            }
            for (devId in deviceIdsOnlyInThisBucket) {
                WrappedKeys.deleteWhere { (WrappedKeys.targetDevice eq devId) or (WrappedKeys.wrappedBy eq devId) }
                KeyAttestations.deleteWhere {
                    (KeyAttestations.signerDevice eq devId) or (KeyAttestations.attestedDevice eq devId)
                }
            }

            // Delete all related data
            Ops.deleteWhere { Ops.bucketId eq bucketId }
            Checkpoints.deleteWhere { Checkpoints.bucketId eq bucketId }

            // Delete blob files from disk
            // SEC-S-01: Path traversal protection - verify resolved path is within storage directory
            val blobDir = File(blobStoragePath).canonicalFile
            val blobRows = Blobs.selectAll().where { Blobs.bucketId eq bucketId }.toList()
            for (row in blobRows) {
                val blobFile = File(blobStoragePath, row[Blobs.filePath])
                if (blobFile.canonicalFile.startsWith(blobDir)) {
                    blobFile.delete()
                }
            }
            Blobs.deleteWhere { Blobs.bucketId eq bucketId }

            // Delete snapshot files from disk
            // SEC-S-01: Path traversal protection - verify resolved path is within storage directory
            val snapshotDir = File(snapshotStoragePath).canonicalFile
            val snapshotRows = Snapshots.selectAll().where { Snapshots.bucketId eq bucketId }.toList()
            for (row in snapshotRows) {
                val snapshotFile = File(snapshotStoragePath, row[Snapshots.filePath])
                if (snapshotFile.canonicalFile.startsWith(snapshotDir)) {
                    snapshotFile.delete()
                }
            }
            Snapshots.deleteWhere { Snapshots.bucketId eq bucketId }

            InviteTokens.deleteWhere { InviteTokens.bucketId eq bucketId }
            BucketAccess.deleteWhere { BucketAccess.bucketId eq bucketId }
            Buckets.deleteWhere { Buckets.id eq bucketId }
        }

        // SEC3-S-05: Terminate WebSocket connections for all devices in the deleted bucket
        for (devId in deviceIdsInBucket) {
            wsManager?.disconnectDevice(bucketId, devId)
        }

        // SEC6-S-11: Invalidate sessions for non-creator devices that no longer have
        // any active bucket memberships after this bucket was deleted.
        for (devId in deviceIdsInBucket) {
            if (devId == deviceId) continue // Skip the creator (who initiated the deletion)
            val remainingBuckets = dbQuery {
                BucketAccess.selectAll().where {
                    (BucketAccess.deviceId eq devId) and BucketAccess.revokedAt.isNull()
                }.count()
            }
            if (remainingBuckets == 0L) {
                logger.info("Device {} lost last bucket membership via bucket deletion, invalidating sessions", devId)
                sessionUtil?.deleteSessionsByDevice(devId)
            }
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
     *
     * SEC2-S-01: Uses atomic UPDATE with WHERE status='unused' (usedAt IS NULL) to prevent
     * race conditions where two concurrent joins could both read the same invite as pending
     * and both succeed. Only the first UPDATE that sets usedAt will affect 1 row.
     *
     * SEC2-S-02: Checks for previously revoked access and rejects the join. A device that
     * was removed from a bucket cannot rejoin using any invite -- it must be explicitly
     * re-invited by another device that creates a new invite after the revocation.
     */
    suspend fun joinBucket(bucketId: String, deviceId: String, inviteToken: String): JoinResponse {
        return dbQuery {
            val tokenHash = HashUtil.sha256HexString(inviteToken)
            val now = LocalDateTime.now(ZoneOffset.UTC)

            // SEC6-S-01: All invite error cases return the same generic error to prevent
            // enumeration. Server-side log messages differentiate for debugging.

            // Stage 1: Find token by hash + bucketId
            val invite = InviteTokens.selectAll().where {
                (InviteTokens.tokenHash eq tokenHash) and
                    (InviteTokens.bucketId eq bucketId)
            }.firstOrNull()
            if (invite == null) {
                logger.info("Invite token not found: bucket={}", bucketId)
                throw inviteInvalidException()
            }

            // Stage 2: Check if already used
            if (invite[InviteTokens.usedAt] != null) {
                logger.info("Invite token already consumed: bucket={}", bucketId)
                throw inviteInvalidException()
            }

            // Stage 3: Check if expired
            if (invite[InviteTokens.expiresAt] <= now) {
                logger.info("Invite token expired: bucket={}", bucketId)
                throw inviteInvalidException()
            }

            // SEC2-S-02: Check if this device was previously revoked from this bucket.
            // A revoked device cannot rejoin without an explicit new invitation created
            // after the revocation.
            val existingRevoked = BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq deviceId) and
                    BucketAccess.revokedAt.isNotNull()
            }.firstOrNull()

            if (existingRevoked != null) {
                // Verify the invite was created AFTER the revocation (explicit re-invitation)
                val revokedAt = existingRevoked[BucketAccess.revokedAt]!!
                val inviteCreatedAt = invite[InviteTokens.createdAt]
                if (inviteCreatedAt <= revokedAt) {
                    throw ApiException(
                        403,
                        "DEVICE_REVOKED",
                        "Device was removed from this bucket and cannot rejoin with this invite"
                    )
                }
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

            // SEC4-S-19: Enforce maximum devices per bucket limit
            val activeDeviceCount = BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and BucketAccess.revokedAt.isNull()
            }.count()

            if (activeDeviceCount >= maxDevicesPerBucket) {
                throw ApiException(429, "RATE_LIMITED", "Maximum number of devices ($maxDevicesPerBucket) per bucket reached")
            }

            // SEC2-S-01: Atomic invite consumption -- use UPDATE with WHERE usedAt IS NULL
            // to prevent race conditions. Only the first concurrent request will match.
            val updatedRows = InviteTokens.update({
                (InviteTokens.tokenHash eq tokenHash) and
                    InviteTokens.usedAt.isNull() and
                    (InviteTokens.expiresAt greater now)
            }) {
                it[usedAt] = now
            }

            if (updatedRows == 0) {
                // SEC6-S-01: Another concurrent request consumed the invite first
                logger.warn("Invite token race: concurrent consumption detected for bucket={}", bucketId)
                throw inviteInvalidException()
            }

            // Re-activate previously revoked access or grant new
            if (existingRevoked != null) {
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
     *
     * SEC4-S-07: When a device is revoked from its LAST bucket, all its sessions
     * are invalidated to prevent continued access with stale tokens.
     */
    suspend fun selfRevoke(bucketId: String, deviceId: String) {
        val remainingBucketCount = dbQuery {
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

            // Check remaining active bucket memberships for this device
            BucketAccess.selectAll().where {
                (BucketAccess.deviceId eq deviceId) and BucketAccess.revokedAt.isNull()
            }.count()
        }

        // SEC3-S-05: Terminate WebSocket connections for the revoked device
        wsManager?.disconnectDevice(bucketId, deviceId)

        // SEC4-S-07: If device has no remaining bucket memberships, invalidate all sessions
        if (remainingBucketCount == 0L) {
            logger.info("Device {} revoked from last bucket, invalidating all sessions", deviceId)
            sessionUtil?.deleteSessionsByDevice(deviceId)
        }
    }

    /**
     * SEC5-S-08: Creator-driven device revocation. Only the bucket creator can remove
     * another device from the bucket. Removes bucket access and deletes wrapped keys
     * for the target device in this bucket's context.
     */
    suspend fun creatorRevoke(bucketId: String, callerDeviceId: String, targetDeviceId: String) {
        if (callerDeviceId == targetDeviceId) {
            throw ApiException(400, "INVALID_REQUEST", "Use DELETE /buckets/{id}/devices/me to revoke yourself")
        }

        val remainingBucketCount = dbQuery {
            val bucket = Buckets.selectAll().where { Buckets.id eq bucketId }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Bucket not found")

            if (bucket[Buckets.createdBy] != callerDeviceId) {
                throw ApiException(403, "NOT_BUCKET_CREATOR", "Only the bucket creator can revoke devices")
            }

            // Verify target device has active access
            val access = BucketAccess.selectAll().where {
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq targetDeviceId) and
                    BucketAccess.revokedAt.isNull()
            }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Device does not have active access to this bucket")

            // Revoke access
            BucketAccess.update({
                (BucketAccess.bucketId eq bucketId) and
                    (BucketAccess.deviceId eq targetDeviceId) and
                    BucketAccess.revokedAt.isNull()
            }) {
                it[revokedAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            // Delete wrapped keys for the target device
            WrappedKeys.deleteWhere { WrappedKeys.targetDevice eq targetDeviceId }

            // Check remaining active bucket memberships for the target device
            BucketAccess.selectAll().where {
                (BucketAccess.deviceId eq targetDeviceId) and BucketAccess.revokedAt.isNull()
            }.count()
        }

        // SEC3-S-05: Terminate WebSocket connections for the revoked device
        wsManager?.disconnectDevice(bucketId, targetDeviceId)

        // SEC4-S-07: If device has no remaining bucket memberships, invalidate all sessions
        if (remainingBucketCount == 0L) {
            logger.info("Device {} revoked by creator from last bucket, invalidating all sessions", targetDeviceId)
            sessionUtil?.deleteSessionsByDevice(targetDeviceId)
        }
    }
}
