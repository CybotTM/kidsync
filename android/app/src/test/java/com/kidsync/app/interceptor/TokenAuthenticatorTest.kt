package com.kidsync.app.interceptor

import android.content.SharedPreferences
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import com.kidsync.app.data.remote.interceptor.TokenAuthenticator
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.repository.AuthRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class TokenAuthenticatorTest : FunSpec({

    val authRepository = mockk<AuthRepository>()
    val lazyAuthRepository = mockk<dagger.Lazy<AuthRepository>>()
    val prefs = mockk<SharedPreferences>(relaxed = true)

    beforeEach {
        clearAllMocks()
        every { lazyAuthRepository.get() } returns authRepository
    }

    fun createAuthenticator(): TokenAuthenticator {
        return TokenAuthenticator(lazyAuthRepository, prefs)
    }

    fun build401Response(request: Request): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_2)
            .code(401)
            .message("Unauthorized")
            .body("".toResponseBody())
            .build()
    }

    // ── Basic token refresh ─────────────────────────────────────────────

    test("401 response triggers re-authentication and retries with new token") {
        val request = Request.Builder()
            .url("https://api.example.com/buckets")
            .header("Authorization", "Bearer old-token")
            .build()
        val response = build401Response(request)

        // Current token in prefs matches the request token (no other thread refreshed)
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "old-token"

        // Auth succeeds with new token
        coEvery { authRepository.authenticate() } returns Result.success(
            DeviceSession("device-1", "new-token", 3600)
        )

        val authenticator = createAuthenticator()
        val retryRequest = authenticator.authenticate(null, response)

        retryRequest shouldNotBe null
        retryRequest!!.header("Authorization") shouldBe "Bearer new-token"
        retryRequest.header(TokenAuthenticator.HEADER_AUTH_RETRY) shouldBe "true"
    }

    // ── Infinite loop prevention ────────────────────────────────────────

    test("second 401 with X-Auth-Retry header returns null to prevent infinite loop") {
        val request = Request.Builder()
            .url("https://api.example.com/buckets")
            .header("Authorization", "Bearer some-token")
            .header(TokenAuthenticator.HEADER_AUTH_RETRY, "true")
            .build()
        val response = build401Response(request)

        val authenticator = createAuthenticator()
        val retryRequest = authenticator.authenticate(null, response)

        retryRequest shouldBe null
        // authenticate() should never be called
        coVerify(exactly = 0) { authRepository.authenticate() }
    }

    // ── Token already refreshed by another thread ───────────────────────

    test("skips re-auth when another thread already refreshed the token") {
        val request = Request.Builder()
            .url("https://api.example.com/buckets")
            .header("Authorization", "Bearer old-token")
            .build()
        val response = build401Response(request)

        // Prefs now have a different (newer) token than what the request sent
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "already-refreshed-token"

        val authenticator = createAuthenticator()
        val retryRequest = authenticator.authenticate(null, response)

        retryRequest shouldNotBe null
        retryRequest!!.header("Authorization") shouldBe "Bearer already-refreshed-token"
        retryRequest.header(TokenAuthenticator.HEADER_AUTH_RETRY) shouldBe "true"

        // authenticate() should not be called since token was already refreshed
        coVerify(exactly = 0) { authRepository.authenticate() }
    }

    // ── Auth failure returns null ────────────────────────────────────────

    test("returns null when re-authentication fails") {
        val request = Request.Builder()
            .url("https://api.example.com/buckets")
            .header("Authorization", "Bearer expired-token")
            .build()
        val response = build401Response(request)

        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "expired-token"
        coEvery { authRepository.authenticate() } returns Result.failure(
            RuntimeException("Network error")
        )

        val authenticator = createAuthenticator()
        val retryRequest = authenticator.authenticate(null, response)

        retryRequest shouldBe null
    }

    // ── Request without Authorization header ────────────────────────────

    test("handles request without Authorization header") {
        val request = Request.Builder()
            .url("https://api.example.com/buckets")
            .build()
        val response = build401Response(request)

        // No current token in prefs either
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns null

        coEvery { authRepository.authenticate() } returns Result.success(
            DeviceSession("device-1", "fresh-token", 3600)
        )

        val authenticator = createAuthenticator()
        val retryRequest = authenticator.authenticate(null, response)

        retryRequest shouldNotBe null
        retryRequest!!.header("Authorization") shouldBe "Bearer fresh-token"
    }

    // ── Concurrent 401s result in single auth call ──────────────────────

    test("concurrent 401s result in single authentication call") {
        val authenticator = createAuthenticator()
        val authCallCount = AtomicInteger(0)

        // First call to prefs returns the old token (both threads see same stale token)
        // After auth, subsequent calls return new token
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returnsMany listOf(
            "old-token",  // first thread sees old token
            "new-token",  // second thread sees new token (first already refreshed)
            "new-token"   // any subsequent calls
        )

        coEvery { authRepository.authenticate() } answers {
            authCallCount.incrementAndGet()
            Result.success(DeviceSession("device-1", "new-token", 3600))
        }

        val request = Request.Builder()
            .url("https://api.example.com/buckets")
            .header("Authorization", "Bearer old-token")
            .build()

        val latch = CountDownLatch(2)
        val results = arrayOfNulls<Request>(2)

        // Launch two threads that hit authenticate concurrently
        val t1 = Thread {
            results[0] = authenticator.authenticate(null, build401Response(request))
            latch.countDown()
        }
        val t2 = Thread {
            results[1] = authenticator.authenticate(null, build401Response(request))
            latch.countDown()
        }

        t1.start()
        t2.start()
        latch.await()

        // Both should get valid retry requests
        results[0] shouldNotBe null
        results[1] shouldNotBe null

        // Only one authentication call should have been made
        // (the second thread sees that the token was already refreshed)
        authCallCount.get() shouldBe 1
    }
})
