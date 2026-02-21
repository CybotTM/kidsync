package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import org.jetbrains.exposed.sql.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class SyncService(private val config: AppConfig) {

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Upload a batch of encrypted ops to a bucket.
     * Validates hash chain integrity but does NOT inspect encrypted payload contents.
     */
    suspend fun uploadOps(
        bucketId: String,
        deviceId: String,
        request: OpsBatchRequest,
    ): OpsBatchResponse {
        if (request.ops.isEmpty()) {
            throw ApiException(400, "INVALID_REQUEST", "ops array must contain at least 1 entry")
        }
        if (request.ops.size > 100) {
            throw ApiException(400, "BATCH_TOO_LARGE", "ops array must contain at most 100 entries")
        }

        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            val now = LocalDateTime.now(ZoneOffset.UTC)
            var latestSequence = 0L

            for (op in request.ops) {
                // Verify the op's deviceId matches the authenticated device
                if (op.deviceId != deviceId) {
                    throw ApiException(403, "FORBIDDEN", "Op deviceId does not match authenticated device")
                }

                // Enforce per-op payload size limit
                if (op.encryptedPayload.length > config.maxPayloadSizeBytes) {
                    throw ApiException(413, "PAYLOAD_TOO_LARGE", "Encrypted payload exceeds size limit")
                }

                // Validate hash chain: check prevHash matches the last known hash for this device in this bucket
                val lastOp = Ops.selectAll()
                    .where { (Ops.deviceId eq op.deviceId) and (Ops.bucketId eq bucketId) }
                    .orderBy(Ops.sequence, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()

                if (lastOp != null) {
                    val expectedPrevHash = lastOp[Ops.currentHash]
                    if (op.prevHash != expectedPrevHash) {
                        throw ApiException(
                            409,
                            "HASH_CHAIN_BREAK",
                            "Expected prevHash '$expectedPrevHash' but got '${op.prevHash}'"
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
            }

            // Check if we crossed a checkpoint boundary
            maybeCreateCheckpoint(bucketId)

            OpsBatchResponse(
                accepted = request.ops.size,
                latestSequence = latestSequence,
            )
        }
    }

    /**
     * Pull operations since a given sequence for a bucket.
     */
    suspend fun pullOps(bucketId: String, deviceId: String, since: Long, limit: Int): List<OpResponse> {
        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            val effectiveLimit = limit.coerceIn(1, 1000)

            Ops.selectAll()
                .where { (Ops.bucketId eq bucketId) and (Ops.sequence greater since) }
                .orderBy(Ops.sequence, SortOrder.ASC)
                .limit(effectiveLimit)
                .map { row ->
                    OpResponse(
                        sequence = row[Ops.sequence],
                        bucketId = row[Ops.bucketId],
                        deviceId = row[Ops.deviceId],
                        encryptedPayload = row[Ops.encryptedPayload],
                        prevHash = row[Ops.prevHash],
                        currentHash = row[Ops.currentHash],
                        keyEpoch = row[Ops.keyEpoch],
                        createdAt = row[Ops.createdAt].atOffset(ZoneOffset.UTC)
                            .format(isoFormatter),
                    )
                }
        }
    }

    /**
     * Get the latest checkpoint for a bucket.
     */
    suspend fun getCheckpoint(bucketId: String, deviceId: String): CheckpointResponse? {
        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            Checkpoints.selectAll()
                .where { Checkpoints.bucketId eq bucketId }
                .orderBy(Checkpoints.endSequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    CheckpointResponse(
                        startSequence = row[Checkpoints.startSequence],
                        endSequence = row[Checkpoints.endSequence],
                        hash = row[Checkpoints.hash],
                        opCount = row[Checkpoints.opCount],
                    )
                }
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
     */
    private fun maybeCreateCheckpoint(bucketId: String) {
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
            ?.get(Ops.sequence) ?: return

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
            }
        }
    }
}
