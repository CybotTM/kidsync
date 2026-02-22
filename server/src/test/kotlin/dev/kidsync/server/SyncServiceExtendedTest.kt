package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.uploadOpsBatch
import dev.kidsync.server.TestHelper.uploadOpsChain
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
 * Extended tests for SyncService: validation, hash chain, sequence handling, edge cases.
 */
class SyncServiceExtendedTest {

    private val encoder = Base64.getEncoder()

    // ================================================================
    // Upload Validation
    // ================================================================

    @Test
    fun `empty ops array rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = emptyList()))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `oversized batch rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Create 101 ops (limit is 100)
        val ops = (1..101).map { i ->
            val prevHash = if (i == 1) "0".repeat(64) else "a".repeat(64)
            OpInput(
                deviceId = device.deviceId,
                keyEpoch = 1,
                encryptedPayload = encoder.encodeToString("p$i".toByteArray()),
                prevHash = prevHash,
                currentHash = "b".repeat(64),
            )
        }

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = ops))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `invalid prevHash format rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = encoder.encodeToString("test".toByteArray()),
                    prevHash = "not-a-valid-sha256",
                    currentHash = "0".repeat(64),
                ))
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `invalid currentHash format rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = encoder.encodeToString("test".toByteArray()),
                    prevHash = "0".repeat(64),
                    currentHash = "XYZ-invalid",
                ))
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `invalid base64 payload rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = "!!!not-base64!!!",
                    prevHash = "0".repeat(64),
                    currentHash = "0".repeat(64),
                ))
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `zero key epoch rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = encoder.encodeToString("test".toByteArray())
        val curHash = computeHash(prevHash, payload)

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 0,
                    encryptedPayload = payload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ================================================================
    // Hash Chain Verification
    // ================================================================

    @Test
    fun `first op must have all-zeros prevHash`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "a".repeat(64)  // Not all zeros
        val payload = encoder.encodeToString("test".toByteArray())
        val curHash = computeHash(prevHash, payload)

        val resp = client.post("/buckets/$bucketId/ops") {
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
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `broken hash chain in batch rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash1 = "0".repeat(64)
        val payload1 = encoder.encodeToString("payload1".toByteArray())
        val hash1 = computeHash(prevHash1, payload1)

        val payload2 = encoder.encodeToString("payload2".toByteArray())
        val wrongPrevHash = "b".repeat(64) // Should be hash1 but isn't
        val hash2 = computeHash(wrongPrevHash, payload2)

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(
                    OpInput(device.deviceId, 1, payload1, prevHash1, hash1),
                    OpInput(device.deviceId, 1, payload2, wrongPrevHash, hash2),
                )
            ))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    @Test
    fun `mismatched currentHash rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = encoder.encodeToString("test".toByteArray())
        val wrongHash = "f".repeat(64)  // Not the actual computed hash

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload, prevHash, wrongHash))
            ))
        }
        assertEquals(HttpStatusCode.Conflict, resp.status)
    }

    // ================================================================
    // Op Retrieval
    // ================================================================

    @Test
    fun `pull ops since sequence returns correct ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsChain(client, device, 5)

        // Pull ops since sequence 2
        val resp = client.get("/buckets/$bucketId/ops?since=2") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<PullOpsResponse>()
        assertEquals(3, body.ops.size, "Should return ops 3, 4, 5")
        assertTrue(body.ops.all { it.globalSequence > 2 })
    }

    @Test
    fun `pull ops with limit returns correct number`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsChain(client, device, 10)

        val resp = client.get("/buckets/$bucketId/ops?since=0&limit=3") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<PullOpsResponse>()
        assertEquals(3, body.ops.size)
        assertTrue(body.hasMore, "Should indicate more ops available")
    }

    @Test
    fun `pull all ops sets hasMore to false`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsChain(client, device, 3)

        val resp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<PullOpsResponse>()
        assertEquals(3, body.ops.size)
        assertFalse(body.hasMore)
    }

    @Test
    fun `negative since parameter rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val resp = client.get("/buckets/$bucketId/ops?since=-1") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `missing since parameter rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val resp = client.get("/buckets/$bucketId/ops") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ================================================================
    // Edge Cases
    // ================================================================

    @Test
    fun `wrong deviceId in op rejected`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = encoder.encodeToString("test".toByteArray())
        val curHash = computeHash(prevHash, payload)

        val resp = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = "00000000-0000-0000-0000-000000000099",
                    keyEpoch = 1,
                    encryptedPayload = payload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
    }

    @Test
    fun `batch upload preserves sequence ordering`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsBatch(client, device, 5)

        val resp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        val body = resp.body<PullOpsResponse>()
        assertEquals(5, body.ops.size)

        // Verify sequences are strictly increasing
        for (i in 1 until body.ops.size) {
            assertTrue(body.ops[i].globalSequence > body.ops[i - 1].globalSequence)
        }
    }
}
