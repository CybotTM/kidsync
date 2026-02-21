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

class AuthTest {

    // ================================================================
    // Device Registration
    // ================================================================

    @Test
    fun `register with valid keys returns deviceId`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val signingKP = generateSigningKeyPair()
        val encryptionKP = generateEncryptionKeyPair()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(
                signingKey = encodePublicKey(signingKP.public),
                encryptionKey = encodePublicKey(encryptionKP.public),
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<RegisterResponse>()
        assertNotNull(body.deviceId)
        assertTrue(body.deviceId.isNotEmpty())
    }

    @Test
    fun `register with duplicate signing key returns 409`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val signingKP = generateSigningKeyPair()
        val signingKeyBase64 = encodePublicKey(signingKP.public)

        // First registration succeeds
        val response1 = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(
                signingKey = signingKeyBase64,
                encryptionKey = encodePublicKey(generateEncryptionKeyPair().public),
            ))
        }
        assertEquals(HttpStatusCode.Created, response1.status)

        // Second registration with same signing key fails
        val response2 = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(
                signingKey = signingKeyBase64,
                encryptionKey = encodePublicKey(generateEncryptionKeyPair().public),
            ))
        }
        assertEquals(HttpStatusCode.Conflict, response2.status)
    }

    @Test
    fun `register with missing signing key returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("encryptionKey" to encodePublicKey(generateEncryptionKeyPair().public)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with missing encryption key returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("signingKey" to encodePublicKey(generateSigningKeyPair().public)))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `register with empty body returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(mapOf<String, String>())
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Challenge Request
    // ================================================================

    @Test
    fun `challenge returns nonce and expiry for registered device`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<ChallengeResponse>()
        assertNotNull(body.nonce)
        assertTrue(body.nonce.isNotEmpty())
        assertNotNull(body.expiresAt)
        assertTrue(body.expiresAt.isNotEmpty())
    }

    @Test
    fun `challenge with unknown signing key returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val unknownKey = encodePublicKey(generateSigningKeyPair().public)

        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = unknownKey))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ================================================================
    // Challenge Verification
    // ================================================================

    @Test
    fun `verify with valid signature returns session token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        // Request challenge
        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Sign and verify
        val (signature, timestamp) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<VerifyResponse>()
        assertNotNull(body.sessionToken)
        assertTrue(body.sessionToken.isNotEmpty())
        assertTrue(body.expiresIn > 0)
    }

    @Test
    fun `verify with different private key returns 401`() = testApplication {
        // The server verifies Ed25519 signatures using BouncyCastle,
        // so signing with a different private key must be rejected.
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Sign with a DIFFERENT key (not the one registered)
        val wrongKeyPair = generateSigningKeyPair()
        val (wrongSignature, timestamp) = signChallenge(
            privateKey = wrongKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = wrongSignature,
                timestamp = timestamp,
            ))
        }

        // Server verifies Ed25519 signature and rejects wrong key
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `verify with reused nonce returns 401`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        val (signature, timestamp) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        // First verification succeeds
        val verify1 = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }
        assertEquals(HttpStatusCode.OK, verify1.status)

        // Second verification with same nonce fails (anti-replay)
        val (signature2, timestamp2) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        val verify2 = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = signature2,
                timestamp = timestamp2,
            ))
        }
        assertEquals(HttpStatusCode.Unauthorized, verify2.status)
    }

    @Test
    fun `verify with wrong signing key returns 401`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Register two devices
        val deviceA = TestHelper.registerDevice(client)
        val deviceB = TestHelper.registerDevice(client)

        // Get challenge for device A
        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = deviceA.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Sign with device A's key but present device B's signing key
        val (signature, timestamp) = signChallenge(
            privateKey = deviceA.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = deviceB.signingKeyBase64, // Wrong: claiming to be B
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = deviceB.signingKeyBase64, // Claiming to be device B
                nonce = challenge.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `verify with expired nonce returns 401`() = testApplication {
        // Use a config with 0-second challenge TTL to force expiration
        val config = testConfig().copy(challengeTtlSeconds = 0L)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Small delay to ensure nonce is expired (TTL=0)
        Thread.sleep(50)

        val (signature, timestamp) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ================================================================
    // Session Token
    // ================================================================

    @Test
    fun `valid session token grants access to authenticated endpoints`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        // Create a bucket (requires authentication)
        val response = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(CreateBucketRequest())
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `invalid session token returns 401`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer invalid-token-12345")
            setBody(CreateBucketRequest())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `missing session token returns 401 for authenticated endpoints`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            setBody(CreateBucketRequest())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `register endpoint works without session token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(
                signingKey = encodePublicKey(generateSigningKeyPair().public),
                encryptionKey = encodePublicKey(generateEncryptionKeyPair().public),
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `auth challenge endpoint works without session token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val response = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `auth verify endpoint works without session token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challenge = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        val (signature, timestamp) = signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challenge.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        val response = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challenge.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `health endpoint works without session token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `expired session token returns 401`() = testApplication {
        // Use 0-second session TTL to force immediate expiration
        val config = testConfig().copy(sessionTtlSeconds = 0L)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        // Small delay to ensure token is expired
        Thread.sleep(50)

        val response = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(CreateBucketRequest())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
