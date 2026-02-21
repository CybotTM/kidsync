package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.DecryptedPayload
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import javax.inject.Inject

/**
 * Creates a new operation and adds it to the local oplog for sync.
 *
 * In the zero-knowledge architecture, ALL metadata (entityType, entityId,
 * operation, clientTimestamp, protocolVersion) is placed INSIDE the encrypted
 * payload. The server sees only opaque encrypted bytes.
 *
 * Pipeline:
 *   DecryptedPayload (all metadata + data) -> JSON -> gzip -> AES-256-GCM -> OpLogEntry
 */
class CreateOperationUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val hashChainVerifier: HashChainVerifier,
    private val opLogDao: OpLogDao,
    private val json: Json,
    private val canonicalJsonSerializer: CanonicalJsonSerializer
) {
    suspend operator fun invoke(
        bucketId: String,
        entityType: EntityType,
        entityId: String,
        operationType: OperationType,
        contentData: JsonObject
    ): Result<OpLogEntryEntity> {
        return try {
            val deviceId = keyManager.getDeviceId()
                ?: return Result.failure(IllegalStateException("Device not registered"))

            // 1. Get current DEK epoch
            val currentEpoch = keyManager.getCurrentEpoch(bucketId)
            val dek = keyManager.getDek(bucketId, currentEpoch)
                ?: return Result.failure(IllegalStateException("No DEK available for epoch $currentEpoch"))

            // 2. Compute device sequence
            val lastOp = opLogDao.getLastOpForDevice(deviceId)
            val deviceSequence = (lastOp?.deviceSequence ?: 0L) + 1

            // 3. Build the full DecryptedPayload with ALL metadata inside
            val decryptedPayload = DecryptedPayload(
                deviceSequence = deviceSequence,
                entityType = entityType.name,
                entityId = entityId,
                operation = operationType.name,
                clientTimestamp = Instant.now().toString(),
                protocolVersion = 2,
                data = contentData
            )

            // 4. Serialize to canonical JSON (sorted keys, compact, no nulls)
            val payloadJson = canonicalJsonSerializer.serializeElement(
                json.encodeToJsonElement(decryptedPayload).jsonObject
            )

            // 5. Encrypt: JSON -> gzip -> AES-256-GCM
            //    AAD = "bucketId|deviceId"
            val aad = CryptoManager.buildPayloadAad(
                bucketId = bucketId,
                deviceId = deviceId
            )
            val encryptedPayload = cryptoManager.encryptPayload(
                plaintext = payloadJson,
                dek = dek,
                aad = aad
            )

            // 6. Compute hash chain
            val devicePrevHash = lastOp?.currentHash ?: HashChainVerifier.GENESIS_HASH
            val currentHash = hashChainVerifier.computeHash(devicePrevHash, encryptedPayload)

            // 7. Create OpLogEntry -- NO plaintext metadata columns
            val entry = OpLogEntryEntity(
                globalSequence = 0, // assigned by server
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = deviceSequence,
                keyEpoch = currentEpoch,
                encryptedPayload = encryptedPayload,
                devicePrevHash = devicePrevHash,
                currentHash = currentHash,
                serverTimestamp = null,
                isPending = true
            )

            opLogDao.insertOpLogEntry(entry)

            Result.success(entry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
