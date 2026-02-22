package com.kidsync.app.viewmodel

import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.data.local.entity.BucketEntity
import com.kidsync.app.sync.p2p.P2PState
import com.kidsync.app.sync.p2p.P2PSyncManager
import com.kidsync.app.ui.viewmodel.P2PViewModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class P2PViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()
    val p2pSyncManager = mockk<P2PSyncManager>(relaxed = true)
    val bucketDao = mockk<BucketDao>(relaxed = true)
    val p2pStateFlow = MutableStateFlow<P2PState>(P2PState.Idle)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        every { p2pSyncManager.state } returns p2pStateFlow
        coEvery { bucketDao.getAllBuckets() } returns emptyList()
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): P2PViewModel {
        return P2PViewModel(p2pSyncManager, bucketDao)
    }

    // ── Permission State ─────────────────────────────────────────────────────

    test("setPermissionsGranted updates state") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setPermissionsGranted(true)
            vm.uiState.value.permissionsGranted shouldBe true

            vm.setPermissionsGranted(false)
            vm.uiState.value.permissionsGranted shouldBe false
        }
    }

    // ── Bucket Loading ───────────────────────────────────────────────────────

    test("loadBuckets populates options on init") {
        runTest(testDispatcher) {
            val buckets = listOf(
                BucketEntity("bucket-abc-1234", "device-1", "2026-01-01T00:00:00Z"),
                BucketEntity("bucket-def-5678", "device-1", "2026-01-01T00:00:00Z")
            )
            coEvery { bucketDao.getAllBuckets() } returns buckets

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.buckets.size shouldBe 2
            vm.uiState.value.selectedBucketId shouldBe "bucket-abc-1234"
        }
    }

    test("loadBuckets error sets error state") {
        runTest(testDispatcher) {
            coEvery { bucketDao.getAllBuckets() } throws RuntimeException("DB error")

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "Failed to load"
        }
    }

    // ── Advertise Flow ───────────────────────────────────────────────────────

    test("startAdvertising with no bucket selected sets error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.startAdvertising()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "No bucket"
        }
    }

    test("startAdvertising with bucket selected calls manager") {
        runTest(testDispatcher) {
            val buckets = listOf(BucketEntity("bucket-1", "device-1", "2026-01-01T00:00:00Z"))
            coEvery { bucketDao.getAllBuckets() } returns buckets

            val vm = createViewModel()
            advanceUntilIdle()

            vm.startAdvertising()

            verify { p2pSyncManager.startAdvertising("bucket-1") }
        }
    }

    // ── Discover Flow ────────────────────────────────────────────────────────

    test("startDiscovery with no bucket selected sets error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.startDiscovery()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "No bucket"
        }
    }

    test("startDiscovery with bucket selected calls manager") {
        runTest(testDispatcher) {
            val buckets = listOf(BucketEntity("bucket-1", "device-1", "2026-01-01T00:00:00Z"))
            coEvery { bucketDao.getAllBuckets() } returns buckets

            val vm = createViewModel()
            advanceUntilIdle()

            vm.startDiscovery()

            verify { p2pSyncManager.startDiscovery("bucket-1") }
        }
    }

    // ── Stop ─────────────────────────────────────────────────────────────────

    test("stop calls manager stop") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.stop()

            verify { p2pSyncManager.stop() }
        }
    }

    // ── Select Bucket ────────────────────────────────────────────────────────

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

    // ── Clear Error ──────────────────────────────────────────────────────────

    test("clearError resets error state") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.startAdvertising() // triggers error
            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
