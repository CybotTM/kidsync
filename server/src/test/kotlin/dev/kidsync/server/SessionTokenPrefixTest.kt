package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import dev.kidsync.server.util.CHALLENGE_TOKEN_PREFIX
import dev.kidsync.server.util.SESSION_TOKEN_PREFIX
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for SEC6-S-05: Token type prefixes to prevent cross-use attacks.
 */
class SessionTokenPrefixTest {

    @Test
    fun `session token has sess_ prefix`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        assertTrue(
            authed.sessionToken.startsWith(SESSION_TOKEN_PREFIX),
            "Session token should start with '$SESSION_TOKEN_PREFIX', got: ${authed.sessionToken.take(10)}"
        )
    }

    @Test
    fun `challenge nonce has chal_ prefix`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val resp = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val challenge = resp.body<ChallengeResponse>()

        assertTrue(
            challenge.nonce.startsWith(CHALLENGE_TOKEN_PREFIX),
            "Challenge nonce should start with '$CHALLENGE_TOKEN_PREFIX', got: ${challenge.nonce.take(10)}"
        )
    }

    @Test
    fun `challenge nonce cannot be used as session token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challengeResp = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }
        val challenge = challengeResp.body<ChallengeResponse>()

        // Try using the challenge nonce as a session token
        val resp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${challenge.nonce}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `session token without prefix is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // A raw base64 token without prefix should be rejected
        val resp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer some-raw-token-without-prefix")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `prefixed session token works for authenticated requests`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // The token from setupDeviceWithBucket should have the prefix and work
        assertTrue(device.sessionToken.startsWith(SESSION_TOKEN_PREFIX))

        val resp = client.get("/buckets/${device.bucketId}/devices") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
