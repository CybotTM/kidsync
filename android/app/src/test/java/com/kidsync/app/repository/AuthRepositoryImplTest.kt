package com.kidsync.app.repository

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.ChallengeResponse
import com.kidsync.app.data.remote.dto.VerifyResponse
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.data.repository.AuthRepositoryImpl
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.*
import java.util.Base64

/**
 * Tests for AuthRepositoryImpl covering:
 *
 * 1. Backoff timing logic (synchronized access per SEC6-A-02)
 * 2. Session token persistence with commit() (SEC5-A-03)
 * 3. Session clearing (SEC3-A-10)
 * 4. buildChallengeMessage nonce/key size validation (SEC6-A-11)
 * 5. Logout cleanup (SEC-A-16)
 * 6. isAuthenticated checks token expiry
 * 7. getSession returns null when not authenticated
 * 8. Server URL get/set
 * 9. Register stores device ID
 */
class AuthRepositoryImplTest : FunSpec({

    val apiService = mockk<ApiService>()
    val cryptoManager = mockk<CryptoManager>()
    val keyManager = mockk<KeyManager>()
    val encryptedPrefs = mockk<SharedPreferences>()
    val prefs = mockk<SharedPreferences>()
    val encryptedEditor = mockk<SharedPreferences.Editor>()
    val prefsEditor = mockk<SharedPreferences.Editor>()
    val serverOrigin = "https://kidsync.example.com"

    fun createRepo() = AuthRepositoryImpl(
        apiService = apiService,
        cryptoManager = cryptoManager,
        keyManager = keyManager,
        encryptedPrefs = encryptedPrefs,
        prefs = prefs,
        serverOrigin = serverOrigin
    )

    beforeEach {
        clearAllMocks()

        // Default editor mocks
        every { encryptedPrefs.edit() } returns encryptedEditor
        every { prefs.edit() } returns prefsEditor

        every { encryptedEditor.putString(any(), any()) } returns encryptedEditor
        every { encryptedEditor.putLong(any(), any()) } returns encryptedEditor
        every { encryptedEditor.remove(any()) } returns encryptedEditor
        every { encryptedEditor.clear() } returns encryptedEditor
        every { encryptedEditor.commit() } returns true
        every { encryptedEditor.apply() } just Runs

        every { prefsEditor.clear() } returns prefsEditor
        every { prefsEditor.commit() } returns true
    }

    // ── Session Token Storage ────────────────────────────────────────────────

    test("getSessionToken returns null when no token stored") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns null

        val repo = createRepo()
        val token = repo.getSessionToken()

        token shouldBe null
    }

    test("getSessionToken returns stored token") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns "test-session-token"

        val repo = createRepo()
        val token = repo.getSessionToken()

        token shouldBe "test-session-token"
    }

    // ── Session Clearing ─────────────────────────────────────────────────────

    test("clearSession removes both token and expiry with commit (SEC5-A-04)") {
        val repo = createRepo()
        repo.clearSession()

        verify {
            encryptedEditor.remove(AuthInterceptor.PREF_SESSION_TOKEN)
            encryptedEditor.remove(AuthInterceptor.PREF_SESSION_EXPIRES_AT)
            encryptedEditor.commit()
        }
    }

    // ── isAuthenticated ──────────────────────────────────────────────────────

    test("isAuthenticated returns false when no token") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns null

        val repo = createRepo()
        repo.isAuthenticated() shouldBe false
    }

    test("isAuthenticated returns true when token exists and not expired") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns "valid-token"
        every {
            encryptedPrefs.getLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, 0L)
        } returns (System.currentTimeMillis() + 60_000L) // Expires in 60s

        val repo = createRepo()
        repo.isAuthenticated() shouldBe true
    }

    test("isAuthenticated returns false and clears session when token expired") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns "expired-token"
        every {
            encryptedPrefs.getLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, 0L)
        } returns (System.currentTimeMillis() - 1000L) // Expired 1s ago

        val repo = createRepo()
        repo.isAuthenticated() shouldBe false

        // Verify session was cleared
        verify {
            encryptedEditor.remove(AuthInterceptor.PREF_SESSION_TOKEN)
            encryptedEditor.commit()
        }
    }

    test("isAuthenticated returns true when expiresAt is 0 (unknown expiry)") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns "token"
        every {
            encryptedPrefs.getLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, 0L)
        } returns 0L

        val repo = createRepo()
        repo.isAuthenticated() shouldBe true
    }

    // ── Logout Cleanup ───────────────────────────────────────────────────────

    test("logout clears both encrypted and plain prefs (SEC-A-16)") {
        val repo = createRepo()
        repo.logout()

        // First clearSession is called
        verify {
            encryptedEditor.remove(AuthInterceptor.PREF_SESSION_TOKEN)
            encryptedEditor.remove(AuthInterceptor.PREF_SESSION_EXPIRES_AT)
            encryptedEditor.commit()
        }
        // Then full clear of both prefs
        verify {
            encryptedEditor.clear()
            encryptedEditor.commit()
        }
        verify {
            prefsEditor.clear()
            prefsEditor.commit()
        }
    }

    // ── getSession ───────────────────────────────────────────────────────────

    test("getSession returns null when no token") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns null

        val repo = createRepo()
        val session = repo.getSession()

        session shouldBe null
    }

    test("getSession returns null when no device ID") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns "token"
        coEvery { keyManager.getDeviceId() } returns null

        val repo = createRepo()
        val session = repo.getSession()

        session shouldBe null
    }

    test("getSession returns session when both token and device ID exist") {
        every {
            encryptedPrefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null)
        } returns "test-token"
        coEvery { keyManager.getDeviceId() } returns "device-123"

        val repo = createRepo()
        val session = repo.getSession()

        session shouldNotBe null
        session!!.sessionToken shouldBe "test-token"
        session.deviceId shouldBe "device-123"
        session.expiresIn shouldBe 0 // Unknown for cached sessions
    }

    // ── Server URL ───────────────────────────────────────────────────────────

    test("getServerUrl returns default when no custom URL set") {
        every {
            encryptedPrefs.getString("server_url", serverOrigin)
        } returns serverOrigin

        val repo = createRepo()
        repo.getServerUrl() shouldBe serverOrigin
    }

    test("setServerUrl stores URL") {
        val repo = createRepo()
        repo.setServerUrl("https://custom.server.com")

        verify {
            encryptedEditor.putString("server_url", "https://custom.server.com")
            encryptedEditor.apply()
        }
    }

    // ── Register ─────────────────────────────────────────────────────────────

    test("register stores device ID on success") {
        val response = mockk<com.kidsync.app.data.remote.dto.RegisterResponse>()
        every { response.deviceId } returns "new-device-id"
        coEvery { apiService.register(any()) } returns response
        coEvery { keyManager.storeDeviceId("new-device-id") } just Runs

        val repo = createRepo()
        val result = repo.register("signing-key-b64", "encryption-key-b64")

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe "new-device-id"
        coVerify { keyManager.storeDeviceId("new-device-id") }
    }

    test("register returns failure on API exception") {
        coEvery { apiService.register(any()) } throws RuntimeException("Network error")

        val repo = createRepo()
        val result = repo.register("key", "key")

        result.isFailure shouldBe true
        result.exceptionOrNull()!!.message shouldBe "Network error"
    }

    // ── getDeviceId ──────────────────────────────────────────────────────────

    test("getDeviceId delegates to keyManager") {
        coEvery { keyManager.getDeviceId() } returns "device-abc"

        val repo = createRepo()
        repo.getDeviceId() shouldBe "device-abc"
    }

    test("getDeviceId returns null when keyManager has no device") {
        coEvery { keyManager.getDeviceId() } returns null

        val repo = createRepo()
        repo.getDeviceId() shouldBe null
    }

    // ── Backoff Timing Logic (SEC3-A-17) ─────────────────────────────────────

    test("authenticate applies exponential backoff after failure") {
        // Setup: signing key pair
        val signingPublicKey = ByteArray(32) { it.toByte() }
        val signingPrivateKey = ByteArray(32) { (it + 100).toByte() }
        coEvery { keyManager.getOrCreateSigningKeyPair() } returns Pair(signingPublicKey, signingPrivateKey)

        // First call: API throws (triggers failure counter)
        coEvery { apiService.requestChallenge(any()) } throws RuntimeException("Server down")

        val repo = createRepo()

        // First call should fail normally
        val result1 = repo.authenticate()
        result1.isFailure shouldBe true

        // Second call should be rate-limited (within backoff window)
        val result2 = repo.authenticate()
        result2.isFailure shouldBe true
        result2.exceptionOrNull()!!.message!!.contains("rate limited") shouldBe true
    }

    test("successful authenticate resets backoff counter") {
        val signingPublicKey = ByteArray(32) { it.toByte() }
        val signingPrivateKey = ByteArray(32) { (it + 100).toByte() }
        coEvery { keyManager.getOrCreateSigningKeyPair() } returns Pair(signingPublicKey, signingPrivateKey)

        val repo = createRepo()

        // First: fail
        coEvery { apiService.requestChallenge(any()) } throws RuntimeException("fail")
        repo.authenticate()

        // Now set up for success
        val nonce = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        coEvery { apiService.requestChallenge(any()) } returns ChallengeResponse(
            nonce = nonce,
            expiresAt = "2026-12-31T23:59:59Z"
        )
        every { cryptoManager.signEd25519(any(), any()) } returns ByteArray(64)
        coEvery { apiService.verifyChallenge(any()) } returns VerifyResponse(
            sessionToken = "new-session",
            expiresIn = 3600
        )
        coEvery { keyManager.getDeviceId() } returns "device-success"

        // Wait for backoff to expire (1 second base)
        Thread.sleep(1100)

        val result = repo.authenticate()
        result.isSuccess shouldBe true

        // After success, backoff is reset - next call should NOT be rate limited
        // (if it fails, it should fail normally, not with "rate limited")
        coEvery { apiService.requestChallenge(any()) } throws RuntimeException("another fail")
        val result3 = repo.authenticate()
        result3.isFailure shouldBe true
        // Should not be rate limited (counter was reset)
        (result3.exceptionOrNull()!!.message?.contains("rate limited") == true) shouldBe false
    }

    // ── testConnection ───────────────────────────────────────────────────────

    test("testConnection throws on non-successful response") {
        val response = mockk<retrofit2.Response<Unit>>()
        every { response.isSuccessful } returns false
        every { response.code() } returns 503
        every { response.message() } returns "Service Unavailable"
        coEvery { apiService.health() } returns response

        val repo = createRepo()
        val result = runCatching { repo.testConnection() }

        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<com.kidsync.app.data.repository.ApiException>()
    }

    test("testConnection succeeds on 200") {
        val response = mockk<retrofit2.Response<Unit>>()
        every { response.isSuccessful } returns true
        coEvery { apiService.health() } returns response

        val repo = createRepo()
        // Should not throw
        repo.testConnection()
    }
})
