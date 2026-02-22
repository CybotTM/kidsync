package com.kidsync.app.viewmodel

import android.content.SharedPreferences
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.ui.viewmodel.SettingsViewModel
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
import java.time.Instant

/**
 * Tests for SettingsViewModel covering:
 * - Initial state loads device info, preferences, and bucket list
 * - Server URL validation (blank)
 * - Server URL save
 * - onServerUrlChanged updates state and clears connection status
 * - testConnection success
 * - testConnection failure
 * - loadDevices success
 * - loadDevices failure (no bucket)
 * - loadDevices failure (exception)
 * - leaveBucket success removes bucket from list
 * - leaveBucket failure sets error
 * - Preference changes (notifications, currency, split ratio)
 * - logout delegates to auth repository
 * - clearError
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val bucketRepository = mockk<BucketRepository>(relaxed = true)
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val keyManager = mockk<KeyManager>(relaxed = true)
    val prefs = mockk<SharedPreferences>(relaxed = true)
    val prefsEditor = mockk<SharedPreferences.Editor>(relaxed = true)
    val encryptedPrefs = mockk<SharedPreferences>(relaxed = true)
    val encryptedPrefsEditor = mockk<SharedPreferences.Editor>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()

        every { prefs.edit() } returns prefsEditor
        every { prefsEditor.putBoolean(any(), any()) } returns prefsEditor
        every { prefsEditor.putString(any(), any()) } returns prefsEditor
        every { prefsEditor.putFloat(any(), any()) } returns prefsEditor
        every { prefsEditor.apply() } just Runs

        every { encryptedPrefs.edit() } returns encryptedPrefsEditor
        every { encryptedPrefsEditor.putString(any(), any()) } returns encryptedPrefsEditor
        every { encryptedPrefsEditor.apply() } just Runs

        // Default prefs values
        every { encryptedPrefs.getString("server_url", any()) } returns "https://api.example.com/v1/"
        every { prefs.getBoolean("notifications_enabled", true) } returns true
        every { prefs.getString("default_currency", "EUR") } returns "EUR"
        every { prefs.getFloat("default_split_ratio", 0.5f) } returns 0.5f

        coEvery { keyManager.getSigningKeyFingerprint() } returns "fp:test-123"
        coEvery { keyManager.getDeviceId() } returns "device-001"
        coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
        coEvery { bucketRepository.getLocalBucketName("bucket-1") } returns "Family"
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(bucketRepository, authRepository, keyManager, prefs, encryptedPrefs)
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    test("initial state loads device info") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.keyFingerprint shouldBe "fp:test-123"
            state.deviceId shouldBe "device-001"
        }
    }

    test("initial state loads preferences") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.serverUrl shouldBe "https://api.example.com/v1/"
            state.notificationsEnabled shouldBe true
            state.defaultCurrency shouldBe "EUR"
            state.defaultSplitRatio shouldBe 0.5
        }
    }

    test("initial state loads bucket list") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.buckets.size shouldBe 1
            state.buckets[0].bucketId shouldBe "bucket-1"
            state.buckets[0].localName shouldBe "Family"
        }
    }

    test("initial state handles device info exception gracefully") {
        runTest(testDispatcher) {
            coEvery { keyManager.getSigningKeyFingerprint() } throws RuntimeException("No keys")

            val vm = createViewModel()
            advanceUntilIdle()

            // Should not crash; fingerprint stays empty
            val state = vm.uiState.value
            state.keyFingerprint shouldBe ""
        }
    }

    // ── Server URL ────────────────────────────────────────────────────────────

    test("onServerUrlChanged updates URL and clears connection status") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onServerUrlChanged("https://new-server.com/")

            val state = vm.uiState.value
            state.serverUrl shouldBe "https://new-server.com/"
            state.isServerConnected shouldBe null
            state.error shouldBe null
        }
    }

    test("saveServerUrl sets error when URL is blank") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onServerUrlChanged("   ")
            vm.saveServerUrl()

            vm.uiState.value.error shouldBe "Server URL is required"
        }
    }

    test("saveServerUrl persists URL to encrypted prefs") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onServerUrlChanged("https://my-server.com/api")
            vm.saveServerUrl()

            verify { encryptedPrefsEditor.putString("server_url", "https://my-server.com/api") }
            verify { encryptedPrefsEditor.apply() }
        }
    }

    // ── Test Connection ───────────────────────────────────────────────────────

    test("testConnection success sets isServerConnected true") {
        runTest(testDispatcher) {
            coEvery { authRepository.testConnection() } just Runs

            val vm = createViewModel()
            advanceUntilIdle()

            vm.testConnection()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isTestingConnection shouldBe false
            state.isServerConnected shouldBe true
            state.error shouldBe null
        }
    }

    test("testConnection failure sets isServerConnected false and error") {
        runTest(testDispatcher) {
            coEvery { authRepository.testConnection() } throws RuntimeException("Connection refused")

            val vm = createViewModel()
            advanceUntilIdle()

            vm.testConnection()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isTestingConnection shouldBe false
            state.isServerConnected shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Connection refused"
        }
    }

    // ── Load Devices ──────────────────────────────────────────────────────────

    test("loadDevices success populates devices list") {
        runTest(testDispatcher) {
            val devices = listOf(
                Device(
                    deviceId = "device-1", signingKey = "sk1", encryptionKey = "ek1",
                    createdAt = Instant.now(), name = "Phone"
                ),
                Device(
                    deviceId = "device-2", signingKey = "sk2", encryptionKey = "ek2",
                    createdAt = Instant.now(), name = "Tablet"
                )
            )
            coEvery { bucketRepository.getBucketDevices("bucket-1") } returns Result.success(devices)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.loadDevices()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoadingDevices shouldBe false
            state.devices.size shouldBe 2
        }
    }

    test("loadDevices failure sets error") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getBucketDevices("bucket-1") } returns
                Result.failure(RuntimeException("Network error"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.loadDevices()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoadingDevices shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Network error"
        }
    }

    test("loadDevices sets error when no bucket available") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            vm.loadDevices()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "No bucket selected"
        }
    }

    // ── Leave Bucket ──────────────────────────────────────────────────────────

    test("leaveBucket success removes bucket from list") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.leaveBucket("bucket-1") } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.buckets.size shouldBe 1

            vm.leaveBucket("bucket-1")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.buckets.size shouldBe 0
        }
    }

    test("leaveBucket failure sets error") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.leaveBucket("bucket-1") } throws RuntimeException("Permission denied")

            val vm = createViewModel()
            advanceUntilIdle()

            vm.leaveBucket("bucket-1")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Permission denied"
        }
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    test("onNotificationsEnabledChanged persists and updates state") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onNotificationsEnabledChanged(false)

            verify { prefsEditor.putBoolean("notifications_enabled", false) }
            vm.uiState.value.notificationsEnabled shouldBe false
        }
    }

    test("onDefaultCurrencyChanged persists and updates state") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onDefaultCurrencyChanged("USD")

            verify { prefsEditor.putString("default_currency", "USD") }
            vm.uiState.value.defaultCurrency shouldBe "USD"
        }
    }

    test("onDefaultSplitRatioChanged persists and updates state") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onDefaultSplitRatioChanged(0.7)

            verify { prefsEditor.putFloat("default_split_ratio", 0.7f) }
            vm.uiState.value.defaultSplitRatio shouldBe 0.7
        }
    }

    // ── Logout ────────────────────────────────────────────────────────────────

    test("logout delegates to authRepository") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.logout()
            advanceUntilIdle()

            coVerify { authRepository.logout() }
        }
    }

    // ── clearError ────────────────────────────────────────────────────────────

    test("clearError sets error to null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onServerUrlChanged("")
            vm.saveServerUrl()
            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
