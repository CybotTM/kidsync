package com.kidsync.app.data.repository

import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.domain.repository.BlobRepository
import com.kidsync.app.domain.repository.BlobUploadResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject

class BlobRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : BlobRepository {

    override suspend fun uploadBlob(
        familyId: UUID,
        blobId: UUID,
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

            val response = apiService.uploadBlob(part)
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            Result.success(
                BlobUploadResult(
                    blobId = UUID.fromString(body.blobId),
                    sizeBytes = body.sizeBytes
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun downloadBlob(familyId: UUID, blobId: UUID): Result<ByteArray> {
        return try {
            val response = apiService.downloadBlob(blobId.toString())
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            Result.success(body.bytes())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getUploadUrl(familyId: UUID, blobId: UUID, mimeType: String): Result<String> {
        // Direct upload is used instead of pre-signed URLs in this implementation
        return Result.success("direct-upload")
    }

    override suspend fun confirmUpload(familyId: UUID, blobId: UUID): Result<Unit> {
        // Upload confirmation is implicit with direct upload
        return Result.success(Unit)
    }

    override suspend fun retryPendingUploads(familyId: UUID): Result<Int> {
        // Pending upload retry logic would be implemented with a local queue
        return Result.success(0)
    }
}
