package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.CryptoManager.Companion.buildPayloadAad
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.*
import com.kidsync.app.domain.repository.SyncRepository
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject

/**
 * Core sync use case implementing the full operation pipeline:
 * 1. Pull new ops from server
 * 2. Decrypt and verify hash chains
 * 3. Apply ops with conflict resolution (metadata extracted from decrypted payload)
 * 4. Push local pending ops
 */
class SyncOpsUseCase @Inject constructor(
    private val syncRepository: SyncRepository,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val hashChainVerifier: HashChainVerifier,
    private val conflictResolver: ConflictResolver,
    private val opApplier: OpApplier,
    private val json: Json
) {
    suspend operator fun invoke(bucketId: String): Result<SyncResult> {
        return try {
            val syncState = syncRepository.getSyncState(bucketId)
            val lastSeq = syncState?.lastGlobalSequence ?: 0L

            // 1. Pull new ops
            val pullResult = syncRepository.pullOps(bucketId, afterSequence = lastSeq)
            if (pullResult.isFailure) return Result.failure(pullResult.exceptionOrNull()!!)

            val newOps = pullResult.getOrThrow()

            // 2. Verify hash chains per device
            val chainResult = hashChainVerifier.verifyChains(newOps)
            if (chainResult.isFailure) {
                return Result.failure(chainResult.exceptionOrNull()!!)
            }

            // 3. Decrypt payloads and apply in globalSequence order
            var appliedCount = 0
            var conflictsResolved = 0

            for (op in newOps.sortedBy { it.globalSequence }) {
                val dek = keyManager.getDek(bucketId, op.keyEpoch)
                    ?: return Result.failure(IllegalStateException("Missing DEK for epoch ${op.keyEpoch}"))

                // Build AAD from the envelope fields
                val aad = buildPayloadAad(
                    bucketId = bucketId,
                    deviceId = op.deviceId
                )
                val decryptedJson = cryptoManager.decryptPayload(
                    encryptedPayload = op.encryptedPayload,
                    dek = dek,
                    aad = aad
                )

                // Parse the decrypted payload to extract metadata
                val decryptedPayload = json.decodeFromString<DecryptedPayload>(decryptedJson)

                val applyResult = opApplier.apply(op, decryptedPayload)
                if (applyResult.conflictResolved) conflictsResolved++
                appliedCount++
            }

            // 4. Update sync state
            if (newOps.isNotEmpty()) {
                val maxSeq = newOps.maxOf { it.globalSequence }
                syncRepository.updateSyncState(
                    SyncState(
                        bucketId = bucketId,
                        lastGlobalSequence = maxSeq,
                        lastSyncTimestamp = Instant.now()
                    )
                )
            }

            // 5. Push pending local ops
            val pendingOps = opApplier.getPendingOps(bucketId)
            if (pendingOps.isNotEmpty()) {
                val pushResult = syncRepository.pushOps(bucketId, pendingOps)
                if (pushResult.isFailure) {
                    return Result.failure(pushResult.exceptionOrNull()!!)
                }
            }

            Result.success(
                SyncResult(
                    pulled = newOps.size,
                    applied = appliedCount,
                    pushed = pendingOps.size,
                    conflictsResolved = conflictsResolved
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

data class SyncResult(
    val pulled: Int,
    val applied: Int,
    val pushed: Int,
    val conflictsResolved: Int
)
