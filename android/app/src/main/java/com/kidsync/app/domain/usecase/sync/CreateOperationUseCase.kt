package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Creates a new operation and adds it to the local oplog for sync.
 *
 * Pipeline: business object -> OperationPayload -> canonical JSON -> gzip -> AES-256-GCM -> OpLogEntry
 */
class CreateOperationUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val canonicalJsonSerializer: CanonicalJsonSerializer,
    private val hashChainVerifier: HashChainVerifier,
    private val opLogDao: OpLogDao
) {
    suspend operator fun invoke(
        familyId: UUID,
        deviceId: UUID,
        entityType: EntityType,
        entityId: UUID,
        operationType: OperationType,
        payloadMap: Map<String, Any?>,
        transitionTo: String? = null
    ): Result<OpLogEntryEntity> {
        return try {
            // 1. Serialize to canonical JSON (sorted keys, compact, no nulls)
            val canonicalJson = canonicalJsonSerializer.serialize(payloadMap)

            // 2. Get current DEK epoch
            val currentEpoch = keyManager.getCurrentEpoch(familyId)
            val dek = keyManager.getDek(familyId, currentEpoch)
                ?: return Result.failure(IllegalStateException("No DEK available for epoch $currentEpoch"))

            // 3. Encrypt: canonical JSON -> gzip -> AES-256-GCM
            val encryptedPayload = cryptoManager.encryptPayload(
                plaintext = canonicalJson,
                dek = dek,
                aad = deviceId.toString()
            )

            // 4. Compute hash chain
            val lastOp = opLogDao.getLastOpForDevice(deviceId)
            val devicePrevHash = lastOp?.currentHash ?: HashChainVerifier.GENESIS_HASH
            val currentHash = hashChainVerifier.computeHash(devicePrevHash, encryptedPayload)

            // 5. Get next device sequence
            val deviceSequence = (lastOp?.deviceSequence ?: 0L) + 1

            // 6. Create OpLogEntry
            val entry = OpLogEntryEntity(
                globalSequence = 0, // assigned by server
                familyId = familyId,
                deviceId = deviceId,
                deviceSequence = deviceSequence,
                entityType = entityType.name,
                entityId = entityId,
                operation = operationType.name,
                keyEpoch = currentEpoch,
                encryptedPayload = encryptedPayload,
                devicePrevHash = devicePrevHash,
                currentHash = currentHash,
                clientTimestamp = Instant.now().toString(),
                serverTimestamp = null,
                transitionTo = transitionTo,
                isPending = true
            )

            opLogDao.insertOpLogEntry(entry)

            Result.success(entry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
