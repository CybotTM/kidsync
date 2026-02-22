package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.encodePublicKey
import dev.kidsync.server.TestHelper.generateEncryptionKeyPair
import dev.kidsync.server.TestHelper.generateSigningKeyPair
import dev.kidsync.server.TestHelper.signChallenge
import dev.kidsync.server.models.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for auth flow, timestamp drift, and error codes.
 */
class AuthIntegrationTest {

    // ================================================================
    // Full Registration -> Challenge -> Verify -> Authenticated Request
    // ================================================================

    @Test
    fun `full auth flow registers authenticates and accesses protected resource`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Step 1: Register
        val signingKP = generateSigningKeyPair()
        val encryptionKP = generateEncryptionKeyPair()
        val signingKey = encodePublicKey(signingKP.public)
        val encryptionKey = encodePublicKey(encryptionKP.public)

        val regResp = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(signingKey = signingKey, encryptionKey = encryptionKey))
        }
        assertEquals(HttpStatusCode.Created, regResp.status)
        val deviceId = regResp.body<RegisterResponse>().deviceId
        assertTrue(deviceId.isNotEmpty())

        // Step 2: Challenge
        val challResp = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = signingKey))
        }
        assertEquals(HttpStatusCode.OK, challResp.status)
        val challenge = challResp.body<ChallengeResponse>()
        assertNotNull(challenge.nonce)

        // Step 3: Verify
        val (sig, ts) = signChallenge(
            privateKey = signingKP.private,
            nonce = challenge.nonce,
            signingKeyBase64 = signingKey,
        )
        val verifyResp = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = signingKey,
                nonce = challenge.nonce,
                signature = sig,
                timestamp = ts,
            ))
        }
        assertEquals(HttpStatusCode.OK, verifyResp.status)
        val session = verifyResp.body<VerifyResponse>()
        assertTrue(session.sessionToken.isNotEmpty())

        // Step 4: Access protected resource
        val bucketResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, bucketResp.status)
    }

    // ================================================================
    // Timestamp Drift
    // ================================================================

    @Test
    fun `verify with timestamp too far in future returns TIMESTAMP_DRIFT`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Timestamp 120 seconds in the future
        val futureTimestamp = Instant.now().plusSeconds(120).toString()
        val (sig, _) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
            timestamp = futureTimestamp,
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = sig,
                timestamp = futureTimestamp,
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("TIMESTAMP_DRIFT", body.error)
    }

    @Test
    fun `verify with timestamp too far in past returns TIMESTAMP_DRIFT`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Timestamp 120 seconds in the past
        val pastTimestamp = Instant.now().minusSeconds(120).toString()
        val (sig, _) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
            timestamp = pastTimestamp,
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = sig,
                timestamp = pastTimestamp,
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("TIMESTAMP_DRIFT", body.error)
    }

    @Test
    fun `verify with invalid timestamp format returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = "dummy-sig",
                timestamp = "not-a-timestamp",
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Unknown Signing Key
    // ================================================================

    @Test
    fun `challenge with unknown signing key returns UNKNOWN_SIGNING_KEY`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val unknownKey = encodePublicKey(generateSigningKeyPair().public)
        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = unknownKey))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("UNKNOWN_SIGNING_KEY", body.error)
    }

    // ================================================================
    // Multiple Sessions
    // ================================================================

    @Test
    fun `re-authentication invalidates previous sessions`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val session1 = TestHelper.authenticateDevice(client, device)
        val session2 = TestHelper.authenticateDevice(client, device)

        // SEC5-S-01: Re-authentication invalidates previous sessions.
        // session1 should be invalidated after session2 is created.
        val resp1 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session1.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Unauthorized, resp1.status)

        // session2 (latest) should work
        val resp2 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session2.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, resp2.status)
    }

    // ================================================================
    // Edge Cases
    // ================================================================

    @Test
    fun `verify with empty signing key returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = "",
                nonce = "some-nonce",
                signature = "some-sig",
                timestamp = Instant.now().toString(),
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `challenge with blank signing key returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
