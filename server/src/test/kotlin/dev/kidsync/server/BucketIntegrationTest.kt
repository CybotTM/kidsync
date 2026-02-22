package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsChain
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for bucket lifecycle, invite flow, and cascade deletion.
 */
class BucketIntegrationTest {

    // ================================================================
    // Invite Error Codes
    // ================================================================

    @Test
    fun `join with invalid token returns INVITE_INVALID`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create a valid invite
        val realToken = "real-${System.nanoTime()}"
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(realToken)))
        }

        // Try joining with completely wrong token
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        val response = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = "totally-wrong-token"))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("INVITE_INVALID", body.error)
    }

    // SEC6-S-01: Consumed invite now returns generic 404 INVITE_INVALID
    @Test
    fun `join with consumed invite returns INVITE_INVALID`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        val inviteToken = "single-use-${System.nanoTime()}"
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(inviteToken)))
        }

        // Device B uses the token
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)
        val join1 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }
        assertEquals(HttpStatusCode.OK, join1.status)

        // Device C tries the same token
        val deviceCReg = TestHelper.registerDevice(client)
        val deviceC = TestHelper.authenticateDevice(client, deviceCReg)
        val join2 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceC.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }

        assertEquals(HttpStatusCode.NotFound, join2.status)
        val body = join2.body<ErrorResponse>()
        assertEquals("INVITE_INVALID", body.error)
    }

    // ================================================================
    // Double-Join Prevention
    // ================================================================

    @Test
    fun `device already member gets 409 on second join`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create two invites
        val token1 = "t1-${System.nanoTime()}"
        val token2 = "t2-${System.nanoTime()}"
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token1)))
        }
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token2)))
        }

        // Device B joins with first token
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)
        client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = token1))
        }

        // Device B tries to join again with second token
        val join2 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = token2))
        }

        assertEquals(HttpStatusCode.Conflict, join2.status)
    }

    // ================================================================
    // Cascade Deletion
    // ================================================================

    @Test
    fun `bucket deletion cascades to ops, invites, keys, and blobs`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload ops
        uploadOpsChain(client, deviceA, 5)

        // Upload wrapped key
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "dek-to-delete-padded-to-meet-minimum-len-requirement",
                keyEpoch = 1,
            ))
        }

        // Create invite (unused)
        val token = "cascade-${System.nanoTime()}"
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token)))
        }

        // Delete bucket
        val deleteResp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        // Verify ops are gone
        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertTrue(
            pullResp.status == HttpStatusCode.NotFound || pullResp.status == HttpStatusCode.Forbidden,
            "Expected 404 or 403 after deletion, got ${pullResp.status}"
        )

        // Verify device list is gone
        val devicesResp = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertTrue(
            devicesResp.status == HttpStatusCode.NotFound || devicesResp.status == HttpStatusCode.Forbidden,
            "Expected 404 or 403 for device list after deletion, got ${devicesResp.status}"
        )
    }

    // ================================================================
    // Invite Rate Limiting
    // ================================================================

    @Test
    fun `max 5 active invites per bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Create 5 invites (should all succeed)
        for (i in 1..5) {
            val token = "rate-$i-${System.nanoTime()}"
            val resp = client.post("/buckets/$bucketId/invite") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
                setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token)))
            }
            assertEquals(HttpStatusCode.Created, resp.status, "Invite $i should succeed")
        }

        // 6th invite should be rate limited
        val token6 = "rate-6-${System.nanoTime()}"
        val resp6 = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token6)))
        }
        assertEquals(429, resp6.status.value)
    }

    // ================================================================
    // Re-join after self-revoke
    // ================================================================

    @Test
    fun `device can re-join bucket after self-revoke`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device B self-revokes
        client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Verify B lost access (session invalidated since no remaining buckets)
        val pull1 = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        // SEC4-S-07: Session is invalidated when device has no remaining buckets
        assertTrue(pull1.status == HttpStatusCode.Forbidden || pull1.status == HttpStatusCode.Unauthorized,
            "Expected 401 or 403 after self-revoke, got ${pull1.status}")

        // Device A creates new invite
        val reToken = "rejoin-${System.nanoTime()}"
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(reToken)))
        }

        // Device B re-authenticates (session was invalidated after self-revoke)
        val deviceBReauthed = TestHelper.authenticateDevice(client, deviceB)

        // Device B re-joins with new session
        val rejoin = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceBReauthed.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = reToken))
        }
        assertEquals(HttpStatusCode.OK, rejoin.status)

        // Device B can access bucket again
        val pull2 = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceBReauthed.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, pull2.status)
    }

    // ================================================================
    // Invite Response
    // ================================================================

    @Test
    fun `invite response includes expiry time`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val token = "expiry-${System.nanoTime()}"
        val response = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token)))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<InviteResponse>()
        assertNotNull(body.expiresAt)
        assertTrue(body.expiresAt.isNotEmpty())
    }

    // ================================================================
    // Bucket not found
    // ================================================================

    @Test
    fun `invite on nonexistent bucket returns error`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.post("/buckets/00000000-0000-0000-0000-000000000000/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString("any-token")))
        }

        assertTrue(
            response.status == HttpStatusCode.Forbidden || response.status == HttpStatusCode.NotFound,
            "Expected 403 or 404, got ${response.status}"
        )
    }
}
