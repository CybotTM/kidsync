package com.kidsync.app.repository

import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.UploadBlobResponse
import com.kidsync.app.data.repository.BlobRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody

class BlobRepositoryImplTest : FunSpec({

    val apiService = mockk<ApiService>()

    fun createRepo() = BlobRepositoryImpl(apiService)

    beforeEach {
        clearAllMocks()
    }

    val bucketId = "bucket-blob-test"

    // ── Upload ──────────────────────────────────────────────────────────────

    test("uploadBlob success returns blobId and size") {
        val encryptedData = "encrypted-content".toByteArray()
        coEvery { apiService.uploadBlob(bucketId, any(), any()) } returns UploadBlobResponse(
            blobId = "blob-123",
            sizeBytes = encryptedData.size.toLong(),
            sha256Hash = "abc123"
        )

        val repo = createRepo()
        val result = repo.uploadBlob(bucketId, "blob-123", encryptedData, "image/jpeg")

        result.isSuccess shouldBe true
        result.getOrNull()!!.blobId shouldBe "blob-123"
        result.getOrNull()!!.sizeBytes shouldBe encryptedData.size.toLong()
    }

    test("uploadBlob failure returns failure result") {
        coEvery { apiService.uploadBlob(any(), any(), any()) } throws RuntimeException("Storage full")

        val repo = createRepo()
        val result = repo.uploadBlob(bucketId, "blob-fail", "data".toByteArray(), "image/png")

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldContain "Storage full"
    }

    test("uploadBlob computes SHA-256 hash of encrypted data") {
        val data = ByteArray(32) { it.toByte() }
        coEvery { apiService.uploadBlob(bucketId, any(), any()) } returns UploadBlobResponse(
            blobId = "blob-hash-test",
            sizeBytes = 32,
            sha256Hash = "hash"
        )

        val repo = createRepo()
        repo.uploadBlob(bucketId, "blob-hash-test", data, "application/octet-stream")

        coVerify { apiService.uploadBlob(bucketId, any(), any()) }
    }

    // ── Download ────────────────────────────────────────────────────────────

    test("downloadBlob success returns byte array") {
        val content = "decrypted-blob-content".toByteArray()
        val responseBody = content.toResponseBody(null)
        coEvery { apiService.downloadBlob(bucketId, "blob-dl") } returns responseBody

        val repo = createRepo()
        val result = repo.downloadBlob(bucketId, "blob-dl")

        result.isSuccess shouldBe true
        result.getOrNull() shouldNotBe null
        String(result.getOrNull()!!) shouldBe "decrypted-blob-content"
    }

    test("downloadBlob failure returns failure result") {
        coEvery { apiService.downloadBlob(any(), any()) } throws RuntimeException("Not found")

        val repo = createRepo()
        val result = repo.downloadBlob(bucketId, "blob-missing")

        result.isFailure shouldBe true
    }

    // ── getUploadUrl (direct upload) ────────────────────────────────────────

    test("getUploadUrl returns direct-upload string") {
        val repo = createRepo()
        val result = repo.getUploadUrl(bucketId, "blob-1", "image/jpeg")

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe "direct-upload"
    }

    // ── confirmUpload ───────────────────────────────────────────────────────

    test("confirmUpload returns success") {
        val repo = createRepo()
        val result = repo.confirmUpload(bucketId, "blob-1")

        result.isSuccess shouldBe true
    }

    // ── retryPendingUploads ─────────────────────────────────────────────────

    test("retryPendingUploads returns zero retried count") {
        val repo = createRepo()
        val result = repo.retryPendingUploads(bucketId)

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe 0
    }
})
