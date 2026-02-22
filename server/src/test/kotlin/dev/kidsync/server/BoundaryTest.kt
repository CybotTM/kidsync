package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsBatch
import dev.kidsync.server.models.*
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

/**
 * Boundary and edge case tests for limits, empty states, and extreme inputs.
 */
class BoundaryTest {

    // ================================================================
    // Empty Bucket Operations
    // ================================================================

    @Test
    fun `pull from empty bucket returns zero ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()

        assertEquals(0, response.ops.size)
        assertEquals(false, response.hasMore)
        assertEquals(0L, response.latestSequence)
    }

    @Test
    fun `device list on newly created bucket has exactly one device`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val devices = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<DeviceListResponse>().devices

        assertEquals(1, devices.size)
        assertEquals(device.deviceId, devices[0].deviceId)
    }

    // ================================================================
    // Payload Boundary
    // ================================================================

    @Test
    fun `minimum payload single base64 char accepted`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Smallest valid base64 payload: one byte encoded
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(0x01))
        val prevHash = "0".repeat(64)
        val curHash = computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload, prevHash, curHash))
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    // ================================================================
    // Pull with limit edge cases
    // ================================================================

    @Test
    fun `pull with limit=1 returns single op`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsBatch(client, device, 10)

        val response = client.get("/buckets/$bucketId/ops?since=0&limit=1") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()

        assertEquals(1, response.ops.size)
        assertTrue(response.hasMore)
    }

    @Test
    fun `pull with very large limit is clamped to 1000`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsBatch(client, device, 5)

        // Request limit=999999 - server should clamp to 1000
        val response = client.get("/buckets/$bucketId/ops?since=0&limit=999999") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()

        assertEquals(5, response.ops.size) // All 5 ops returned (within 1000 limit)
        assertEquals(false, response.hasMore)
    }

    // ================================================================
    // High Key Epoch
    // ================================================================

    @Test
    fun `very high key epoch is accepted`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val payload = Base64.getEncoder().encodeToString("high-epoch".toByteArray())
        val prevHash = "0".repeat(64)
        val curHash = computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, Int.MAX_VALUE, payload, prevHash, curHash))
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    // ================================================================
    // Wrapped Key for Multiple Epochs
    // ================================================================

    @Test
    fun `wrapped keys for different epochs are independent`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Upload keys for epochs 1, 2, 3
        for (epoch in 1..3) {
            client.post("/keys/wrapped") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
                setBody(WrappedKeyRequest(
                    targetDevice = deviceB.deviceId,
                    wrappedDek = "dek-epoch-$epoch-padded-to-meet-minimum-length-req",
                    keyEpoch = epoch,
                ))
            }.also { assertEquals(HttpStatusCode.Created, it.status) }
        }

        // Retrieve each epoch
        for (epoch in 1..3) {
            val resp = client.get("/keys/wrapped?epoch=$epoch") {
                header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            }.body<WrappedKeyResponse>()
            assertEquals("dek-epoch-$epoch-padded-to-meet-minimum-length-req", resp.wrappedDek)
            assertEquals(epoch, resp.keyEpoch)
        }
    }

    // ================================================================
    // Wrapped Key for Unknown Device
    // ================================================================

    @Test
    fun `wrapped key for nonexistent target device returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = "00000000-0000-0000-0000-000000000000",
                wrappedDek = "some-dek-padded-to-meet-minimum-length-requirement",
                keyEpoch = 1,
            ))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ================================================================
    // Attestation for Unknown Device
    // ================================================================

    @Test
    fun `attestation for nonexistent attested device returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = "00000000-0000-0000-0000-000000000000",
                attestedKey = "some-key",
                signature = "some-sig",
            ))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // ================================================================
    // Push Token Platforms
    // ================================================================

    @Test
    fun `push token with FCM platform succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "fcm-token", platform = "FCM"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `push token with APNS platform succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "apns-token", platform = "APNS"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // ================================================================
    // Health Endpoint
    // ================================================================

    // SEC6-S-18: Health endpoint no longer returns version or uptime
    @Test
    fun `health endpoint returns status only`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.body<HealthResponse>()
        assertEquals("healthy", body.status)
        // version and uptime are no longer included
        assertEquals(null, body.version)
        assertEquals(null, body.uptime)
    }

    // ================================================================
    // Checkpoint
    // ================================================================

    @Test
    fun `checkpoint not available before threshold returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload fewer ops than checkpoint interval (100)
        uploadOpsBatch(client, device, 10)

        val response = client.get("/buckets/$bucketId/checkpoint") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `checkpoint available after 100 ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload exactly 100 ops (checkpoint interval)
        var prevHash = "0".repeat(64)
        for (batch in 1..10) {
            prevHash = uploadOpsBatch(client, device, 10, startPrevHash = prevHash, localIdPrefix = "cp$batch")
        }

        val response = client.get("/buckets/$bucketId/checkpoint") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.body<CheckpointResponse>()
        assertNotNull(body.checkpoint)
        assertEquals(100, body.checkpoint.opCount)
        assertTrue(body.checkpoint.hash.length == 64)
    }
}
