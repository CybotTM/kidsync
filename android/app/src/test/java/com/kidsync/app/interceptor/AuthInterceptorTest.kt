package com.kidsync.app.interceptor

import android.content.SharedPreferences
import com.kidsync.app.data.remote.interceptor.AuthInterceptor
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class AuthInterceptorTest : FunSpec({

    val prefs = mockk<SharedPreferences>(relaxed = true)
    val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    val chain = mockk<Interceptor.Chain>()

    fun createInterceptor() = AuthInterceptor(prefs)

    fun mockChainForPath(path: String): Request {
        val request = Request.Builder()
            .url("https://api.example.com$path")
            .build()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            Response.Builder()
                .request(firstArg())
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody(null))
                .build()
        }
        return request
    }

    beforeEach {
        clearAllMocks()
        every { prefs.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns true
    }

    // ── Token Injection ─────────────────────────────────────────────────────

    test("adds Bearer token for authenticated endpoint") {
        mockChainForPath("/buckets/bucket-1/ops")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "valid-token"
        every { prefs.getLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, 0L) } returns Long.MAX_VALUE

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == "Bearer valid-token"
            })
        }
    }

    test("does not add token when no session token stored") {
        mockChainForPath("/buckets/bucket-1/ops")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns null

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == null
            })
        }
    }

    test("does not add blank token") {
        mockChainForPath("/buckets/bucket-1/ops")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "  "

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == null
            })
        }
    }

    // ── Unauthenticated Endpoints ───────────────────────────────────────────

    test("skips token for /register endpoint") {
        mockChainForPath("/register")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "should-not-appear"

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == null
            })
        }
    }

    test("skips token for /auth/challenge endpoint") {
        mockChainForPath("/auth/challenge")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "should-not-appear"

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == null
            })
        }
    }

    test("skips token for /auth/verify endpoint") {
        mockChainForPath("/auth/verify")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "should-not-appear"

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == null
            })
        }
    }

    test("skips token for /health endpoint") {
        mockChainForPath("/health")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "should-not-appear"

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == null
            })
        }
    }

    // ── Expired Session ─────────────────────────────────────────────────────

    test("clears expired session token and does not add header") {
        mockChainForPath("/buckets/bucket-1/ops")
        every { prefs.getString(AuthInterceptor.PREF_SESSION_TOKEN, null) } returns "expired-token"
        // Set expires_at to a time in the past
        every { prefs.getLong(AuthInterceptor.PREF_SESSION_EXPIRES_AT, 0L) } returns 1L

        val interceptor = createInterceptor()
        interceptor.intercept(chain)

        // Should clear the expired token using commit()
        verify {
            editor.remove(AuthInterceptor.PREF_SESSION_TOKEN)
            editor.remove(AuthInterceptor.PREF_SESSION_EXPIRES_AT)
            editor.commit()
        }

        // Should not add Authorization header
        verify {
            chain.proceed(match { request ->
                request.header("Authorization") == null
            })
        }
    }
})
