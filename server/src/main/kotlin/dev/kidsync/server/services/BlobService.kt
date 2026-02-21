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
                    it[filePath] = blobFile.absolutePath
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
     * Download a blob. Returns the file bytes and sha256 hash.
     */
    suspend fun downloadBlob(blobId: String, deviceId: String, bucketId: String): Pair<ByteArray, String> {
        return dbQuery {
            BucketService.requireBucketAccess(bucketId, deviceId)

            val blob = Blobs.selectAll().where {
                (Blobs.id eq blobId) and (Blobs.bucketId eq bucketId)
            }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Blob not found")

            val file = File(blob[Blobs.filePath])
            if (!file.exists()) {
                throw ApiException(404, "NOT_FOUND", "Blob file not found on disk")
            }

            Pair(file.readBytes(), blob[Blobs.sha256Hash])
        }
    }

    private fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
