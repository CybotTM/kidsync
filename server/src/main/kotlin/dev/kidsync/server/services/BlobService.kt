package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.Blobs
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.UploadBlobResponse
import dev.kidsync.server.util.HashUtil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*

class BlobService(private val config: AppConfig) {

    /**
     * Upload an encrypted blob. Returns the blob metadata.
     */
    suspend fun uploadBlob(
        userId: String,
        familyId: String,
        fileBytes: ByteArray,
    ): Result<UploadBlobResponse> {
        if (fileBytes.size > config.maxBlobSizeBytes) {
            return Result.failure(
                ApiException(413, "BLOB_TOO_LARGE", "File exceeds ${config.maxBlobSizeBytes} byte limit")
            )
        }

        val blobId = UUID.randomUUID().toString()
        val sha256 = computeSha256(fileBytes)
        val now = LocalDateTime.now(ZoneOffset.UTC)

        // Write to filesystem
        val blobDir = File(config.blobStoragePath)
        blobDir.mkdirs()
        val blobFile = File(blobDir, blobId)
        blobFile.writeBytes(fileBytes)

        dbQuery {
            Blobs.insert {
                it[id] = blobId
                it[Blobs.familyId] = familyId
                it[filePath] = blobFile.absolutePath
                it[sizeBytes] = fileBytes.size.toLong()
                it[sha256Hash] = sha256
                it[uploadedBy] = userId
                it[uploadedAt] = now
            }
        }

        return Result.success(
            UploadBlobResponse(
                blobId = blobId,
                sizeBytes = fileBytes.size.toLong(),
                sha256Hash = sha256,
            )
        )
    }

    /**
     * Download a blob. Returns the file bytes or null if not found.
     */
    suspend fun downloadBlob(blobId: String, userId: String, familyId: String): Result<Pair<ByteArray, String>> {
        return dbQuery {
            val blob = Blobs.selectAll().where {
                (Blobs.id eq blobId) and (Blobs.familyId eq familyId) and Blobs.deletedAt.isNull()
            }.firstOrNull()
                ?: return@dbQuery Result.failure(ApiException(404, "NOT_FOUND", "Blob not found"))

            val file = File(blob[Blobs.filePath])
            if (!file.exists()) {
                return@dbQuery Result.failure(ApiException(404, "NOT_FOUND", "Blob file not found on disk"))
            }

            val sha256 = blob[Blobs.sha256Hash]
            Result.success(Pair(file.readBytes(), sha256))
        }
    }

    /**
     * Soft-delete a blob. Only the uploader can delete.
     */
    suspend fun deleteBlob(blobId: String, userId: String): Result<Unit> {
        return dbQuery {
            val blob = Blobs.selectAll().where {
                (Blobs.id eq blobId) and Blobs.deletedAt.isNull()
            }.firstOrNull()
                ?: return@dbQuery Result.failure(ApiException(404, "NOT_FOUND", "Blob not found"))

            if (blob[Blobs.uploadedBy] != userId) {
                return@dbQuery Result.failure(ApiException(403, "FORBIDDEN", "Only the uploader can delete this blob"))
            }

            Blobs.update({ Blobs.id eq blobId }) {
                it[deletedAt] = LocalDateTime.now(ZoneOffset.UTC)
            }

            Result.success(Unit)
        }
    }

    private fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
