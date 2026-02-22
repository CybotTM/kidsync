package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for rate limiting behavior.
 * These test the per-signing-key challenge rate limiter (ChallengeKeyRateLimiter)
 * via the HTTP endpoint, since it's a private object.
 */
class RateLimiterTest {

    // ================================================================
    // ChallengeKeyRateLimiter -- tested via /auth/challenge endpoint
    // ================================================================

    @Test
    fun `challenge rate limiter allows 5 requests within a minute`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        // First 5 requests should succeed
        for (i in 1..5) {
            val response = client.post("/auth/challenge") {
                contentType(ContentType.Application.Json)
                setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
            }
            assertEquals(HttpStatusCode.OK, response.status, "Request $i should succeed")
        }
    }

    @Test
    fun `challenge rate limiter rejects 6th request within a minute`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        // Exhaust the 5-request limit
        for (i in 1..5) {
            val response = client.post("/auth/challenge") {
                contentType(ContentType.Application.Json)
                setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
            }
            assertEquals(HttpStatusCode.OK, response.status, "Request $i should succeed")
        }

        // 6th request should be rate limited
        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("RATE_LIMITED", body.error)
    }

    @Test
    fun `challenge rate limit is per-signing-key not global`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device1 = TestHelper.registerDevice(client)
        val device2 = TestHelper.registerDevice(client)

        // Exhaust device1's limit
        for (i in 1..5) {
            client.post("/auth/challenge") {
                contentType(ContentType.Application.Json)
                setBody(ChallengeRequest(signingKey = device1.signingKeyBase64))
            }
        }

        // Device2 should still be able to request challenges
        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device2.signingKeyBase64))
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `challenge for unregistered key returns 404 not 429`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // The key check happens before rate limiting, so unregistered keys get 404
        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = "unregistered-key-base64"))
        }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
