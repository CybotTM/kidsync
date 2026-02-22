package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.Blobs
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.BlobResponse
import org.jetbrains.exposed.sql.*
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.*

class BlobService(private val config: AppConfig) {

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    companion object {
        // SEC5-S-13: Per-bucket blob storage quota (1 GB)
        const val MAX_BUCKET_BLOB_STORAGE = 1_073_741_824L
        // SEC5-S-13: Max number of blobs per bucket
        const val MAX_BLOBS_PER_BUCKET = 1000
    }

    /**
     * Upload an encrypted blob to a bucket. Returns blob metadata.
     */
    suspend fun uploadBlob(
        deviceId: String,
        bucketId: String,
        fileBytes: ByteArray,
    ): BlobResponse {
        if (fileBytes.size > config.maxBlobSizeBytes) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "File exceeds ${config.maxBlobSizeBytes} byte limit")
        }

        // Check access BEFORE writing file to disk (prevent TOCTOU)
        dbQuery { BucketService.requireBucketAccess(bucketId, deviceId) }

        // SEC5-S-13: Check per-bucket blob count and storage quota
        dbQuery {
            val bucketBlobs = Blobs.selectAll()
                .where { Blobs.bucketId eq bucketId }

            val blobCount = bucketBlobs.count()
            if (blobCount >= MAX_BLOBS_PER_BUCKET) {
                throw ApiException(429, "QUOTA_EXCEEDED", "Maximum number of blobs per bucket exceeded")
            }

            val currentSize = Blobs.selectAll()
                .where { Blobs.bucketId eq bucketId }
                .sumOf { it[Blobs.sizeBytes] }
            if (currentSize + fileBytes.size > MAX_BUCKET_BLOB_STORAGE) {
                throw ApiException(429, "QUOTA_EXCEEDED", "Bucket blob storage quota exceeded")
            }
        }

        val blobId = UUID.randomUUID().toString()
        val sha256 = computeSha256(fileBytes)
        val now = LocalDateTime.now(ZoneOffset.UTC)

        // Write to filesystem only after access check passes
        val blobDir = File(config.blobStoragePath)
        blobDir.mkdirs()
        val blobFile = File(blobDir, blobId)
        blobFile.writeBytes(fileBytes)

        try {
            dbQuery {
                Blobs.insert {
                    it[id] = blobId
                    it[Blobs.bucketId] = bucketId
                    // SEC-S-15: Store only the filename, not the absolute path
                    it[filePath] = blobId
                    it[sizeBytes] = fileBytes.size.toLong()
                    it[sha256Hash] = sha256
                    it[uploadedBy] = deviceId
                    it[uploadedAt] = now
                }
            }
        } catch (e: Exception) {
            // Clean up orphaned file on DB insert failure
            blobFile.delete()
            throw e
        }

        return BlobResponse(
            blobId = blobId,
            sizeBytes = fileBytes.size.toLong(),
            sha256 = sha256,
            uploadedAt = now.atOffset(ZoneOffset.UTC).format(isoFormatter),
        )
    }

    /**
     * SEC5-S-22: Download a blob. Returns the file path and sha256 hash for streaming.
     * The caller should use call.respondFile() to stream the file instead of reading
     * all bytes into memory.
     */
    suspend fun downloadBlob(blobId: String, deviceId: String, bucketId: String): Pair<File, String> {
        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            val blob = Blobs.selectAll().where {
                (Blobs.id eq blobId) and (Blobs.bucketId eq bucketId)
            }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Blob not found")

            // SEC-S-15: Resolve relative filename to absolute path at read time
            val file = File(config.blobStoragePath, blob[Blobs.filePath])

            // SEC-S-01: Path traversal protection - verify resolved path is within storage directory
            val blobDir = File(config.blobStoragePath).canonicalFile
            if (!file.canonicalFile.startsWith(blobDir)) {
                throw ApiException(403, "BUCKET_ACCESS_DENIED", "Invalid file path")
            }

            if (!file.exists()) {
                throw ApiException(404, "NOT_FOUND", "Blob file not found on disk")
            }

            Pair(file, blob[Blobs.sha256Hash])
        }
    }

    private fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
