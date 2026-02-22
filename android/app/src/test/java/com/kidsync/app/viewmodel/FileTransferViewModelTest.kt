package com.kidsync.app.viewmodel

import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.data.local.entity.BucketEntity
import com.kidsync.app.sync.filetransfer.ExportManifest
import com.kidsync.app.sync.filetransfer.FileTransferManager
import com.kidsync.app.sync.filetransfer.ImportResult
import com.kidsync.app.ui.viewmodel.FileTransferViewModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()
    val fileTransferManager = mockk<FileTransferManager>(relaxed = true)
    val bucketDao = mockk<BucketDao>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        coEvery { bucketDao.getAllBuckets() } returns emptyList()
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): FileTransferViewModel {
        return FileTransferViewModel(fileTransferManager, bucketDao)
    }

    // ── Export Flow ───────────────────────────────────────────────────────────

    test("exportBucket with no selected bucket sets error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val out = ByteArrayOutputStream()
            vm.exportBucket(out)
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "No bucket"
        }
    }

    test("exportBucket success updates state with manifest") {
        runTest(testDispatcher) {
            val buckets = listOf(BucketEntity("bucket-1", "device-1", "2026-01-01T00:00:00Z"))
            coEvery { bucketDao.getAllBuckets() } returns buckets

            val manifest = ExportManifest(
                formatVersion = 1,
                exportedAt = "2026-01-01T00:00:00Z",
                bucketId = "bucket-1",
                deviceId = "device-1",
                opCount = 42,
                hashChainTip = "abc123"
            )
            coEvery { fileTransferManager.exportBucket("bucket-1", any()) } returns Result.success(manifest)

            val vm = createViewModel()
            advanceUntilIdle()

            val out = ByteArrayOutputStream()
            vm.exportBucket(out)
            advanceUntilIdle()

            vm.uiState.value.isExporting shouldBe false
            vm.uiState.value.exportResult shouldNotBe null
            vm.uiState.value.exportResult!!.opCount shouldBe 42
            vm.uiState.value.error shouldBe null
        }
    }

    test("exportBucket failure sets error") {
        runTest(testDispatcher) {
            val buckets = listOf(BucketEntity("bucket-1", "device-1", "2026-01-01T00:00:00Z"))
            coEvery { bucketDao.getAllBuckets() } returns buckets
            coEvery { fileTransferManager.exportBucket("bucket-1", any()) } returns Result.failure(
                RuntimeException("Disk full")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.exportBucket(ByteArrayOutputStream())
            advanceUntilIdle()

            vm.uiState.value.isExporting shouldBe false
            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "Disk full"
        }
    }

    // ── Import Flow ──────────────────────────────────────────────────────────

    test("importBundle success updates state with result") {
        runTest(testDispatcher) {
            val importResult = ImportResult(
                bucketId = "bucket-1",
                totalOps = 100,
                newOps = 80,
                skippedDuplicates = 20
            )
            coEvery { fileTransferManager.importBundle(any()) } returns Result.success(importResult)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.importBundle(ByteArrayInputStream(ByteArray(0)))
            advanceUntilIdle()

            vm.uiState.value.isImporting shouldBe false
            vm.uiState.value.importResult shouldNotBe null
            vm.uiState.value.importResult!!.newOps shouldBe 80
        }
    }

    test("importBundle with corrupt file sets error") {
        runTest(testDispatcher) {
            coEvery { fileTransferManager.importBundle(any()) } returns Result.failure(
                IllegalStateException("Invalid .kidsync bundle: missing manifest.json")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.importBundle(ByteArrayInputStream(ByteArray(0)))
            advanceUntilIdle()

            vm.uiState.value.isImporting shouldBe false
            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "manifest"
        }
    }

    // ── Bucket Selection ─────────────────────────────────────────────────────

    test("selectBucket updates selectedBucketId") {
        runTest(testDispatcher) {
            val buckets = listOf(
                BucketEntity("bucket-1", "device-1", "2026-01-01T00:00:00Z"),
                BucketEntity("bucket-2", "device-1", "2026-01-01T00:00:00Z")
            )
            coEvery { bucketDao.getAllBuckets() } returns buckets

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectBucket("bucket-2")
            vm.uiState.value.selectedBucketId shouldBe "bucket-2"
        }
    }

    test("loadBuckets populates bucket list from DAO") {
        runTest(testDispatcher) {
            val buckets = listOf(
                BucketEntity("bucket-a", "device-1", "2026-01-01T00:00:00Z"),
                BucketEntity("bucket-b", "device-1", "2026-01-02T00:00:00Z")
            )
            coEvery { bucketDao.getAllBuckets() } returns buckets

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.buckets.size shouldBe 2
            vm.uiState.value.selectedBucketId shouldBe "bucket-a"
        }
    }

    // ── Clear ────────────────────────────────────────────────────────────────

    test("clearError resets error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.exportBucket(ByteArrayOutputStream()) // triggers error
            advanceUntilIdle()

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }

    test("clearResults resets both export and import results") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.clearResults()
            vm.uiState.value.exportResult shouldBe null
            vm.uiState.value.importResult shouldBe null
            vm.uiState.value.error shouldBe null
        }
    }
})
