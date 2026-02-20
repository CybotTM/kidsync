package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import dev.kidsync.server.util.ValidationUtil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

class SyncService(private val config: AppConfig) {

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Upload operations. Assigns global sequence numbers, validates hash chains.
     * Returns the assigned sequences or throws ApiException.
     */
    suspend fun uploadOps(
        userId: String,
        familyId: String,
        request: UploadOpsRequest,
    ): UploadOpsResponse {
        if (request.ops.isEmpty()) {
            throw ApiException(400, "INVALID_REQUEST", "ops array must contain at least 1 entry")
        }
        if (request.ops.size > 100) {
            throw ApiException(400, "BATCH_TOO_LARGE", "ops array must contain at most 100 entries")
        }

        return dbQuery {
            val now = Instant.now()
            val serverTimestamp = isoFormatter.format(now)
            val accepted = mutableListOf<AcceptedOp>()

            for (op in request.ops) {
                // Validate device belongs to user and family
                val device = Devices.selectAll().where {
                    (Devices.id eq op.deviceId) and (Devices.userId eq userId)
                }.firstOrNull()
                    ?: throw ApiException(400, "INVALID_REQUEST", "Device ${op.deviceId} not found or not owned by user")

                if (device[Devices.revokedAt] != null) {
                    throw ApiException(403, "DEVICE_REVOKED", "Device ${op.deviceId} has been revoked")
                }

                // Validate entity type if provided
                if (op.entityType != null && op.entityType !in ValidationUtil.VALID_ENTITY_TYPES) {
                    throw ApiException(400, "INVALID_ENTITY_TYPE", "Unknown entity type: ${op.entityType}")
                }

                // Validate operation if provided
                if (op.operation != null && op.operation !in ValidationUtil.VALID_OPERATIONS) {
                    throw ApiException(400, "INVALID_REQUEST", "Unknown operation: ${op.operation}")
                }

                // Validate hash chain: check devicePrevHash matches the last known hash for this device
                val lastOp = OpLog.selectAll()
                    .where { (OpLog.deviceId eq op.deviceId) and (OpLog.familyId eq familyId) }
                    .orderBy(OpLog.globalSequence, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()

                if (lastOp != null) {
                    val expectedPrevHash = lastOp[OpLog.currentHash] ?: lastOp[OpLog.devicePrevHash]
                    if (op.devicePrevHash != expectedPrevHash) {
                        throw ApiException(
                            409,
                            "HASH_CHAIN_BREAK",
                            "Expected devicePrevHash '$expectedPrevHash' but got '${op.devicePrevHash}'"
                        )
                    }

                    // Validate device sequence continuity if present
                    if (op.deviceSequence != null && lastOp[OpLog.deviceSequence] != null) {
                        val expectedSeq = lastOp[OpLog.deviceSequence]!! + 1
                        if (op.deviceSequence != expectedSeq) {
                            if (op.deviceSequence <= lastOp[OpLog.deviceSequence]!!) {
                                throw ApiException(409, "SEQUENCE_DUPLICATE", "deviceSequence ${op.deviceSequence} already used")
                            }
                            throw ApiException(400, "SEQUENCE_GAP", "Expected deviceSequence $expectedSeq but got ${op.deviceSequence}")
                        }
                    }
                } else {
                    // First op from this device: devicePrevHash should be all zeros
                    val sentinel = "0".repeat(64)
                    if (op.devicePrevHash != sentinel && op.deviceSequence == 1) {
                        throw ApiException(
                            409,
                            "HASH_CHAIN_BREAK",
                            "First op from device must have devicePrevHash of 64 zeros"
                        )
                    }
                }

                // Validate hash correctness: currentHash is now required
                if (!HashUtil.verifyHashChain(op.devicePrevHash, op.encryptedPayload, op.currentHash)) {
                    throw ApiException(409, "HASH_MISMATCH", "currentHash does not match computed hash")
                }

                // Validate ScheduleOverride state transitions
                if (op.entityType == "ScheduleOverride" && op.operation == "UPDATE" && op.transitionTo != null && op.entityId != null) {
                    val validationResult = validateOverrideTransition(
                        entityId = op.entityId,
                        familyId = familyId,
                        transitionTo = op.transitionTo,
                        actingUserId = userId,
                    )
                    if (validationResult != null) {
                        throw validationResult
                    }
                }

                // Insert the op
                val globalSeq = OpLog.insert {
                    it[OpLog.familyId] = familyId
                    it[deviceId] = op.deviceId
                    it[deviceSequence] = op.deviceSequence
                    it[entityType] = op.entityType
                    it[entityId] = op.entityId
                    it[operation] = op.operation
                    it[devicePrevHash] = op.devicePrevHash
                    it[currentHash] = op.currentHash
                    it[encryptedPayload] = op.encryptedPayload
                    it[keyEpoch] = op.keyEpoch
                    it[clientTimestamp] = op.clientTimestamp
                    it[OpLog.serverTimestamp] = serverTimestamp
                    it[protocolVersion] = op.protocolVersion
                    it[transitionTo] = op.transitionTo
                } get OpLog.globalSequence

                // Update override state if this is a ScheduleOverride op
                if (op.entityType == "ScheduleOverride" && op.entityId != null) {
                    if (op.operation == "CREATE") {
                        OverrideStates.insert {
                            it[OverrideStates.entityId] = op.entityId
                            it[OverrideStates.familyId] = familyId
                            it[currentState] = "PROPOSED"
                            it[proposerUserId] = userId
                        }
                    } else if (op.operation == "UPDATE" && op.transitionTo != null) {
                        OverrideStates.update({ OverrideStates.entityId eq op.entityId }) {
                            it[currentState] = op.transitionTo
                        }
                    }
                }

                accepted.add(
                    AcceptedOp(
                        localId = op.localId,
                        deviceSequence = op.deviceSequence,
                        globalSequence = globalSeq,
                        serverTimestamp = serverTimestamp,
                    )
                )
            }

            // Check if we crossed a checkpoint boundary
            maybeCreateCheckpoint(familyId)

            UploadOpsResponse(accepted = accepted)
        }
    }

    /**
     * Pull operations since a given sequence.
     */
    suspend fun pullOps(familyId: String, since: Long, limit: Int): PullOpsResponse {
        return dbQuery {
            val effectiveLimit = limit.coerceIn(1, 1000)

            val ops = OpLog.selectAll()
                .where { (OpLog.familyId eq familyId) and (OpLog.globalSequence greater since) }
                .orderBy(OpLog.globalSequence, SortOrder.ASC)
                .limit(effectiveLimit + 1) // Fetch one extra to check hasMore
                .toList()

            val hasMore = ops.size > effectiveLimit
            val resultOps = ops.take(effectiveLimit)

            val latestSeq = OpLog.selectAll()
                .where { OpLog.familyId eq familyId }
                .orderBy(OpLog.globalSequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(OpLog.globalSequence) ?: 0L

            PullOpsResponse(
                ops = resultOps.map { row ->
                    OpOutput(
                        deviceId = row[OpLog.deviceId],
                        deviceSequence = row[OpLog.deviceSequence],
                        entityType = row[OpLog.entityType],
                        entityId = row[OpLog.entityId],
                        operation = row[OpLog.operation],
                        keyEpoch = row[OpLog.keyEpoch],
                        encryptedPayload = row[OpLog.encryptedPayload],
                        devicePrevHash = row[OpLog.devicePrevHash],
                        currentHash = row[OpLog.currentHash] ?: "",
                        clientTimestamp = row[OpLog.clientTimestamp],
                        protocolVersion = row[OpLog.protocolVersion],
                        transitionTo = row[OpLog.transitionTo],
                        globalSequence = row[OpLog.globalSequence],
                        serverTimestamp = row[OpLog.serverTimestamp],
                    )
                },
                hasMore = hasMore,
                latestSequence = latestSeq,
            )
        }
    }

    /**
     * Get checkpoint for a family.
     */
    suspend fun getCheckpoint(familyId: String, atSequence: Long?): CheckpointResponse {
        return dbQuery {
            val latestSeq = OpLog.selectAll()
                .where { OpLog.familyId eq familyId }
                .orderBy(OpLog.globalSequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(OpLog.globalSequence) ?: 0L

            val checkpoint = if (atSequence != null) {
                Checkpoints.selectAll().where {
                    (Checkpoints.familyId eq familyId) and
                        (Checkpoints.startSequence lessEq atSequence) and
                        (Checkpoints.endSequence greaterEq atSequence)
                }.firstOrNull()
            } else {
                Checkpoints.selectAll()
                    .where { Checkpoints.familyId eq familyId }
                    .orderBy(Checkpoints.endSequence, SortOrder.DESC)
                    .limit(1)
                    .firstOrNull()
            }

            val nextCheckpointAt = if (checkpoint != null) {
                checkpoint[Checkpoints.endSequence] + config.checkpointInterval.toLong()
            } else {
                config.checkpointInterval.toLong()
            }

            CheckpointResponse(
                checkpoint = checkpoint?.let {
                    CheckpointDto(
                        startSequence = it[Checkpoints.startSequence],
                        endSequence = it[Checkpoints.endSequence],
                        hash = it[Checkpoints.hash],
                        timestamp = it[Checkpoints.timestamp].atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
                        opCount = it[Checkpoints.opCount],
                    )
                },
                latestSequence = latestSeq,
                nextCheckpointAt = nextCheckpointAt,
            )
        }
    }

    /**
     * Get latest sequence number for a family.
     */
    suspend fun getLatestSequence(familyId: String): Long {
        return dbQuery {
            OpLog.selectAll()
                .where { OpLog.familyId eq familyId }
                .orderBy(OpLog.globalSequence, SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(OpLog.globalSequence) ?: 0L
        }
    }

    /**
     * Validate override state transition. Returns an ApiException if invalid, null if valid.
     */
    private fun validateOverrideTransition(
        entityId: String,
        familyId: String,
        transitionTo: String,
        actingUserId: String,
    ): ApiException? {
        if (transitionTo !in ValidationUtil.VALID_TRANSITION_STATES) {
            return ApiException(400, "INVALID_REQUEST", "Invalid transitionTo value: $transitionTo")
        }

        val state = OverrideStates.selectAll().where {
            (OverrideStates.entityId eq entityId) and (OverrideStates.familyId eq familyId)
        }.firstOrNull()
            ?: return ApiException(404, "NOT_FOUND", "ScheduleOverride entity $entityId not found")

        val currentState = state[OverrideStates.currentState]
        val proposerUserId = state[OverrideStates.proposerUserId]

        // Terminal states cannot transition
        val terminalStates = setOf("DECLINED", "CANCELLED", "SUPERSEDED", "EXPIRED")
        if (currentState in terminalStates) {
            return ApiException(409, "INVALID_STATE_TRANSITION", "Cannot transition from terminal state $currentState")
        }

        // Validate allowed transitions
        val isProposer = actingUserId == proposerUserId

        return when (currentState) {
            "PROPOSED" -> when (transitionTo) {
                "APPROVED", "DECLINED" -> {
                    if (isProposer) {
                        ApiException(403, "TRANSITION_NOT_AUTHORIZED", "Proposer cannot approve/decline their own override")
                    } else null
                }
                "CANCELLED" -> {
                    if (!isProposer) {
                        ApiException(403, "TRANSITION_NOT_AUTHORIZED", "Only the proposer can cancel an override")
                    } else null
                }
                else -> ApiException(409, "INVALID_STATE_TRANSITION", "Cannot transition from PROPOSED to $transitionTo")
            }
            "APPROVED" -> when (transitionTo) {
                "SUPERSEDED", "EXPIRED" -> null // System transitions, always allowed
                else -> ApiException(409, "INVALID_STATE_TRANSITION", "Cannot transition from APPROVED to $transitionTo")
            }
            else -> ApiException(409, "INVALID_STATE_TRANSITION", "Unknown current state: $currentState")
        }
    }

    /**
     * Check if a checkpoint boundary was crossed and create checkpoint if so.
     */
    private fun maybeCreateCheckpoint(familyId: String) {
        val latestCheckpoint = Checkpoints.selectAll()
            .where { Checkpoints.familyId eq familyId }
            .orderBy(Checkpoints.endSequence, SortOrder.DESC)
            .limit(1)
            .firstOrNull()

        val lastCheckpointEnd = latestCheckpoint?.get(Checkpoints.endSequence) ?: 0L
        val nextCheckpointEnd = lastCheckpointEnd + config.checkpointInterval

        val latestSeq = OpLog.selectAll()
            .where { OpLog.familyId eq familyId }
            .orderBy(OpLog.globalSequence, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(OpLog.globalSequence) ?: return

        if (latestSeq >= nextCheckpointEnd) {
            val startSeq = lastCheckpointEnd + 1
            val endSeq = nextCheckpointEnd

            val payloads = OpLog.selectAll()
                .where {
                    (OpLog.familyId eq familyId) and
                        (OpLog.globalSequence greaterEq startSeq) and
                        (OpLog.globalSequence lessEq endSeq)
                }
                .orderBy(OpLog.globalSequence, SortOrder.ASC)
                .map { it[OpLog.encryptedPayload] }

            if (payloads.size == config.checkpointInterval) {
                val hash = HashUtil.computeCheckpointHash(payloads)

                Checkpoints.insert {
                    it[Checkpoints.familyId] = familyId
                    it[startSequence] = startSeq
                    it[endSequence] = endSeq
                    it[Checkpoints.hash] = hash
                    it[timestamp] = LocalDateTime.now(ZoneOffset.UTC)
                    it[opCount] = config.checkpointInterval
                }
            }
        }
    }
}
