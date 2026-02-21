package com.kidsync.app.viewmodel

import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.auth.RecoveryUseCase
import com.kidsync.app.ui.viewmodel.AuthUiState
import com.kidsync.app.ui.viewmodel.AuthViewModel
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
import java.security.KeyPair
import java.security.KeyPairGenerator

/**
 * Tests for AuthViewModel covering:
 * - Initial state
 * - checkExistingSession finds existing session
 * - checkExistingSession handles no session
 * - setupDevice success flow
 * - setupDevice failure (registration error)
 * - authenticate success
 * - authenticate failure
 * - generateRecoveryPhrase success
 * - generateRecoveryPhrase failure (no bucket)
 * - restoreFromRecovery success
 * - restoreFromRecovery wrong word count
 * - restoreFromRecovery failure
 * - confirmRecoveryKeySaved clears words from memory (SEC-C3)
 * - logout resets state
 * - clearError
 * - onRecoveryPassphraseChanged / onRecoverySavedChecked
 * - onRecoveryInputWordChanged bounds check
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val authRepository = mockk<AuthRepository>(relaxed = true)
    val recoveryUseCase = mockk<RecoveryUseCase>(relaxed = true)
    val keyManager = mockk<KeyManager>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        // Default: no existing session
        coEvery { authRepository.getSession() } returns null
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): AuthViewModel {
        return AuthViewModel(authRepository, recoveryUseCase, keyManager, bucketRepository)
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    test("initial state is not authenticated and not loading") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isAuthenticated shouldBe false
            state.isLoading shouldBe false
            state.error shouldBe null
            state.deviceId shouldBe null
        }
    }

    // ── checkExistingSession ──────────────────────────────────────────────────

    test("checkExistingSession finds existing session and authenticates") {
        runTest(testDispatcher) {
            val session = DeviceSession("device-abc", "token-123", 3600)
            coEvery { authRepository.getSession() } returns session
            coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:abc"

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isAuthenticated shouldBe true
            state.deviceId shouldBe "device-abc"
            state.keyFingerprint shouldBe "fp:abc"
        }
    }

    test("checkExistingSession handles exception gracefully") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } throws RuntimeException("DB error")

            val vm = createViewModel()
            advanceUntilIdle()

            // Should not crash; stays unauthenticated
            val state = vm.uiState.value
            state.isAuthenticated shouldBe false
        }
    }

    // ── setupDevice ───────────────────────────────────────────────────────────

    test("setupDevice success sets isAuthenticated and deviceId") {
        runTest(testDispatcher) {
            val signingKeyPair = Pair(ByteArray(32) { 1 }, ByteArray(64) { 2 })
            coEvery { keyManager.getOrCreateSigningKeyPair() } returns signingKeyPair

            val kpg = KeyPairGenerator.getInstance("X25519")
            val encKp = kpg.generateKeyPair()
            coEvery { keyManager.getEncryptionKeyPair() } returns encKp

            coEvery { authRepository.register(any(), any()) } returns Result.success("device-xyz")
            coEvery { keyManager.storeDeviceId("device-xyz") } just Runs
            coEvery { authRepository.authenticate() } returns Result.success(
                DeviceSession("device-xyz", "token-abc", 3600)
            )
            coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:xyz"

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setupDevice()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isAuthenticated shouldBe true
            state.isKeyGenerated shouldBe true
            state.isRegisteredWithServer shouldBe true
            state.deviceId shouldBe "device-xyz"
            state.keyFingerprint shouldBe "fp:xyz"
            state.isLoading shouldBe false
            state.error shouldBe null
        }
    }

    test("setupDevice failure sets error message") {
        runTest(testDispatcher) {
            val signingKeyPair = Pair(ByteArray(32) { 1 }, ByteArray(64) { 2 })
            coEvery { keyManager.getOrCreateSigningKeyPair() } returns signingKeyPair

            val kpg = KeyPairGenerator.getInstance("X25519")
            val encKp = kpg.generateKeyPair()
            coEvery { keyManager.getEncryptionKeyPair() } returns encKp

            coEvery { authRepository.register(any(), any()) } returns Result.failure(
                RuntimeException("Server unreachable")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setupDevice()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isAuthenticated shouldBe false
            state.isLoading shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Server unreachable"
        }
    }

    // ── authenticate ──────────────────────────────────────────────────────────

    test("authenticate success sets isAuthenticated") {
        runTest(testDispatcher) {
            coEvery { authRepository.authenticate() } returns Result.success(
                DeviceSession("device-001", "tok", 3600)
            )
            coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:001"
            coEvery { keyManager.getDeviceId() } returns "device-001"

            val vm = createViewModel()
            advanceUntilIdle()

            vm.authenticate()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isAuthenticated shouldBe true
            state.deviceId shouldBe "device-001"
            state.isLoading shouldBe false
        }
    }

    test("authenticate failure sets error") {
        runTest(testDispatcher) {
            coEvery { authRepository.authenticate() } returns Result.failure(
                RuntimeException("Challenge failed")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.authenticate()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isAuthenticated shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Challenge failed"
        }
    }

    // ── generateRecoveryPhrase ────────────────────────────────────────────────

    test("generateRecoveryPhrase success populates recovery words") {
        runTest(testDispatcher) {
            val words = (1..24).map { "word$it" }
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
            coEvery { recoveryUseCase.generateRecoveryKey("bucket-1", "") } returns Result.success(words)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.generateRecoveryPhrase()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.recoveryWords.size shouldBe 24
            state.isLoading shouldBe false
        }
    }

    test("generateRecoveryPhrase fails when no bucket available") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            vm.generateRecoveryPhrase()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.error shouldNotBe null
            state.isLoading shouldBe false
        }
    }

    // ── restoreFromRecovery ───────────────────────────────────────────────────

    test("restoreFromRecovery rejects fewer than 24 words") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Only fill 12 words
            for (i in 0..11) {
                vm.onRecoveryInputWordChanged(i, "word$i")
            }
            vm.restoreFromRecovery()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.error shouldNotBe null
            state.error!! shouldContain "24"
        }
    }

    test("restoreFromRecovery success sets isRecoveryComplete and clears input (SEC-C3)") {
        runTest(testDispatcher) {
            val signingKeyPair = Pair(ByteArray(32) { 1 }, ByteArray(64) { 2 })
            coEvery { keyManager.getOrCreateSigningKeyPair() } returns signingKeyPair

            val kpg = KeyPairGenerator.getInstance("X25519")
            val encKp = kpg.generateKeyPair()
            coEvery { keyManager.getEncryptionKeyPair() } returns encKp

            coEvery { authRepository.register(any(), any()) } returns Result.success("device-rec")
            coEvery { keyManager.storeDeviceId("device-rec") } just Runs
            coEvery { authRepository.authenticate() } returns Result.success(
                DeviceSession("device-rec", "tok", 3600)
            )
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-rec")
            coEvery { recoveryUseCase.restoreFromRecovery(any(), "bucket-rec", "") } returns Result.success(Unit)
            coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:rec"

            val vm = createViewModel()
            advanceUntilIdle()

            // Fill all 24 words
            for (i in 0..23) {
                vm.onRecoveryInputWordChanged(i, "word$i")
            }
            vm.restoreFromRecovery()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isRecoveryComplete shouldBe true
            state.isAuthenticated shouldBe true
            // SEC-C3: input should be cleared
            state.recoveryInputWords.all { it.isEmpty() } shouldBe true
            state.recoveryInputPassphrase shouldBe ""
        }
    }

    test("restoreFromRecovery failure sets error") {
        runTest(testDispatcher) {
            val signingKeyPair = Pair(ByteArray(32) { 1 }, ByteArray(64) { 2 })
            coEvery { keyManager.getOrCreateSigningKeyPair() } returns signingKeyPair

            val kpg = KeyPairGenerator.getInstance("X25519")
            val encKp = kpg.generateKeyPair()
            coEvery { keyManager.getEncryptionKeyPair() } returns encKp

            coEvery { authRepository.register(any(), any()) } returns Result.failure(
                RuntimeException("Registration failed")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            for (i in 0..23) {
                vm.onRecoveryInputWordChanged(i, "word$i")
            }
            vm.restoreFromRecovery()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isRecoveryComplete shouldBe false
            state.error shouldNotBe null
        }
    }

    // ── confirmRecoveryKeySaved ───────────────────────────────────────────────

    test("confirmRecoveryKeySaved clears recovery words and passphrase (SEC-C3)") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Simulate that words are populated
            vm.onRecoveryPassphraseChanged("my-pass")
            vm.confirmRecoveryKeySaved()

            val state = vm.uiState.value
            state.recoveryWords shouldBe emptyList()
            state.recoveryPassphrase shouldBe ""
            state.hasSavedRecoveryKey shouldBe true
        }
    }

    // ── logout ────────────────────────────────────────────────────────────────

    test("logout resets to initial state") {
        runTest(testDispatcher) {
            val session = DeviceSession("device-abc", "token-123", 3600)
            coEvery { authRepository.getSession() } returns session
            coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:abc"

            val vm = createViewModel()
            advanceUntilIdle()
            vm.uiState.value.isAuthenticated shouldBe true

            coEvery { authRepository.logout() } just Runs

            vm.logout()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isAuthenticated shouldBe false
            state.deviceId shouldBe null
            state.keyFingerprint shouldBe null
        }
    }

    // ── UI Helpers ────────────────────────────────────────────────────────────

    test("clearError sets error to null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Force an error
            vm.authenticate()
            coEvery { authRepository.authenticate() } returns Result.failure(RuntimeException("err"))
            vm.authenticate()
            advanceUntilIdle()

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }

    test("onRecoveryPassphraseChanged updates passphrase and clears error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onRecoveryPassphraseChanged("my-passphrase")
            vm.uiState.value.recoveryPassphrase shouldBe "my-passphrase"
            vm.uiState.value.error shouldBe null
        }
    }

    test("onRecoverySavedChecked updates hasSavedRecoveryKey") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onRecoverySavedChecked(true)
            vm.uiState.value.hasSavedRecoveryKey shouldBe true

            vm.onRecoverySavedChecked(false)
            vm.uiState.value.hasSavedRecoveryKey shouldBe false
        }
    }

    test("onRecoveryInputWordChanged ignores out-of-bounds index") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onRecoveryInputWordChanged(24, "out-of-bounds") // index 24 is out of range
            vm.onRecoveryInputWordChanged(-1, "negative")

            // No crash, words remain empty
            vm.uiState.value.recoveryInputWords.all { it.isEmpty() } shouldBe true
        }
    }

    test("onRecoveryInputWordChanged updates specific word") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onRecoveryInputWordChanged(0, "abandon")
            vm.onRecoveryInputWordChanged(23, "zoo")

            vm.uiState.value.recoveryInputWords[0] shouldBe "abandon"
            vm.uiState.value.recoveryInputWords[23] shouldBe "zoo"
            vm.uiState.value.recoveryInputWords[1] shouldBe "" // unchanged
        }
    }
})
