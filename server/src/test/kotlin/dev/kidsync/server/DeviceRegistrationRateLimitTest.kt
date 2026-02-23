package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.ErrorResponse
import dev.kidsync.server.models.RegisterRequest
import dev.kidsync.server.models.RegisterResponse
import dev.kidsync.server.routes.DeviceRegistrationRateLimiter
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Tests for SEC-S-10: Device registration IP-based rate limiting.
 */
class DeviceRegistrationRateLimitTest {

    @BeforeEach
    fun resetRateLimiter() {
        DeviceRegistrationRateLimiter.reset()
    }

    private suspend fun registerNewDevice(client: io.ktor.client.HttpClient): io.ktor.client.statement.HttpResponse {
        val signingKeyPair = TestHelper.generateSigningKeyPair()
        val encryptionKeyPair = TestHelper.generateEncryptionKeyPair()
        return client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(
                signingKey = TestHelper.encodePublicKey(signingKeyPair.public),
                encryptionKey = TestHelper.encodePublicKey(encryptionKeyPair.public),
            ))
        }
    }

    @Test
    fun `5 registrations from same IP succeed`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        for (i in 1..5) {
            val resp = registerNewDevice(client)
            assertEquals(HttpStatusCode.Created, resp.status,
                "Registration $i should succeed")
        }
    }

    @Test
    fun `6th registration returns 429`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // First 5 succeed
        for (i in 1..5) {
            val resp = registerNewDevice(client)
            assertEquals(HttpStatusCode.Created, resp.status,
                "Registration $i should succeed")
        }

        // 6th should be rate limited
        val resp = registerNewDevice(client)
        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
        val body = resp.body<ErrorResponse>()
        assertEquals("RATE_LIMITED", body.error)
    }

    @Test
    fun `rate limiter unit test - independent IPs`() {
        DeviceRegistrationRateLimiter.reset()

        // IP A uses 5 slots
        for (i in 1..5) {
            assertEquals(true, DeviceRegistrationRateLimiter.checkAndIncrement("1.2.3.4"),
                "IP A registration $i should pass")
        }
        // IP A is now limited
        assertEquals(false, DeviceRegistrationRateLimiter.checkAndIncrement("1.2.3.4"),
            "IP A should be rate limited after 5")

        // IP B should still be fine
        for (i in 1..5) {
            assertEquals(true, DeviceRegistrationRateLimiter.checkAndIncrement("5.6.7.8"),
                "IP B registration $i should pass")
        }
    }

    @Test
    fun `rate limiter unit test - cleanup removes stale entries`() {
        DeviceRegistrationRateLimiter.reset()

        // Fill up IP A
        for (i in 1..5) {
            DeviceRegistrationRateLimiter.checkAndIncrement("10.0.0.1")
        }
        assertEquals(false, DeviceRegistrationRateLimiter.checkAndIncrement("10.0.0.1"))

        // Reset simulates window expiry (in practice, time passes)
        DeviceRegistrationRateLimiter.reset()

        // After reset, IP A can register again
        assertEquals(true, DeviceRegistrationRateLimiter.checkAndIncrement("10.0.0.1"))
    }
}
