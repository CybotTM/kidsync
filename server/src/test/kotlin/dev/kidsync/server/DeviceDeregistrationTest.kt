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
import kotlin.test.assertTrue

/**
 * Integration tests for SEC5-S-02: Device deregistration (DELETE /devices/me).
 */
class DeviceDeregistrationTest {

    @Test
    fun `successful deregistration returns 204`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.delete("/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `session is invalid after deregistration`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        client.delete("/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        // Session should no longer work
        val response = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(CreateBucketRequest())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `bucket membership removed after deregistration`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device B deregisters
        client.delete("/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Device A should see only itself in the bucket
        val devicesResp = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, devicesResp.status)
        val devices = devicesResp.body<DeviceListResponse>().devices
        assertEquals(1, devices.size)
        assertEquals(deviceA.deviceId, devices[0].deviceId)
    }

    @Test
    fun `unauthenticated deregistration request rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.delete("/devices/me")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `deregistration with invalid token rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.delete("/devices/me") {
            header(HttpHeaders.Authorization, "Bearer sess_invalid-token")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `multiple devices - only caller deregistered`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device B deregisters
        client.delete("/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Device A should still be fully functional
        val resp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, resp.status)
    }

    @Test
    fun `re-registration after deregistration succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // Deregister
        client.delete("/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        // Re-register with new keys (old signing key record was deleted)
        val newDevice = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, newDevice)

        val bucketResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, bucketResp.status)
    }

    @Test
    fun `device with pending ops can deregister`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // Upload some ops
        uploadOpsChain(client, device, 5)

        // Deregister should still succeed
        val response = client.delete("/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }
}
