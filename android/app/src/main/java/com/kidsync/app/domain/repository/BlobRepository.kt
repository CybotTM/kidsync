package com.kidsync.app.domain.repository

interface BlobRepository {
    suspend fun uploadBlob(
        bucketId: String,
        blobId: String,
        encryptedData: ByteArray,
        mimeType: String
    ): Result<BlobUploadResult>

    suspend fun downloadBlob(bucketId: String, blobId: String): Result<ByteArray>

    suspend fun getUploadUrl(bucketId: String, blobId: String, mimeType: String): Result<String>

    suspend fun confirmUpload(bucketId: String, blobId: String): Result<Unit>

    suspend fun retryPendingUploads(bucketId: String): Result<Int>
}

data class BlobUploadResult(
    val blobId: String,
    val sizeBytes: Long
)
