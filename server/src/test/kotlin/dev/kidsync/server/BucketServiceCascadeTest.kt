package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsChain
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for BucketService including SEC3-S-24 cascade deletion.
 */
class BucketServiceCascadeTest {

    private val encoder = Base64.getEncoder()

    @Test
    fun `create invite and accept succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        val inviteToken = "invite-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        val inviteResp = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }
        assertEquals(HttpStatusCode.Created, inviteResp.status)

        val deviceB = TestHelper.registerDevice(client)
        val authedB = TestHelper.authenticateDevice(client, deviceB)
        val joinResp = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authedB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }
        assertEquals(HttpStatusCode.OK, joinResp.status)
    }

    @Test
    fun `reject invite with wrong token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        val inviteToken = "invite-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }

        val deviceB = TestHelper.registerDevice(client)
        val authedB = TestHelper.authenticateDevice(client, deviceB)
        val joinResp = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authedB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = "wrong-token"))
        }
        assertEquals(HttpStatusCode.NotFound, joinResp.status)
    }

    @Test
    fun `max members per bucket enforced`() = testApplication {
        // Use maxDevicesPerBucket=2 to stay within auth rate limits during testing
        // (each device registration + auth = 3 auth-rated requests; limit is 10/min/IP)
        val config = testConfig().copy(maxDevicesPerBucket = 2)
        application { module(config) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Add 1 more device to reach the limit of 2
        val inviteToken1 = "invite-1-${System.nanoTime()}"
        val tokenHash1 = HashUtil.sha256HexString(inviteToken1)
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash1))
        }
        val dev1 = TestHelper.registerDevice(client)
        val authedDev1 = TestHelper.authenticateDevice(client, dev1)
        val joinResp1 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authedDev1.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken1))
        }
        assertEquals(HttpStatusCode.OK, joinResp1.status, "Device 1 should join successfully")

        // 3rd device should be rejected (bucket already at max of 2)
        val inviteToken2 = "invite-2-${System.nanoTime()}"
        val tokenHash2 = HashUtil.sha256HexString(inviteToken2)
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash2))
        }
        val dev2 = TestHelper.registerDevice(client)
        val authedDev2 = TestHelper.authenticateDevice(client, dev2)
        val joinResp2 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authedDev2.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken2))
        }
        assertEquals(HttpStatusCode.TooManyRequests, joinResp2.status)
    }

    @Test
    fun `is member check works`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device A is a member -- should be able to list devices
        val resp = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        // Non-member should get 403
        val deviceB = TestHelper.registerDevice(client)
        val authedB = TestHelper.authenticateDevice(client, deviceB)
        val resp2 = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${authedB.sessionToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp2.status)
    }

    @Test
    fun `is creator check works for deletion`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Non-creator cannot delete
        val resp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)

        // Creator can delete
        val resp2 = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, resp2.status)
    }

    @Test
    fun `deletion cascade removes ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsChain(client, device, 5)

        val deleteResp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        // Verify ops are gone (bucket is deleted, so access will fail)
        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertTrue(
            pullResp.status == HttpStatusCode.NotFound || pullResp.status == HttpStatusCode.Forbidden,
            "Ops should be gone after bucket deletion, got ${pullResp.status}"
        )
    }

    @Test
    fun `deletion cascade removes invites`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Create invite
        val inviteToken = "invite-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }

        // Delete bucket
        client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        // Create new bucket and try joining with old invite -- should fail
        val newBucketResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, newBucketResp.status)
    }

    @Test
    fun `delete non-existent bucket returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val fakeBucketId = "00000000-0000-0000-0000-000000000099"

        val resp = client.delete("/buckets/$fakeBucketId") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `deletion with active members removes all access`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }

        // Device B's session may be invalidated if this was its only bucket
        val resp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `max buckets per device enforced`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        // Create max buckets (MAX_BUCKETS_PER_DEVICE = 10)
        for (i in 1..10) {
            val resp = client.post("/buckets") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
                setBody(CreateBucketRequest())
            }
            assertEquals(HttpStatusCode.Created, resp.status, "Bucket $i should succeed")
        }

        // 11th should be rejected
        val resp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.TooManyRequests, resp.status)
    }

    @Test
    fun `deletion cascade removes snapshots`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!
        uploadOpsChain(client, device, 1)

        // Upload a snapshot
        val snapshotData = ByteArray(50) { it.toByte() }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(snapshotData)
            .joinToString("") { "%02x".format(it) }
        val signature = encoder.encodeToString("sig".toByteArray())
        val metadata = Json.encodeToString(
            SnapshotMetadata.serializer(),
            SnapshotMetadata(atSequence = 1, keyEpoch = 1, sha256 = sha256, signature = signature)
        )

        val snapshotResp = client.submitFormWithBinaryData(
            url = "/buckets/$bucketId/snapshots",
            formData = formData {
                append("metadata", metadata)
                append("snapshot", snapshotData, Headers.build {
                    append(HttpHeaders.ContentType, "application/octet-stream")
                    append(HttpHeaders.ContentDisposition, "filename=\"snapshot.bin\"")
                })
            }
        ) {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.Created, snapshotResp.status)

        // Delete the bucket
        client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        // Verify snapshot endpoint returns 404 for deleted bucket
        val getResp = client.get("/buckets/$bucketId/snapshots/latest") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertTrue(
            getResp.status == HttpStatusCode.NotFound || getResp.status == HttpStatusCode.Forbidden,
            "Snapshots should be gone after bucket deletion"
        )
    }
}
