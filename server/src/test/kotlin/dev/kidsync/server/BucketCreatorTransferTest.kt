package dev.kidsync.server

import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Integration tests for SEC3-S-08: Bucket creator role transfer.
 */
class BucketCreatorTransferTest {

    @Test
    fun `creator successfully transfers to active member`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Transfer creator from A to B
        val resp = client.patch("/buckets/$bucketId/creator") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(TransferCreatorRequest(targetDeviceId = deviceB.deviceId))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        // Verify B is now the creator: B can delete the bucket
        val deleteResp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)
    }

    @Test
    fun `non-creator gets 403`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device B (non-creator) tries to transfer creator
        val resp = client.patch("/buckets/$bucketId/creator") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(TransferCreatorRequest(targetDeviceId = deviceA.deviceId))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        val body = resp.body<ErrorResponse>()
        assertEquals("NOT_BUCKET_CREATOR", body.error)
    }

    @Test
    fun `target not in bucket gets 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create outsider device (not in bucket)
        val outsiderReg = TestHelper.registerDevice(client)
        val outsider = TestHelper.authenticateDevice(client, outsiderReg)

        val resp = client.patch("/buckets/$bucketId/creator") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(TransferCreatorRequest(targetDeviceId = outsider.deviceId))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `revoked target gets 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Revoke device B
        val revokeResp = client.delete("/buckets/$bucketId/devices/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, revokeResp.status)

        // Try to transfer to revoked device B
        val resp = client.patch("/buckets/$bucketId/creator") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(TransferCreatorRequest(targetDeviceId = deviceB.deviceId))
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }

    @Test
    fun `after transfer new creator can perform creator-only operations`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Transfer from A to B
        val transferResp = client.patch("/buckets/$bucketId/creator") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(TransferCreatorRequest(targetDeviceId = deviceB.deviceId))
        }
        assertEquals(HttpStatusCode.OK, transferResp.status)

        // Old creator (A) cannot revoke B
        val revokeByA = client.delete("/buckets/$bucketId/devices/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, revokeByA.status)

        // New creator (B) can revoke A
        val revokeByB = client.delete("/buckets/$bucketId/devices/${deviceA.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, revokeByB.status)
    }

    @Test
    fun `cannot transfer to self`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val resp = client.patch("/buckets/$bucketId/creator") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(TransferCreatorRequest(targetDeviceId = device.deviceId))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
