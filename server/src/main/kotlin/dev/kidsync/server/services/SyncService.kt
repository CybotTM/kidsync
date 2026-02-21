package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import dev.kidsync.server.util.ValidationUtil
import org.jetbrains.exposed.sql.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Represents a checkpoint that was created during an upload.
 */
data class CheckpointCreated(val startSequence: Long, val endSequence: Long)

class SyncService(private val config: AppConfig) {

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
            var latestSequence = 0L
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
                    throw ApiException(403, "FORBIDDEN", "Op deviceId does not match authenticated device")
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

                // Enforce per-op payload size limit
                if (op.encryptedPayload.length > config.maxPayloadSizeBytes) {
                    throw ApiException(413, "PAYLOAD_TOO_LARGE", "Encrypted payload exceeds size limit")
                }

                // Validate hash chain using in-memory running hash
                if (runningHash != null) {
                    if (op.prevHash != runningHash) {
                        throw ApiException(
                            409,
                            "HASH_CHAIN_BREAK",
                            "Expected prevHash '$runningHash' but got '${op.prevHash}'"
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

                // Validate hash correctness
                if (!HashUtil.verifyHashChain(op.prevHash, op.encryptedPayload, op.currentHash)) {
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

                latestSequence = seq
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
                    latestSequence = latestSequence,
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

            val ops = Ops.selectAll()
                .where { (Ops.bucketId eq bucketId) and (Ops.sequence greater since) }
                .orderBy(Ops.sequence, SortOrder.ASC)
                .limit(effectiveLimit)
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

            // Determine hasMore: if we got exactly effectiveLimit results, there might be more
            val hasMore = ops.size == effectiveLimit

            // Get the latest sequence for this bucket
            val latestSequence = Ops.selectAll()
                .where { Ops.bucketId eq bucketId }
                .orderBy(Ops.sequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(Ops.sequence) ?: 0L

            PullOpsResponse(
                ops = ops,
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
}
