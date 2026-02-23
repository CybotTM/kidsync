package com.kidsync.app.viewmodel

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.Bucket
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.model.KeyAttestation
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.data.remote.dto.WrappedKeyResponse
import com.kidsync.app.ui.viewmodel.BucketViewModel
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
import java.security.KeyPairGenerator
import java.time.Instant

/**
 * Tests for BucketViewModel covering:
 * - createBucket success
 * - createBucket failure
 * - generateInvite success
 * - generateInvite with no bucket selected
 * - joinBucket success flow
 * - joinBucket peer verification failure
 * - leaveBucket success
 * - leaveBucket removes from list
 * - loadBuckets success
 * - loadBuckets empty list
 * - loadBuckets error
 * - onLocalBucketNameChanged
 * - onInviteCopied
 * - clearError
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BucketViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val bucketRepository = mockk<BucketRepository>(relaxed = true)
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val cryptoManager = mockk<CryptoManager>(relaxed = true)
    val keyManager = mockk<KeyManager>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): BucketViewModel {
        return BucketViewModel(bucketRepository, authRepository, cryptoManager, keyManager)
    }

    // ── createBucket ──────────────────────────────────────────────────────────

    test("createBucket success updates state with new bucket") {
        runTest(testDispatcher) {
            val bucket = Bucket("bucket-new", "device-001", Instant.now())
            coEvery { bucketRepository.createBucket() } returns Result.success(bucket)
            coEvery { bucketRepository.storeLocalBucketName("bucket-new", any()) } just Runs
            coEvery { cryptoManager.generateAndStoreDek("bucket-new") } just Runs

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onLocalBucketNameChanged("Family")
            vm.createBucket()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isBucketCreated shouldBe true
            state.currentBucket shouldNotBe null
            state.currentBucket!!.bucketId shouldBe "bucket-new"
            state.currentBucket!!.localName shouldBe "Family"
            state.isLoading shouldBe false
        }
    }

    test("createBucket with blank name defaults to 'My Bucket'") {
        runTest(testDispatcher) {
            val bucket = Bucket("bucket-new", "device-001", Instant.now())
            coEvery { bucketRepository.createBucket() } returns Result.success(bucket)
            coEvery { bucketRepository.storeLocalBucketName(any(), any()) } just Runs
            coEvery { cryptoManager.generateAndStoreDek(any()) } just Runs

            val vm = createViewModel()
            advanceUntilIdle()

            // Don't set a name - should default
            vm.createBucket()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.currentBucket!!.localName shouldBe "My Bucket"
        }
    }

    test("createBucket failure sets error") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.createBucket() } returns Result.failure(
                RuntimeException("Network error")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.createBucket()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isBucketCreated shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Network error"
        }
    }

    // ── generateInvite ────────────────────────────────────────────────────────

    test("generateInvite with no bucket selected sets error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // No currentBucket set
            vm.generateInvite()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.error shouldNotBe null
            state.error!! shouldContain "No bucket"
        }
    }

    test("generateInvite success populates QR payload") {
        runTest(testDispatcher) {
            // First create a bucket so we have a currentBucket
            val bucket = Bucket("bucket-inv", "device-001", Instant.now())
            coEvery { bucketRepository.createBucket() } returns Result.success(bucket)
            coEvery { bucketRepository.storeLocalBucketName(any(), any()) } just Runs
            coEvery { cryptoManager.generateAndStoreDek(any()) } just Runs

            coEvery { cryptoManager.generateInviteToken() } returns "tok-abc-123"
            coEvery { bucketRepository.createInvite("bucket-inv", "tok-abc-123") } returns Result.success(Unit)
            coEvery { authRepository.getServerUrl() } returns "https://api.kidsync.dev"
            coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:sign:001"

            val vm = createViewModel()
            advanceUntilIdle()

            vm.createBucket()
            advanceUntilIdle()

            vm.generateInvite()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.qrPayload shouldNotBe null
            state.qrPayload!! shouldContain "bucket-inv"
            state.qrPayload!! shouldContain "tok-abc-123"
            state.inviteToken shouldBe "tok-abc-123"
            state.isLoading shouldBe false
        }
    }

    // ── leaveBucket ───────────────────────────────────────────────────────────

    test("leaveBucket removes bucket from list") {
        runTest(testDispatcher) {
            // Setup: create a bucket first
            val bucket = Bucket("bucket-leave", "device-001", Instant.now())
            coEvery { bucketRepository.createBucket() } returns Result.success(bucket)
            coEvery { bucketRepository.storeLocalBucketName(any(), any()) } just Runs
            coEvery { cryptoManager.generateAndStoreDek(any()) } just Runs
            coEvery { bucketRepository.leaveBucket("bucket-leave") } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.createBucket()
            advanceUntilIdle()
            vm.uiState.value.buckets.size shouldBe 1

            vm.leaveBucket("bucket-leave")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.buckets.size shouldBe 0
            state.currentBucket shouldBe null
            state.isLoading shouldBe false
        }
    }

    test("leaveBucket failure sets error") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.leaveBucket(any()) } throws RuntimeException("Forbidden")

            val vm = createViewModel()
            advanceUntilIdle()

            vm.leaveBucket("bucket-xyz")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.error shouldNotBe null
        }
    }

    // ── loadBuckets ───────────────────────────────────────────────────────────

    test("loadBuckets success populates bucket list") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("b1", "b2")
            coEvery { bucketRepository.getLocalBucketName("b1") } returns "Family"
            coEvery { bucketRepository.getLocalBucketName("b2") } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            vm.loadBuckets()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.buckets.size shouldBe 2
            state.buckets[0].localName shouldBe "Family"
            state.buckets[1].localName shouldBe "Bucket" // default name
            state.currentBucket shouldBe state.buckets[0]
            state.isLoading shouldBe false
        }
    }

    test("loadBuckets with empty list") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            vm.loadBuckets()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.buckets shouldBe emptyList()
            state.currentBucket shouldBe null
        }
    }

    test("loadBuckets failure sets error") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } throws RuntimeException("DB error")

            val vm = createViewModel()
            advanceUntilIdle()

            vm.loadBuckets()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.error shouldNotBe null
        }
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    test("onLocalBucketNameChanged updates name and clears error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onLocalBucketNameChanged("New Name")
            vm.uiState.value.localBucketName shouldBe "New Name"
            vm.uiState.value.error shouldBe null
        }
    }

    test("onInviteCopied sets flag") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onInviteCopied()
            vm.uiState.value.isInviteCopied shouldBe true
        }
    }

    test("clearError resets error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.generateInvite() // triggers error (no bucket)
            advanceUntilIdle()
            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }

    // ── SC-03: QR signing key fingerprint ─────────────────────────────────

    test("generateInvite uses signing key fingerprint, not encryption key") {
        runTest(testDispatcher) {
            val bucket = Bucket("bucket-sc03", "device-001", Instant.now())
            coEvery { bucketRepository.createBucket() } returns Result.success(bucket)
            coEvery { bucketRepository.storeLocalBucketName(any(), any()) } just Runs
            coEvery { cryptoManager.generateAndStoreDek(any()) } just Runs

            coEvery { cryptoManager.generateInviteToken() } returns "tok-sc03"
            coEvery { bucketRepository.createInvite("bucket-sc03", "tok-sc03") } returns Result.success(Unit)
            coEvery { authRepository.getServerUrl() } returns "https://api.kidsync.dev"
            coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:signing:abc123"

            val vm = createViewModel()
            advanceUntilIdle()

            vm.createBucket()
            advanceUntilIdle()
            vm.generateInvite()
            advanceUntilIdle()

            // Verify signing key fingerprint was used
            coVerify { keyManager.getSigningKeyFingerprint() }
            coVerify(exactly = 0) { keyManager.getEncryptionKeyFingerprint() }

            // Verify fingerprint appears in QR payload
            val state = vm.uiState.value
            state.qrPayload shouldNotBe null
            state.qrPayload!! shouldContain "fp:signing:abc123"
        }
    }

    test("continueJoinBucket compares fingerprint against signing key, not encryption key") {
        runTest(testDispatcher) {
            val signingFingerprint = "fp:signing:peer001"
            val peerDevice = Device(
                deviceId = "peer-device-1",
                signingKey = "cGVlci1zaWduaW5nLWtleQ==",  // base64 for test
                encryptionKey = "cGVlci1lbmMta2V5",
                createdAt = Instant.now()
            )

            coEvery { keyManager.hasExistingKeys() } returns true
            coEvery { authRepository.authenticate() } returns Result.success(
                com.kidsync.app.domain.model.DeviceSession("device-join", "token-123", 3600)
            )
            coEvery { bucketRepository.joinBucket("bucket-join", "tok-join") } returns Result.success(Unit)
            coEvery { bucketRepository.getBucketDevices("bucket-join") } returns Result.success(listOf(peerDevice))
            // Return matching fingerprint for signing key, different for encryption key
            every { cryptoManager.computeKeyFingerprint(peerDevice.signingKey) } returns signingFingerprint
            every { cryptoManager.computeKeyFingerprint(peerDevice.encryptionKey) } returns "fp:enc:different"

            val encKeyPair = KeyPairGenerator.getInstance("X25519").generateKeyPair()
            coEvery { keyManager.getEncryptionKeyPair() } returns encKeyPair

            coEvery { bucketRepository.waitForWrappedDek("bucket-join") } returns WrappedKeyResponse(
                wrappedDek = "wrapped-dek-base64", wrappedBy = "sender-pub-key", keyEpoch = 1
            )
            coEvery { cryptoManager.unwrapAndStoreDek(any(), any(), any(), any()) } just Runs
            coEvery { keyManager.createKeyAttestation(any(), any()) } returns KeyAttestation(
                signerDeviceId = "device-join",
                attestedDeviceId = "peer-device-1",
                attestedEncryptionKey = "key",
                signature = "sig",
                createdAt = Instant.now().toString()
            )
            coEvery { bucketRepository.uploadKeyAttestation(any()) } returns Result.success(Unit)
            coEvery { bucketRepository.storeLocalBucketName(any(), any()) } just Runs
            coEvery { bucketRepository.getLocalBucketName(any()) } returns "Shared Bucket"
            coEvery { authRepository.getServerUrl() } returns "https://api.kidsync.dev"

            val vm = createViewModel()
            advanceUntilIdle()

            // Build QR payload with signing key fingerprint
            val qrData = """{"v":1,"s":"https://api.kidsync.dev","b":"bucket-join","t":"tok-join","f":"$signingFingerprint"}"""
            vm.joinBucket(qrData)
            advanceUntilIdle()

            // Should have verified against signing key, not encryption key
            verify { cryptoManager.computeKeyFingerprint(peerDevice.signingKey) }

            val state = vm.uiState.value
            state.isJoined shouldBe true
            state.error shouldBe null
        }
    }
})
