package com.kidsync.app.domain.repository

import java.util.UUID

interface BlobRepository {
    suspend fun uploadBlob(
        familyId: UUID,
        blobId: UUID,
        encryptedData: ByteArray,
        mimeType: String
    ): Result<BlobUploadResult>

    suspend fun downloadBlob(familyId: UUID, blobId: UUID): Result<ByteArray>

    suspend fun getUploadUrl(familyId: UUID, blobId: UUID, mimeType: String): Result<String>

    suspend fun confirmUpload(familyId: UUID, blobId: UUID): Result<Unit>

    suspend fun retryPendingUploads(familyId: UUID): Result<Int>
}

data class BlobUploadResult(
    val blobId: UUID,
    val sizeBytes: Long
)
