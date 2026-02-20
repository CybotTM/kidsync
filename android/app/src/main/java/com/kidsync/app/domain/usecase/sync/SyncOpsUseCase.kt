package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.*
import com.kidsync.app.domain.repository.SyncRepository
import com.kidsync.app.domain.usecase.custody.ConflictResolver
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Core sync use case implementing the full operation pipeline:
 * 1. Pull new ops from server
 * 2. Decrypt and verify hash chains
 * 3. Apply ops with conflict resolution
 * 4. Push local pending ops
 */
class SyncOpsUseCase @Inject constructor(
    private val syncRepository: SyncRepository,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val hashChainVerifier: HashChainVerifier,
    private val conflictResolver: ConflictResolver,
    private val opApplier: OpApplier
) {
    suspend operator fun invoke(familyId: UUID): Result<SyncResult> {
        return try {
            val syncState = syncRepository.getSyncState(familyId)
            val lastSeq = syncState?.lastGlobalSequence ?: 0L

            // 1. Pull new ops
            val pullResult = syncRepository.pullOps(familyId, afterSequence = lastSeq)
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
                val dek = keyManager.getDek(familyId, op.keyEpoch)
                    ?: return Result.failure(IllegalStateException("Missing DEK for epoch ${op.keyEpoch}"))

                val decryptedPayload = cryptoManager.decryptPayload(
                    encryptedPayload = op.encryptedPayload,
                    dek = dek,
                    aad = op.deviceId.toString()
                )

                val applyResult = opApplier.apply(op, decryptedPayload)
                if (applyResult.conflictResolved) conflictsResolved++
                appliedCount++
            }

            // 4. Update sync state
            if (newOps.isNotEmpty()) {
                val maxSeq = newOps.maxOf { it.globalSequence }
                syncRepository.updateSyncState(
                    SyncState(
                        familyId = familyId,
                        lastGlobalSequence = maxSeq,
                        lastSyncTimestamp = Instant.now()
                    )
                )
            }

            // 5. Push pending local ops
            val pendingOps = opApplier.getPendingOps(familyId)
            if (pendingOps.isNotEmpty()) {
                val pushResult = syncRepository.pushOps(familyId, pendingOps)
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
