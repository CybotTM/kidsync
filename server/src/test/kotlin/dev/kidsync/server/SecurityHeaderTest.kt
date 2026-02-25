package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests that all security headers are present on HTTP responses.
 * SEC-S-11: X-Content-Type-Options, X-Frame-Options, Cache-Control
 * SEC2-S-12: HSTS
 * SEC5-S-21: Referrer-Policy, CSP, Permissions-Policy
 */
class SecurityHeaderTest {

    // ================================================================
    // Individual security header checks on /health
    // ================================================================

    @Test
    fun `X-Content-Type-Options header is nosniff`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
    }

    @Test
    fun `X-Frame-Options header is DENY`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals("DENY", response.headers["X-Frame-Options"])
    }

    @Test
    fun `Cache-Control header is no-store`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `Strict-Transport-Security header is present with correct value`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        val hsts = response.headers["Strict-Transport-Security"]
        assertNotNull(hsts, "HSTS header should be present")
        assertTrue(hsts.contains("max-age=63072000"), "HSTS max-age should be 63072000")
        assertTrue(hsts.contains("includeSubDomains"), "HSTS should include subdomains")
        assertTrue(hsts.contains("preload"), "HSTS should include preload")
    }

    @Test
    fun `Referrer-Policy header is no-referrer`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
    }

    @Test
    fun `Content-Security-Policy header is default-src none`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals("default-src 'none'", response.headers["Content-Security-Policy"])
    }

    @Test
    fun `Permissions-Policy header is present and empty`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        val pp = response.headers["Permissions-Policy"]
        assertNotNull(pp, "Permissions-Policy header should be present")
        assertEquals("", pp)
    }

    // ================================================================
    // Security headers on non-health endpoints
    // ================================================================

    @Test
    fun `security headers present on 404 response`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/nonexistent-path")
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-store", response.headers["Cache-Control"])
        assertNotNull(response.headers["Strict-Transport-Security"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
    }

    @Test
    fun `security headers present on authenticated endpoint`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.get("/buckets/${device.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
    }

    @Test
    fun `security headers present on 401 response`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/buckets/00000000-0000-0000-0000-000000000000/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer invalid-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-store", response.headers["Cache-Control"])
    }

    @Test
    fun `security headers present on register endpoint`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val signingKP = TestHelper.generateSigningKeyPair()
        val encryptionKP = TestHelper.generateEncryptionKeyPair()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            val signingKey = TestHelper.encodePublicKey(signingKP.public)
            val encryptionKey = TestHelper.encodePublicKey(encryptionKP.public)
            setBody("""{"signingKey": "$signingKey", "encryptionKey": "$encryptionKey"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("default-src 'none'", response.headers["Content-Security-Policy"])
    }
}
