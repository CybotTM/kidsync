package com.kidsync.app.sync.filetransfer

import android.util.Log
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ExportManifest(
    val formatVersion: Int = 1,
    val exportedAt: String,
    val bucketId: String,
    val deviceId: String,
    val opCount: Long,
    val hashChainTip: String
)

@Serializable
data class ExportedOp(
    val globalSequence: Long,
    val deviceId: String,
    val deviceSequence: Long,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val serverTimestamp: String?
)

data class ImportResult(
    val bucketId: String,
    val totalOps: Long,
    val newOps: Long,
    val skippedDuplicates: Long
)

/**
 * Manages export and import of OpLog data to/from .kidsync bundle files.
 *
 * A .kidsync file is a ZIP archive containing:
 * - manifest.json: metadata about the export
 * - ops.jsonl: one OpLogEntry per line as JSON (streaming format)
 *
 * The encrypted payloads are exported as-is; the .kidsync file contains
 * encrypted data, NOT plaintext.
 */
class FileTransferManager(
    private val opLogDao: OpLogDao,
    private val keyManager: KeyManager,
    private val hashChainVerifier: HashChainVerifier = HashChainVerifier()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Export all ops for a bucket to a .kidsync ZIP file.
     * Uses JSONL streaming to avoid OOM on large datasets.
     */
    suspend fun exportBucket(bucketId: String, outputStream: OutputStream): Result<ExportManifest> {
        return try {
            val deviceId = keyManager.getDeviceId() ?: return Result.failure(
                IllegalStateException("Device ID not available")
            )

            val opCount = opLogDao.getOpsCountForBucket(bucketId)
            val lastOp = opLogDao.getLastOpForBucket(bucketId)
            val hashChainTip = lastOp?.currentHash ?: ""

            val manifest = ExportManifest(
                formatVersion = CURRENT_FORMAT_VERSION,
                exportedAt = Instant.now().toString(),
                bucketId = bucketId,
                deviceId = deviceId,
                opCount = opCount,
                hashChainTip = hashChainTip
            )

            ZipOutputStream(outputStream).use { zipOut ->
                // Write manifest.json
                zipOut.putNextEntry(ZipEntry(MANIFEST_FILENAME))
                val manifestJson = json.encodeToString(manifest)
                zipOut.write(manifestJson.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()

                // Write ops.jsonl - stream in batches to avoid OOM
                zipOut.putNextEntry(ZipEntry(OPS_FILENAME))
                val writer = BufferedWriter(OutputStreamWriter(zipOut, Charsets.UTF_8))
                var lastSequence = 0L
                while (true) {
                    val batch = opLogDao.getOpsAfterSequence(bucketId, lastSequence, limit = EXPORT_BATCH_SIZE)
                    if (batch.isEmpty()) break
                    for (op in batch) {
                        val exportedOp = op.toExportedOp()
                        val line = json.encodeToString(exportedOp)
                        writer.write(line)
                        writer.newLine()
                    }
                    lastSequence = batch.last().globalSequence
                }
                writer.flush()
                zipOut.closeEntry()
            }

            Result.success(manifest)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Import ops from a .kidsync ZIP file.
     * Skips duplicate ops (matching bucketId + deviceId + deviceSequence).
     * Returns count of new ops imported.
     *
     * SEC7: Added ZIP bomb protection (manifest size, op count, total bytes limits),
     * hash chain verification, optional bucket validation, and transactional inserts.
     *
     * @param expectedBucketId If non-null, the import will be rejected if the
     *   manifest's bucketId does not match this value (cross-bucket injection protection).
     */
    suspend fun importBundle(
        inputStream: InputStream,
        expectedBucketId: String? = null
    ): Result<ImportResult> {
        return try {
            var manifest: ExportManifest? = null
            var totalOps = 0L
            var newOps = 0L
            var skippedDuplicates = 0L
            var totalBytesRead = 0L

            ZipInputStream(inputStream).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    when (entry.name) {
                        MANIFEST_FILENAME -> {
                            // SEC7: Cap manifest read at MAX_MANIFEST_SIZE_BYTES
                            val manifestBytes = readLimited(zipIn, MAX_MANIFEST_SIZE_BYTES)
                                ?: return Result.failure(
                                    SecurityException("Manifest exceeds ${MAX_MANIFEST_SIZE_BYTES / 1024}KB size limit")
                                )
                            totalBytesRead += manifestBytes.size

                            val manifestContent = manifestBytes.toString(Charsets.UTF_8)
                            manifest = json.decodeFromString<ExportManifest>(manifestContent)

                            if (manifest!!.formatVersion != CURRENT_FORMAT_VERSION) {
                                return Result.failure(
                                    IllegalArgumentException(
                                        "Unsupported format version: ${manifest!!.formatVersion}. Expected: $CURRENT_FORMAT_VERSION"
                                    )
                                )
                            }

                            // SEC7: Validate bucket ID if expected
                            if (expectedBucketId != null && manifest!!.bucketId != expectedBucketId) {
                                return Result.failure(
                                    SecurityException(
                                        "Bucket ID mismatch: expected=$expectedBucketId, got=${manifest!!.bucketId}"
                                    )
                                )
                            }
                        }
                        OPS_FILENAME -> {
                            if (manifest == null) {
                                return Result.failure(
                                    IllegalStateException("manifest.json must appear before ops.jsonl in the archive")
                                )
                            }
                            val bucketId = manifest!!.bucketId
                            val reader = BufferedReader(InputStreamReader(zipIn, Charsets.UTF_8))
                            val batch = mutableListOf<OpLogEntryEntity>()
                            var line = reader.readLine()
                            while (line != null) {
                                if (line.isNotBlank()) {
                                    // SEC7: Track total bytes read for ZIP bomb protection
                                    totalBytesRead += line.length
                                    if (totalBytesRead > MAX_TOTAL_BYTES) {
                                        return Result.failure(
                                            SecurityException(
                                                "Import exceeds ${MAX_TOTAL_BYTES / (1024 * 1024)}MB total size limit (ZIP bomb protection)"
                                            )
                                        )
                                    }

                                    totalOps++

                                    // SEC7: Cap op count to prevent DoS
                                    if (totalOps > MAX_OP_COUNT) {
                                        return Result.failure(
                                            SecurityException(
                                                "Import exceeds $MAX_OP_COUNT op count limit"
                                            )
                                        )
                                    }

                                    val exportedOp = json.decodeFromString<ExportedOp>(line)
                                    val existing = opLogDao.findOp(
                                        bucketId = bucketId,
                                        deviceId = exportedOp.deviceId,
                                        deviceSequence = exportedOp.deviceSequence
                                    )
                                    if (existing == null) {
                                        batch.add(exportedOp.toEntity(bucketId))
                                        newOps++
                                    } else {
                                        skippedDuplicates++
                                    }
                                }
                                line = reader.readLine()
                            }

                            // SEC7: Verify hash chain integrity before inserting ops
                            if (batch.isNotEmpty()) {
                                val opLogEntries = batch.map { entity ->
                                    OpLogEntry(
                                        globalSequence = entity.globalSequence,
                                        bucketId = entity.bucketId,
                                        deviceId = entity.deviceId,
                                        deviceSequence = entity.deviceSequence,
                                        keyEpoch = entity.keyEpoch,
                                        encryptedPayload = entity.encryptedPayload,
                                        devicePrevHash = entity.devicePrevHash,
                                        currentHash = entity.currentHash,
                                        serverTimestamp = null
                                    )
                                }

                                val localLastOps = opLogDao.getLastOpsPerDeviceForBucket(bucketId)
                                val localLastHashes = localLastOps.associate { it.deviceId to it.currentHash }

                                val verifyResult = hashChainVerifier.verifyChains(opLogEntries, localLastHashes)
                                if (verifyResult.isFailure) {
                                    Log.e(TAG, "Hash chain verification failed: ${verifyResult.exceptionOrNull()?.message}")
                                    return Result.failure(
                                        SecurityException(
                                            "Hash chain verification failed: ${verifyResult.exceptionOrNull()?.message}"
                                        )
                                    )
                                }

                                // SEC7: Batch insert within a single transaction
                                opLogDao.insertOpLogEntries(batch)
                            }
                        }
                    }
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }
            }

            if (manifest == null) {
                return Result.failure(
                    IllegalStateException("Invalid .kidsync bundle: missing manifest.json")
                )
            }

            Result.success(
                ImportResult(
                    bucketId = manifest!!.bucketId,
                    totalOps = totalOps,
                    newOps = newOps,
                    skippedDuplicates = skippedDuplicates
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * SEC7: Read from input stream with a size limit to prevent ZIP bomb attacks.
     * Returns null if the content exceeds the limit.
     */
    private fun readLimited(inputStream: InputStream, maxBytes: Long): ByteArray? {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var totalRead = 0L
        while (true) {
            val bytesRead = inputStream.read(chunk)
            if (bytesRead == -1) break
            totalRead += bytesRead
            if (totalRead > maxBytes) return null
            buffer.write(chunk, 0, bytesRead)
        }
        return buffer.toByteArray()
    }

    private fun OpLogEntryEntity.toExportedOp(): ExportedOp = ExportedOp(
        globalSequence = globalSequence,
        deviceId = deviceId,
        deviceSequence = deviceSequence,
        keyEpoch = keyEpoch,
        encryptedPayload = encryptedPayload,
        devicePrevHash = devicePrevHash,
        currentHash = currentHash,
        serverTimestamp = serverTimestamp
    )

    private fun ExportedOp.toEntity(bucketId: String): OpLogEntryEntity = OpLogEntryEntity(
        globalSequence = globalSequence,
        bucketId = bucketId,
        deviceId = deviceId,
        deviceSequence = deviceSequence,
        keyEpoch = keyEpoch,
        encryptedPayload = encryptedPayload,
        devicePrevHash = devicePrevHash,
        currentHash = currentHash,
        serverTimestamp = serverTimestamp,
        isPending = false
    )

    companion object {
        private const val TAG = "FileTransferManager"
        const val CURRENT_FORMAT_VERSION = 1
        const val MANIFEST_FILENAME = "manifest.json"
        const val OPS_FILENAME = "ops.jsonl"
        const val FILE_EXTENSION = ".kidsync"
        const val MIME_TYPE = "application/zip"
        private const val EXPORT_BATCH_SIZE = 100

        // SEC7: ZIP bomb protection limits
        private const val MAX_MANIFEST_SIZE_BYTES = 1 * 1024 * 1024L // 1 MB
        private const val MAX_OP_COUNT = 100_000L
        private const val MAX_TOTAL_BYTES = 100 * 1024 * 1024L // 100 MB
    }
}
