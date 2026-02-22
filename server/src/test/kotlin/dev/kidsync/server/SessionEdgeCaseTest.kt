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
import kotlin.test.assertNotEquals

/**
 * Edge case tests for session management: expiry, invalidation on bucket deletion,
 * max sessions per device, and nonce reuse.
 */
class SessionEdgeCaseTest {

    // ================================================================
    // Session expiry
    // ================================================================

    @Test
    fun `expired session is rejected`() = testApplication {
        // Use a config with 1-second session TTL so tokens expire quickly
        val config = testConfig().copy(sessionTtlSeconds = 1)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // Wait for the token to expire (TTL = 1 second)
        Thread.sleep(1500)

        val response = client.get("/buckets/${device.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ================================================================
    // Nonce reuse
    // ================================================================

    @Test
    fun `nonce cannot be reused after successful verification`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        // Request challenge
        val challengeResp = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Sign and verify (first time)
        val (signature, timestamp) = TestHelper.signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challengeResp.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        val firstVerify = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challengeResp.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }
        assertEquals(HttpStatusCode.OK, firstVerify.status)

        // Try to use the same nonce again (replay attack)
        val (sig2, ts2) = TestHelper.signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challengeResp.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )
        val secondVerify = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challengeResp.nonce,
                signature = sig2,
                timestamp = ts2,
            ))
        }
        assertEquals(HttpStatusCode.Unauthorized, secondVerify.status)
    }

    // ================================================================
    // Multiple sessions per device
    // ================================================================

    @Test
    fun `re-authentication invalidates prior sessions`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val registered = TestHelper.registerDevice(client)

        // Authenticate twice to get two different session tokens
        val session1 = TestHelper.authenticateDevice(client, registered)
        val session2 = TestHelper.authenticateDevice(client, registered)

        assertNotEquals(session1.sessionToken, session2.sessionToken)

        // SEC5-S-01: First session should be invalidated by re-authentication
        val bucketResp1 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session1.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Unauthorized, bucketResp1.status)

        // Second (latest) session should work
        val bucketResp2 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${session2.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, bucketResp2.status)
    }

    // ================================================================
    // Session invalidation on self-revoke from last bucket
    // ================================================================

    @Test
    fun `session invalidated when device self-revokes from last bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // deviceB self-revokes from the only bucket it belongs to
        val revokeResp = client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, revokeResp.status)

        // deviceB's session should now be invalidated (SEC4-S-07)
        val testResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Unauthorized, testResp.status)
    }

    // ================================================================
    // Session invalidation on bucket deletion for orphaned devices
    // ================================================================

    @Test
    fun `non-creator session invalidated when bucket deleted and device has no other buckets`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // deviceA (creator) deletes the bucket
        val deleteResp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        // deviceB should have its sessions invalidated (SEC6-S-11)
        val testResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Unauthorized, testResp.status)
    }

    @Test
    fun `creator session preserved when bucket deleted`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // deviceA (creator) deletes the bucket
        val deleteResp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        // deviceA's session should still work (creator is skipped in invalidation)
        val bucketResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, bucketResp.status)
    }

    // ================================================================
    // Expired challenge nonce
    // ================================================================

    @Test
    fun `expired challenge nonce is rejected`() = testApplication {
        // Use 0-second challenge TTL
        val config = testConfig().copy(challengeTtlSeconds = 0)
        application { module(config) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)

        val challengeResp = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Wait for nonce to expire
        Thread.sleep(50)

        val (signature, timestamp) = TestHelper.signChallenge(
            privateKey = device.signingKeyPair.private,
            nonce = challengeResp.nonce,
            signingKeyBase64 = device.signingKeyBase64,
        )

        val verifyResp = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device.signingKeyBase64,
                nonce = challengeResp.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }

        assertEquals(HttpStatusCode.Unauthorized, verifyResp.status)
    }

    // ================================================================
    // Wrong signing key for nonce
    // ================================================================

    @Test
    fun `nonce with wrong signing key is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device1 = TestHelper.registerDevice(client)
        val device2 = TestHelper.registerDevice(client)

        // Get challenge for device1's key
        val challengeResp = client.post("/auth/challenge") {
            contentType(ContentType.Application.Json)
            setBody(ChallengeRequest(signingKey = device1.signingKeyBase64))
        }.body<ChallengeResponse>()

        // Try to verify with device2's key (different key than what was used for challenge)
        val (signature, timestamp) = TestHelper.signChallenge(
            privateKey = device2.signingKeyPair.private,
            nonce = challengeResp.nonce,
            signingKeyBase64 = device2.signingKeyBase64,
        )

        val verifyResp = client.post("/auth/verify") {
            contentType(ContentType.Application.Json)
            setBody(VerifyRequest(
                signingKey = device2.signingKeyBase64,
                nonce = challengeResp.nonce,
                signature = signature,
                timestamp = timestamp,
            ))
        }

        // Should fail because nonce was issued for device1's key, not device2's
        assertEquals(HttpStatusCode.Unauthorized, verifyResp.status)
    }
}
