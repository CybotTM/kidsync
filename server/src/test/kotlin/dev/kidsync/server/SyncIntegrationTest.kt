package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsChain
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for sync (ops push/pull) endpoints.
 */
class SyncIntegrationTest {

    // ================================================================
    // Pagination
    // ================================================================

    @Test
    fun `pull with limit returns correct page size and hasMore flag`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload 150 ops in batches
        var prevHash = "0".repeat(64)
        for (batch in 1..3) {
            prevHash = uploadOpsBatch(client, device, 50, startPrevHash = prevHash, localIdPrefix = "batch$batch")
        }

        // Pull first page of 50
        val page1 = client.get("/buckets/$bucketId/ops?since=0&limit=50") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()
        assertEquals(50, page1.ops.size)
        assertTrue(page1.hasMore)

        // Pull second page of 50
        val lastSeq1 = page1.ops.last().globalSequence
        val page2 = client.get("/buckets/$bucketId/ops?since=$lastSeq1&limit=50") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()
        assertEquals(50, page2.ops.size)
        assertTrue(page2.hasMore)

        // Pull third page of 50
        val lastSeq2 = page2.ops.last().globalSequence
        val page3 = client.get("/buckets/$bucketId/ops?since=$lastSeq2&limit=50") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()
        assertEquals(50, page3.ops.size)
        assertFalse(page3.hasMore)

        // Pull after all - should be empty
        val lastSeq3 = page3.ops.last().globalSequence
        val page4 = client.get("/buckets/$bucketId/ops?since=$lastSeq3&limit=50") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()
        assertEquals(0, page4.ops.size)
        assertFalse(page4.hasMore)
    }

    @Test
    fun `pull latestSequence is accurate across pages`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsBatch(client, device, 20)

        val page1 = client.get("/buckets/$bucketId/ops?since=0&limit=5") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>()

        // latestSequence should reflect the total ops, not just the page
        assertTrue(page1.latestSequence >= 20)
        assertTrue(page1.hasMore)
    }

    // ================================================================
    // Payload Size Limits
    // ================================================================

    @Test
    fun `payload at exactly 64KB is accepted`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Create a payload that is exactly 64KB when decoded from base64
        val rawPayload = ByteArray(64 * 1024) { 0x41 } // 64KB of 'A'
        val payload = Base64.getEncoder().encodeToString(rawPayload)
        val prevHash = "0".repeat(64)
        val curHash = computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = payload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
    }

    @Test
    fun `payload at 64KB plus 1 byte is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Create a payload that is 64KB + 1 when decoded
        val rawPayload = ByteArray(64 * 1024 + 1) { 0x41 }
        val payload = Base64.getEncoder().encodeToString(rawPayload)
        val prevHash = "0".repeat(64)
        val curHash = computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = payload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("PAYLOAD_TOO_LARGE", body.error)
    }

    // ================================================================
    // Hash Chain Validation
    // ================================================================

    @Test
    fun `hash chain break returns 409 with HASH_CHAIN_BREAK error code`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload first op
        val prevHash = "0".repeat(64)
        val payload1 = Base64.getEncoder().encodeToString("first".toByteArray())
        val hash1 = computeHash(prevHash, payload1)
        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(OpInput(device.deviceId, 1, payload1, prevHash, hash1))))
        }

        // Upload second op with wrong prevHash
        val wrongPrev = "a".repeat(64)
        val payload2 = Base64.getEncoder().encodeToString("second".toByteArray())
        val hash2 = computeHash(wrongPrev, payload2)
        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(OpInput(device.deviceId, 1, payload2, wrongPrev, hash2))))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("HASH_CHAIN_BREAK", body.error)
    }

    @Test
    fun `hash mismatch returns 409 with HASH_MISMATCH error code`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())
        val wrongHash = "b".repeat(64)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(OpInput(device.deviceId, 1, payload, prevHash, wrongHash))))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("HASH_MISMATCH", body.error)
    }

    // ================================================================
    // Device ID Mismatch
    // ================================================================

    @Test
    fun `op with different deviceId than authenticated user is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())
        val curHash = computeHash(prevHash, payload)

        // Device A submits op claiming to be device B
        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = deviceB.deviceId, // MISMATCH
                    keyEpoch = 1,
                    encryptedPayload = payload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ================================================================
    // Key Epoch Validation
    // ================================================================

    @Test
    fun `keyEpoch zero is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())
        val curHash = computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 0, payload, prevHash, curHash))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `keyEpoch negative is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())
        val curHash = computeHash(prevHash, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, -1, payload, prevHash, curHash))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Batch Size Limits
    // ================================================================

    @Test
    fun `batch of exactly 100 ops is accepted`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val lastHash = uploadOpsBatch(client, device, 100)
        assertTrue(lastHash.isNotEmpty())
    }

    @Test
    fun `batch of 101 ops is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val ops = mutableListOf<OpInput>()
        var prevHash = "0".repeat(64)
        for (i in 1..101) {
            val payload = Base64.getEncoder().encodeToString("op-$i".toByteArray())
            val curHash = computeHash(prevHash, payload)
            ops.add(OpInput(device.deviceId, 1, payload, prevHash, curHash))
            prevHash = curHash
        }

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = ops))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.body<ErrorResponse>()
        assertEquals("BATCH_TOO_LARGE", body.error)
    }

    // ================================================================
    // Invalid Hash Formats
    // ================================================================

    @Test
    fun `prevHash with invalid hex is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val badPrevHash = "g".repeat(64) // not valid hex
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload, badPrevHash, "a".repeat(64)))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `currentHash too short is rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload, prevHash, "a".repeat(32)))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Pull missing since parameter
    // ================================================================

    @Test
    fun `pull without since parameter returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/ops") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `pull with non-numeric since returns 400`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.get("/buckets/$bucketId/ops?since=abc") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // First op sentinel validation
    // ================================================================

    @Test
    fun `first op from device must have all-zero prevHash`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Try to submit first op with non-zero prevHash
        val wrongPrev = "a".repeat(64)
        val payload = Base64.getEncoder().encodeToString("first".toByteArray())
        val curHash = computeHash(wrongPrev, payload)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload, wrongPrev, curHash))
            ))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
    }
}
