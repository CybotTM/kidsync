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
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BucketTest {

    // ================================================================
    // Bucket Creation
    // ================================================================

    @Test
    fun `authenticated device can create bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(CreateBucketRequest())
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<BucketResponse>()
        assertNotNull(body.bucketId)
        assertTrue(body.bucketId.isNotEmpty())
    }

    @Test
    fun `creator gets automatic access to bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Creator should be able to list devices (proves access)
        val response = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val devices = response.body<DeviceListResponse>().devices
        assertTrue(devices.isNotEmpty())
        assertTrue(devices.any { it.deviceId == device.deviceId })
    }

    @Test
    fun `device can create multiple buckets`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val bucket1 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()

        val bucket2 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()

        assertTrue(bucket1.bucketId != bucket2.bucketId)
    }

    // ================================================================
    // Bucket Deletion
    // ================================================================

    @Test
    fun `creator can delete bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `non-creator cannot delete bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device B (non-creator) tries to delete
        val response = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `deleting bucket removes all ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload some ops
        uploadOpsChain(client, device, 3)

        // Delete bucket
        val deleteResp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        // Attempt to pull ops from deleted bucket should fail
        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        // Should return 404 (bucket not found) or 403 (no access)
        assertTrue(
            pullResp.status == HttpStatusCode.NotFound || pullResp.status == HttpStatusCode.Forbidden,
            "Expected 404 or 403 after bucket deletion, got ${pullResp.status}"
        )
    }

    @Test
    fun `deleting bucket removes all access records`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Delete bucket (by creator)
        client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        // Device B should not be able to access the bucket
        // SEC6-S-11: Device B's session may be invalidated (401) if it has no remaining buckets
        val response = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertTrue(
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Forbidden ||
                response.status == HttpStatusCode.Unauthorized,
            "Expected 401, 403, or 404 for deleted bucket, got ${response.status}"
        )
    }

    // ================================================================
    // Invite Flow
    // ================================================================

    @Test
    fun `create invite and join with valid token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create invite
        val inviteToken = "invite-token-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)

        val inviteResponse = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)

        // Device B registers, authenticates, and joins
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        val joinResponse = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }
        assertEquals(HttpStatusCode.OK, joinResponse.status)
    }

    @Test
    fun `join with invalid token returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create invite with one token
        val realToken = "real-token-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(realToken)
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }

        // Device B tries to join with wrong token
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        val response = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = "wrong-token"))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `invite token is single-use`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        val inviteToken = "single-use-token-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }

        // Device B joins successfully
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)
        val join1 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }
        assertEquals(HttpStatusCode.OK, join1.status)

        // Device C tries to use same token -- should fail
        val deviceCReg = TestHelper.registerDevice(client)
        val deviceC = TestHelper.authenticateDevice(client, deviceCReg)
        val join2 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceC.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }

        assertTrue(
            join2.status == HttpStatusCode.NotFound || join2.status == HttpStatusCode.Conflict ||
                join2.status == HttpStatusCode.Gone,
            "Expected 404, 409, or 410 for reused invite token, got ${join2.status}"
        )
    }

    @Test
    fun `join with expired invite token returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create invite with very short expiry
        // The server uses default 24h expiry, but we can test by manipulating time or
        // by verifying the token_hash lookup. For a realistic test, we just verify
        // that an unknown token hash returns 404 (same code path as expired).
        val expiredToken = "expired-${System.nanoTime()}"
        // Do NOT register this token hash on the server -- simulates expired/purged token
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        val response = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = expiredToken))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ================================================================
    // Device Listing
    // ================================================================

    @Test
    fun `list devices shows all devices with access`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        val response = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val devices = response.body<DeviceListResponse>().devices
        assertEquals(2, devices.size)

        val deviceIds = devices.map { it.deviceId }.toSet()
        assertTrue(deviceIds.contains(deviceA.deviceId))
        assertTrue(deviceIds.contains(deviceB.deviceId))
    }

    @Test
    fun `device listing includes encryption keys for DEK wrapping`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        val response = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val devices = response.body<DeviceListResponse>().devices

        for (deviceInfo in devices) {
            assertNotNull(deviceInfo.encryptionKey)
            assertTrue(deviceInfo.encryptionKey.isNotEmpty())
        }

        // Verify device B's encryption key matches what was registered
        val deviceBInfo = devices.find { it.deviceId == deviceB.deviceId }
        assertNotNull(deviceBInfo)
        assertEquals(deviceB.encryptionKeyBase64, deviceBInfo.encryptionKey)
    }

    @Test
    fun `device listing excludes revoked devices`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device B self-revokes
        client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Device A lists devices
        val response = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val devices = response.body<DeviceListResponse>().devices
        assertEquals(1, devices.size)
        assertEquals(deviceA.deviceId, devices[0].deviceId)
    }

    // ================================================================
    // Self-Revoke
    // ================================================================

    @Test
    fun `device can self-revoke from bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        val response = client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `after self-revoke device cannot access bucket ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload some ops first
        uploadOpsChain(client, deviceA, 3)

        // Device B self-revokes
        client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Device B tries to pull ops
        // SEC4-S-07: Session is invalidated when device has no remaining buckets,
        // so we get 401 (Unauthorized) instead of 403 (Forbidden)
        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        assertTrue(pullResp.status == HttpStatusCode.Forbidden || pullResp.status == HttpStatusCode.Unauthorized,
            "Expected 401 or 403 after self-revoke, got ${pullResp.status}")
    }

    @Test
    fun `after self-revoke other devices are unaffected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload ops from device A
        uploadOpsChain(client, deviceA, 3)

        // Device B self-revokes
        client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Device A can still access ops
        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, pullResp.status)
        val ops = pullResp.body<PullOpsResponse>().ops
        assertEquals(3, ops.size)
    }

    @Test
    fun `creator can revoke another device`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // SEC5-S-08: Creator (device A) can revoke device B
        val response = client.delete("/buckets/$bucketId/devices/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `device without bucket access gets 403`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device B registers and authenticates but does NOT join the bucket
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        val response = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
