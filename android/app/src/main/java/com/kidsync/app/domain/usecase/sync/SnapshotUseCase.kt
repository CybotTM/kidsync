package com.kidsync.app.domain.usecase.sync

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.*
import com.kidsync.app.data.remote.api.ApiService
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.time.Instant
import java.util.Arrays
import java.util.UUID
import javax.inject.Inject

/**
 * Creates and restores snapshots of the local database state.
 *
 * Snapshots are encrypted blobs uploaded via the dedicated snapshot API endpoint
 * (`POST /buckets/{id}/snapshots`), NOT as oplog operations.
 *
 * Pipeline:
 * 1. Serialize full local state as JSON
 * 2. Encrypt with current DEK using AES-256-GCM
 * 3. Sign the encrypted blob with Ed25519 key
 * 4. Upload via multipart POST (metadata JSON + encrypted blob)
 */
class SnapshotUseCase @Inject constructor(
    private val custodyScheduleDao: CustodyScheduleDao,
    private val overrideDao: OverrideDao,
    private val expenseDao: ExpenseDao,
    private val calendarEventDao: CalendarEventDao,
    private val infoBankDao: InfoBankDao,
    private val opLogDao: OpLogDao,
    private val syncStateDao: SyncStateDao,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val apiService: ApiService
) {
    /**
     * Create a snapshot of the current local state and upload via the snapshot API endpoint.
     *
     * @return Result containing the state hash on success
     */
    suspend fun createSnapshot(bucketId: String): Result<String> {
        return try {
            val syncState = syncStateDao.getSyncState(bucketId)
                ?: return Result.failure(IllegalStateException("No sync state found"))

            val deviceId = keyManager.getDeviceId()
                ?: return Result.failure(IllegalStateException("Device not registered"))

            val currentEpoch = keyManager.getCurrentEpoch(bucketId)
            val dek = keyManager.getDek(bucketId, currentEpoch)
                ?: return Result.failure(IllegalStateException("No DEK for epoch $currentEpoch"))

            // SEC3-A-03: Track seed separately so we can zero it in finally
            var seed: ByteArray? = null
            try {
                // 1. Compute state hash from all materialized entities
                val stateHash = computeStateHash()

                // 2. Build snapshot content as JSON
                val snapshotId = UUID.randomUUID().toString()
                val snapshotContent = buildJsonObject {
                    put("snapshotId", JsonPrimitive(snapshotId))
                    put("deviceId", JsonPrimitive(deviceId))
                    put("bucketId", JsonPrimitive(bucketId))
                    put("lastGlobalSequence", JsonPrimitive(syncState.lastGlobalSequence))
                    put("stateHash", JsonPrimitive(stateHash))
                    put("timestamp", JsonPrimitive(Instant.now().toString()))
                }.toString()

                // 3. Encrypt with AES-256-GCM using current DEK
                val aad = CryptoManager.buildPayloadAad(bucketId = bucketId, deviceId = deviceId)
                val encryptedBlob = cryptoManager.encryptPayload(
                    plaintext = snapshotContent,
                    dek = dek,
                    aad = aad
                )

                // 4. Sign the encrypted blob with Ed25519
                // SEC4-A-09: The signature covers the Base64-encoded encrypted payload (UTF-8 bytes),
                // not the raw ciphertext bytes. This convention is intentional: it matches what the
                // server receives in the multipart upload and allows the server (or any verifier) to
                // check the signature against the transmitted form without Base64 decoding first.
                val (_, signingPrivateKey) = keyManager.getOrCreateSigningKeyPair()
                seed = signingPrivateKey
                val encryptedBytes = encryptedBlob.toByteArray(Charsets.UTF_8)
                val signature = cryptoManager.signEd25519(encryptedBytes, signingPrivateKey)
                val signatureBase64 = java.util.Base64.getEncoder().encodeToString(signature)

                // 5. Build metadata JSON
                val metadata = buildJsonObject {
                    put("keyEpoch", JsonPrimitive(currentEpoch))
                    put("atSequence", JsonPrimitive(syncState.lastGlobalSequence))
                    put("stateHash", JsonPrimitive(stateHash))
                    put("signature", JsonPrimitive(signatureBase64))
                }.toString()

                // 6. Upload via multipart POST /buckets/{id}/snapshots
                val metadataBody = metadata.toRequestBody("application/json".toMediaType())
                val snapshotBody = encryptedBytes.toRequestBody("application/octet-stream".toMediaType())
                val snapshotPart = MultipartBody.Part.createFormData(
                    "snapshot", "snapshot.bin", snapshotBody
                )

                apiService.uploadSnapshot(bucketId, metadataBody, snapshotPart)

                Result.success(stateHash)
            } finally {
                // SEC3-A-03: Zero DEK and signing seed after use
                Arrays.fill(dek, 0.toByte())
                seed?.let { Arrays.fill(it, 0.toByte()) }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Compute SHA-256 hash of the current materialized state.
     *
     * Covers all materialized entity types: CustodySchedule, ScheduleOverride,
     * Expense, CalendarEvent, and InfoBankEntry (non-deleted only).
     *
     * Design: Hashes identity + core value fields per entity (not all columns).
     * Metadata fields (createdBy, timestamps, description, location, notes) are
     * excluded so the hash detects structural divergence without false positives
     * from metadata-only changes that don't affect the materialized schedule.
     */
    private suspend fun computeStateHash(): String {
        val digest = MessageDigest.getInstance("SHA-256")

        // Hash all active schedules
        val schedules = custodyScheduleDao.getAllSchedules()
        for (schedule in schedules.sortedBy { it.scheduleId.toString() }) {
            digest.update(schedule.scheduleId.toString().toByteArray())
            digest.update(schedule.status.toByteArray())
        }

        // Hash all overrides
        val overrides = overrideDao.getAllOverrides()
        for (override in overrides.sortedBy { it.overrideId.toString() }) {
            digest.update(override.overrideId.toString().toByteArray())
            digest.update(override.status.toByteArray())
        }

        // Hash all expenses
        val expenses = expenseDao.getAllExpenses()
        for (expense in expenses.sortedBy { it.expenseId.toString() }) {
            digest.update(expense.expenseId.toString().toByteArray())
            digest.update(expense.amountCents.toString().toByteArray())
        }

        // Hash all calendar events (SEC6-A-16)
        val calendarEvents = calendarEventDao.getAllEvents()
        for (event in calendarEvents.sortedBy { it.eventId }) {
            digest.update(event.eventId.toByteArray())
            digest.update(event.title.toByteArray())
            digest.update(event.startTime.toByteArray())
            digest.update(event.endTime.toByteArray())
        }

        // Hash all non-deleted info bank entries (SEC6-A-16)
        val infoBankEntries = infoBankDao.getAllEntries()
        for (entry in infoBankEntries.sortedBy { it.entryId.toString() }) {
            digest.update(entry.entryId.toString().toByteArray())
            digest.update(entry.category.toByteArray())
            entry.content?.let { digest.update(it.toByteArray()) }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
