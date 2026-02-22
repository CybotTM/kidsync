package com.kidsync.app.viewmodel

import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.ui.viewmodel.DashboardViewModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant

/**
 * Tests for DashboardViewModel covering:
 * - Initial state defaults
 * - loadBucketInfo populates bucket info on success
 * - loadBucketInfo handles no buckets
 * - loadBucketInfo sets hasCoParent when multiple devices
 * - loadBucketInfo handles exception gracefully
 * - loadBucketInfo uses default name when local name is null
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val authRepository = mockk<AuthRepository>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
    }

    afterEach {
        Dispatchers.resetMain()
    }

    // ── Initial State / loadBucketInfo ─────────────────────────────────────────

    test("initial state has null bucket when no buckets available") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = DashboardViewModel(authRepository, bucketRepository)
            advanceUntilIdle()

            val state = vm.uiState.value
            state.currentBucketId shouldBe null
            state.bucketCount shouldBe 0
            state.hasCoParent shouldBe false
        }
    }

    test("loadBucketInfo populates bucket info on success") {
        runTest(testDispatcher) {
            val device1 = Device(
                deviceId = "device-1",
                signingKey = "sk1",
                encryptionKey = "ek1",
                createdAt = Instant.now()
            )
            val device2 = Device(
                deviceId = "device-2",
                signingKey = "sk2",
                encryptionKey = "ek2",
                createdAt = Instant.now()
            )

            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1", "bucket-2")
            coEvery { bucketRepository.getLocalBucketName("bucket-1") } returns "Family Bucket"
            coEvery { bucketRepository.getBucketDevices("bucket-1") } returns Result.success(listOf(device1, device2))

            val vm = DashboardViewModel(authRepository, bucketRepository)
            advanceUntilIdle()

            val state = vm.uiState.value
            state.currentBucketId shouldBe "bucket-1"
            state.currentBucketName shouldBe "Family Bucket"
            state.bucketCount shouldBe 2
            state.hasCoParent shouldBe true
        }
    }

    test("loadBucketInfo sets hasCoParent false when single device") {
        runTest(testDispatcher) {
            val device1 = Device(
                deviceId = "device-1",
                signingKey = "sk1",
                encryptionKey = "ek1",
                createdAt = Instant.now()
            )

            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
            coEvery { bucketRepository.getLocalBucketName("bucket-1") } returns "My Bucket"
            coEvery { bucketRepository.getBucketDevices("bucket-1") } returns Result.success(listOf(device1))

            val vm = DashboardViewModel(authRepository, bucketRepository)
            advanceUntilIdle()

            val state = vm.uiState.value
            state.currentBucketId shouldBe "bucket-1"
            state.hasCoParent shouldBe false
        }
    }

    test("loadBucketInfo uses default name when local name is null") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
            coEvery { bucketRepository.getLocalBucketName("bucket-1") } returns null
            coEvery { bucketRepository.getBucketDevices("bucket-1") } returns Result.success(emptyList())

            val vm = DashboardViewModel(authRepository, bucketRepository)
            advanceUntilIdle()

            val state = vm.uiState.value
            state.currentBucketName shouldBe "My Bucket"
        }
    }

    test("loadBucketInfo handles exception gracefully without crash") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } throws RuntimeException("DB error")

            val vm = DashboardViewModel(authRepository, bucketRepository)
            advanceUntilIdle()

            // Should not crash; state stays at defaults
            val state = vm.uiState.value
            state.currentBucketId shouldBe null
            state.bucketCount shouldBe 0
        }
    }

    test("loadBucketInfo handles getBucketDevices failure gracefully") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
            coEvery { bucketRepository.getLocalBucketName("bucket-1") } returns "Test"
            coEvery { bucketRepository.getBucketDevices("bucket-1") } returns Result.failure(RuntimeException("err"))

            val vm = DashboardViewModel(authRepository, bucketRepository)
            advanceUntilIdle()

            val state = vm.uiState.value
            state.currentBucketId shouldBe "bucket-1"
            // getOrDefault(emptyList()) on failure means 0 devices
            state.hasCoParent shouldBe false
        }
    }
})
