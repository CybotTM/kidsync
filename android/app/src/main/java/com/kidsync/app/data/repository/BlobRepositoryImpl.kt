package com.kidsync.app.data.repository

import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.domain.repository.BlobRepository
import com.kidsync.app.domain.repository.BlobUploadResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest

class BlobRepositoryImpl(
    private val apiService: ApiService
) : BlobRepository {

    override suspend fun uploadBlob(
        bucketId: String,
        blobId: String,
        encryptedData: ByteArray,
        mimeType: String
    ): Result<BlobUploadResult> {
        return try {
            val requestBody = encryptedData.toRequestBody("application/octet-stream".toMediaType())
            val part = MultipartBody.Part.createFormData(
                "file",
                "$blobId.enc",
                requestBody
            )

            // Compute SHA-256 hash of the encrypted data
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(encryptedData)
            val sha256Hex = hashBytes.joinToString("") { "%02x".format(it) }
            val sha256Part = sha256Hex.toRequestBody("text/plain".toMediaType())

            val body = apiService.uploadBlob(bucketId, part, sha256Part)

            Result.success(
                BlobUploadResult(
                    blobId = body.blobId,
                    sizeBytes = body.sizeBytes
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadBlob(bucketId: String, blobId: String): Result<ByteArray> {
        return try {
            val body = apiService.downloadBlob(bucketId, blobId)
            Result.success(body.bytes())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUploadUrl(bucketId: String, blobId: String, mimeType: String): Result<String> {
        // Direct upload is used instead of pre-signed URLs in this implementation
        return Result.success("direct-upload")
    }

    override suspend fun confirmUpload(bucketId: String, blobId: String): Result<Unit> {
        // Upload confirmation is implicit with direct upload
        return Result.success(Unit)
    }

    override suspend fun retryPendingUploads(bucketId: String): Result<Int> {
        // Pending upload retry logic would be implemented with a local queue
        return Result.success(0)
    }
}
