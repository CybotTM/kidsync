package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import dev.kidsync.server.util.ValidationUtil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Represents a checkpoint that was created during an upload.
 */
data class CheckpointCreated(val startSequence: Long, val endSequence: Long)

/**
 * SEC2-S-05: DESIGN NOTE - Per-device hash chains.
 * Each device maintains its own independent hash chain that does not cross-reference
 * other devices' chains. This is by design: it allows devices to operate independently
 * and upload ops without coordinating hash state with other devices. The trade-off is
 * that independent forks are possible -- each device's chain can be verified in isolation,
 * but the server cannot detect if two devices diverge. Cross-device consistency is
 * ensured at the application layer via the encrypted CRDT payloads, not at the hash
 * chain layer.
 *
 * SEC2-S-14: DESIGN NOTE - Global sequence and checkpoint sparsity.
 * The global sequence number is shared across all devices in a bucket. Checkpoints
 * are created at fixed global sequence intervals (e.g., every 100 ops). For buckets
 * with sparse activity from many devices, checkpoints may contain ops from multiple
 * devices. This is by design: checkpoints provide an integrity anchor for the global
 * op stream, not per-device streams.
 */
// SEC5-S-14: Op table pruning is implemented via CheckpointAcknowledgments table and
// pruneAcknowledgedOps(). Ops covered by fully-acknowledged checkpoints (all active
// devices have acknowledged) are pruned, preserving the latest checkpoint's ops as
// a safety margin.

class SyncService(private val config: AppConfig) {

    private val logger = LoggerFactory.getLogger(SyncService::class.java)

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Upload a batch of encrypted ops to a bucket.
     * Validates hash chain integrity but does NOT inspect encrypted payload contents.
     * Returns the batch response and optionally a checkpoint if one was created.
     */
    suspend fun uploadOps(
        bucketId: String,
        deviceId: String,
        request: OpsBatchRequest,
    ): Pair<OpsBatchResponse, CheckpointCreated?> {
        if (request.ops.isEmpty()) {
            throw ApiException(400, "INVALID_REQUEST", "ops array must contain at least 1 entry")
        }
        if (request.ops.size > 100) {
            throw ApiException(400, "BATCH_TOO_LARGE", "ops array must contain at most 100 entries")
        }

        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            val now = LocalDateTime.now(ZoneOffset.UTC)
            val serverTimestamp = now.atOffset(ZoneOffset.UTC).format(isoFormatter)
            val acceptedOps = mutableListOf<AcceptedOp>()

            // Query the last op once before the loop, then track running hash in memory
            val lastExistingOp = Ops.selectAll()
                .where { (Ops.deviceId eq deviceId) and (Ops.bucketId eq bucketId) }
                .orderBy(Ops.sequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
            var runningHash: String? = lastExistingOp?.get(Ops.currentHash)

            for ((index, op) in request.ops.withIndex()) {
                // Verify the op's deviceId matches the authenticated device
                if (op.deviceId != deviceId) {
                    throw ApiException(403, "BUCKET_ACCESS_DENIED", "Op deviceId does not match authenticated device")
                }

                // Validate key epoch
                if (op.keyEpoch < 1) {
                    throw ApiException(400, "INVALID_REQUEST", "keyEpoch must be >= 1")
                }

                // Validate prevHash and currentHash are valid SHA-256 hex
                if (!ValidationUtil.isValidSha256Hex(op.prevHash)) {
                    throw ApiException(400, "INVALID_REQUEST", "prevHash must be a valid 64-character hex SHA-256 hash")
                }
                if (!ValidationUtil.isValidSha256Hex(op.currentHash)) {
                    throw ApiException(400, "INVALID_REQUEST", "currentHash must be a valid 64-character hex SHA-256 hash")
                }

                // Validate encryptedPayload is valid base64
                if (!ValidationUtil.isValidBase64(op.encryptedPayload)) {
                    throw ApiException(400, "INVALID_REQUEST", "encryptedPayload must be valid base64")
                }

                // SEC4-S-18: Decode payload once and reuse for both size check and hash verification
                val payloadBytes = java.util.Base64.getDecoder().decode(op.encryptedPayload)
                if (payloadBytes.size > config.maxPayloadSizeBytes) {
                    throw ApiException(413, "PAYLOAD_TOO_LARGE", "Encrypted payload exceeds size limit")
                }

                // Validate hash chain using in-memory running hash
                // SEC2-S-08: Error message does not expose server-side expected hash
                if (runningHash != null) {
                    if (op.prevHash != runningHash) {
                        throw ApiException(
                            409,
                            "HASH_CHAIN_BREAK",
                            "Hash chain mismatch: prevHash does not match expected value"
                        )
                    }
                } else {
                    // First op from this device in this bucket: prevHash should be all zeros
                    val sentinel = "0".repeat(64)
                    if (op.prevHash != sentinel) {
                        throw ApiException(
                            409,
                            "HASH_CHAIN_BREAK",
                            "First op from device must have prevHash of 64 zeros"
                        )
                    }
                }

                // SEC4-S-18: Pass pre-decoded bytes to avoid redundant base64 decode
                if (!HashUtil.verifyHashChain(op.prevHash, payloadBytes, op.currentHash)) {
                    throw ApiException(409, "HASH_MISMATCH", "currentHash does not match computed hash")
                }

                // Insert the op
                val seq = Ops.insert {
                    it[Ops.bucketId] = bucketId
                    it[Ops.deviceId] = op.deviceId
                    it[encryptedPayload] = op.encryptedPayload
                    it[prevHash] = op.prevHash
                    it[currentHash] = op.currentHash
                    it[keyEpoch] = op.keyEpoch
                    it[createdAt] = now
                } get Ops.sequence

                runningHash = op.currentHash
                acceptedOps.add(AcceptedOp(
                    index = index,
                    globalSequence = seq,
                    serverTimestamp = serverTimestamp,
                ))
            }

            // Check if we crossed a checkpoint boundary
            val checkpoint = maybeCreateCheckpoint(bucketId)

            Pair(
                OpsBatchResponse(
                    accepted = acceptedOps,
                ),
                checkpoint,
            )
        }
    }

    /**
     * Pull operations since a given sequence for a bucket.
     * Returns a PullOpsResponse with ops, hasMore flag, and latestSequence.
     */
    suspend fun pullOps(bucketId: String, deviceId: String, since: Long, limit: Int): PullOpsResponse {
        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            val effectiveLimit = limit.coerceIn(1, 1000)

            // Query for effectiveLimit + 1 to accurately determine hasMore
            val ops = Ops.selectAll()
                .where { (Ops.bucketId eq bucketId) and (Ops.sequence greater since) }
                .orderBy(Ops.sequence, SortOrder.ASC)
                .limit(effectiveLimit + 1)
                .map { row ->
                    OpResponse(
                        globalSequence = row[Ops.sequence],
                        deviceId = row[Ops.deviceId],
                        encryptedPayload = row[Ops.encryptedPayload],
                        prevHash = row[Ops.prevHash],
                        currentHash = row[Ops.currentHash],
                        keyEpoch = row[Ops.keyEpoch],
                        serverTimestamp = row[Ops.createdAt].atOffset(ZoneOffset.UTC)
                            .format(isoFormatter),
                    )
                }

            val hasMore = ops.size > effectiveLimit
            val resultOps = ops.take(effectiveLimit)

            // Get the latest sequence for this bucket
            val latestSequence = Ops.selectAll()
                .where { Ops.bucketId eq bucketId }
                .orderBy(Ops.sequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(Ops.sequence) ?: 0L

            PullOpsResponse(
                ops = resultOps,
                hasMore = hasMore,
                latestSequence = latestSequence,
            )
        }
    }

    /**
     * Get the latest checkpoint for a bucket.
     */
    suspend fun getCheckpoint(bucketId: String, deviceId: String): CheckpointResponse? {
        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            val row = Checkpoints.selectAll()
                .where { Checkpoints.bucketId eq bucketId }
                .orderBy(Checkpoints.endSequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull() ?: return@dbQuery null

            val latestSequence = Ops.selectAll()
                .where { Ops.bucketId eq bucketId }
                .orderBy(Ops.sequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(Ops.sequence) ?: row[Checkpoints.endSequence]

            val nextCheckpointAt = row[Checkpoints.endSequence] + config.checkpointInterval

            CheckpointResponse(
                checkpoint = CheckpointData(
                    startSequence = row[Checkpoints.startSequence],
                    endSequence = row[Checkpoints.endSequence],
                    hash = row[Checkpoints.hash],
                    timestamp = row[Checkpoints.createdAt].atOffset(ZoneOffset.UTC)
                        .format(isoFormatter),
                    opCount = row[Checkpoints.opCount],
                ),
                latestSequence = latestSequence,
                nextCheckpointAt = nextCheckpointAt,
            )
        }
    }

    /**
     * Get the latest sequence number for a bucket.
     */
    suspend fun getLatestSequence(bucketId: String): Long {
        return dbQuery {
            Ops.selectAll()
                .where { Ops.bucketId eq bucketId }
                .orderBy(Ops.sequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(Ops.sequence) ?: 0L
        }
    }

    /**
     * Check if a checkpoint boundary was crossed and create checkpoint if so.
     * Returns checkpoint info if one was created, null otherwise.
     */
    private fun maybeCreateCheckpoint(bucketId: String): CheckpointCreated? {
        val latestCheckpoint = Checkpoints.selectAll()
            .where { Checkpoints.bucketId eq bucketId }
            .orderBy(Checkpoints.endSequence, SortOrder.DESC)
            .limit(1)
            .firstOrNull()

        val lastCheckpointEnd = latestCheckpoint?.get(Checkpoints.endSequence) ?: 0L
        val nextCheckpointEnd = lastCheckpointEnd + config.checkpointInterval

        val latestSeq = Ops.selectAll()
            .where { Ops.bucketId eq bucketId }
            .orderBy(Ops.sequence, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(Ops.sequence) ?: return null

        if (latestSeq >= nextCheckpointEnd) {
            val startSeq = lastCheckpointEnd + 1
            val endSeq = nextCheckpointEnd

            val payloads = Ops.selectAll()
                .where {
                    (Ops.bucketId eq bucketId) and
                        (Ops.sequence greaterEq startSeq) and
                        (Ops.sequence lessEq endSeq)
                }
                .orderBy(Ops.sequence, SortOrder.ASC)
                .map { it[Ops.encryptedPayload] }

            if (payloads.size == config.checkpointInterval) {
                val hash = HashUtil.computeCheckpointHash(payloads)

                Checkpoints.insert {
                    it[Checkpoints.bucketId] = bucketId
                    it[startSequence] = startSeq
                    it[endSequence] = endSeq
                    it[Checkpoints.hash] = hash
                    it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
                    it[opCount] = config.checkpointInterval
                }

                return CheckpointCreated(startSequence = startSeq, endSequence = endSeq)
            }
        }
        return null
    }

    /**
     * SEC5-S-14: Record a device's acknowledgment of a checkpoint.
     * Upserts to handle idempotent re-acknowledgment.
     */
    suspend fun acknowledgeCheckpoint(bucketId: String, deviceId: String, checkpointId: Int) {
        dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            // Verify checkpoint exists and belongs to this bucket
            val checkpoint = Checkpoints.selectAll()
                .where { (Checkpoints.id eq checkpointId) and (Checkpoints.bucketId eq bucketId) }
                .firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Checkpoint not found in this bucket")

            // Upsert: insert or ignore if already acknowledged
            val existing = CheckpointAcknowledgments.selectAll()
                .where {
                    (CheckpointAcknowledgments.checkpointId eq checkpointId) and
                        (CheckpointAcknowledgments.deviceId eq deviceId)
                }
                .firstOrNull()

            if (existing == null) {
                CheckpointAcknowledgments.insert {
                    it[CheckpointAcknowledgments.checkpointId] = checkpointId
                    it[CheckpointAcknowledgments.deviceId] = deviceId
                    it[acknowledgedAt] = java.time.Instant.now().epochSecond
                }
            }
        }
    }

    /**
     * SEC5-S-14: Prune ops covered by fully-acknowledged checkpoints.
     *
     * A checkpoint is "fully acknowledged" when ALL active (non-revoked) devices
     * in the bucket have acknowledged it. For safety, the latest fully-acknowledged
     * checkpoint's ops are preserved -- only ops covered by older checkpoints are pruned.
     *
     * Returns the number of ops pruned.
     */
    suspend fun pruneAcknowledgedOps(bucketId: String): Long {
        return dbQuery {
            // Get all active (non-revoked) devices for this bucket
            val activeDeviceIds = BucketAccess.selectAll()
                .where {
                    (BucketAccess.bucketId eq bucketId) and BucketAccess.revokedAt.isNull()
                }
                .map { it[BucketAccess.deviceId] }
                .toSet()

            if (activeDeviceIds.isEmpty()) return@dbQuery 0L

            // Get all checkpoints for this bucket ordered by endSequence
            val checkpoints = Checkpoints.selectAll()
                .where { Checkpoints.bucketId eq bucketId }
                .orderBy(Checkpoints.endSequence, SortOrder.ASC)
                .toList()

            if (checkpoints.isEmpty()) return@dbQuery 0L

            // Find checkpoints where ALL active devices have acknowledged
            val fullyAcknowledged = checkpoints.filter { cp ->
                val cpId = cp[Checkpoints.id]
                val acknowledgedDevices = CheckpointAcknowledgments.selectAll()
                    .where { CheckpointAcknowledgments.checkpointId eq cpId }
                    .map { it[CheckpointAcknowledgments.deviceId] }
                    .toSet()
                activeDeviceIds.all { it in acknowledgedDevices }
            }

            if (fullyAcknowledged.isEmpty()) return@dbQuery 0L

            // Keep the latest fully-acknowledged checkpoint's ops as safety margin.
            // Only prune ops covered by older fully-acknowledged checkpoints.
            val prunableCps = fullyAcknowledged.dropLast(1)
            if (prunableCps.isEmpty()) return@dbQuery 0L

            val maxPrunableSeq = prunableCps.last()[Checkpoints.endSequence]

            // Delete ops up to and including maxPrunableSeq
            val deleted = Ops.deleteWhere {
                (Ops.bucketId eq bucketId) and (Ops.sequence lessEq maxPrunableSeq)
            }.toLong()

            if (deleted > 0) {
                logger.info("Pruned {} ops from bucket {} (up to seq {})", deleted, bucketId, maxPrunableSeq)
            }

            deleted
        }
    }
}
