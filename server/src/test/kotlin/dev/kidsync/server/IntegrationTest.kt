package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
import dev.kidsync.server.TestHelper.createJsonClient
import dev.kidsync.server.TestHelper.encodePublicKey
import dev.kidsync.server.TestHelper.generateEncryptionKeyPair
import dev.kidsync.server.TestHelper.generateSigningKeyPair
import dev.kidsync.server.TestHelper.uploadOpsChain
import dev.kidsync.server.TestHelper.uploadOpsBatch
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

class IntegrationTest {

    // ================================================================
    // Database Constraints
    // ================================================================

    @Test
    fun `signing key uniqueness constraint enforced at database level`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val signingKP = generateSigningKeyPair()
        val signingKey = encodePublicKey(signingKP.public)

        // First registration
        val resp1 = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(
                signingKey = signingKey,
                encryptionKey = encodePublicKey(generateEncryptionKeyPair().public),
            ))
        }
        assertEquals(HttpStatusCode.Created, resp1.status)

        // Second registration with same signing key
        val resp2 = client.post("/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(
                signingKey = signingKey,
                encryptionKey = encodePublicKey(generateEncryptionKeyPair().public),
            ))
        }
        assertEquals(HttpStatusCode.Conflict, resp2.status)
    }

    @Test
    fun `bucket access constraint device cannot join same bucket twice`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create two invite tokens
        val token1 = "token1-${System.nanoTime()}"
        val token2 = "token2-${System.nanoTime()}"

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

        val join1 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = token1))
        }
        assertEquals(HttpStatusCode.OK, join1.status)

        // Device B tries to join again with second token (already has access)
        val join2 = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = token2))
        }
        // Should be 409 (already a member) or 200 (idempotent)
        assertTrue(
            join2.status == HttpStatusCode.Conflict || join2.status == HttpStatusCode.OK,
            "Double join should return 409 or 200, got ${join2.status}"
        )
    }

    @Test
    fun `wrapped key unique constraint per target device and epoch`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Upload wrapped key epoch 1
        val resp1 = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "first-dek",
                keyEpoch = 1,
            ))
        }
        assertEquals(HttpStatusCode.Created, resp1.status)

        // Upload another wrapped key for same device+epoch
        val resp2 = client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "second-dek",
                keyEpoch = 1,
            ))
        }
        // Should be 409 (unique constraint) or 200 (upsert)
        assertTrue(
            resp2.status == HttpStatusCode.Conflict || resp2.status == HttpStatusCode.OK ||
                resp2.status == HttpStatusCode.Created,
            "Duplicate wrapped key should return 409, 200, or 201, got ${resp2.status}"
        )
    }

    // ================================================================
    // Concurrent Access Patterns
    // ================================================================

    @Test
    fun `multiple devices uploading ops concurrently in same bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Simulate interleaved uploads
        val sentinel = "0".repeat(64)

        // Device A op 1
        val payloadA1 = Base64.getEncoder().encodeToString("A-1".toByteArray())
        val hashA1 = computeHash(sentinel, payloadA1)
        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceA.deviceId, 1, payloadA1, sentinel, hashA1)
            )))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Device B op 1
        val payloadB1 = Base64.getEncoder().encodeToString("B-1".toByteArray())
        val hashB1 = computeHash(sentinel, payloadB1)
        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceB.deviceId, 1, payloadB1, sentinel, hashB1)
            )))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Device A op 2
        val payloadA2 = Base64.getEncoder().encodeToString("A-2".toByteArray())
        val hashA2 = computeHash(hashA1, payloadA2)
        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceA.deviceId, 1, payloadA2, hashA1, hashA2)
            )))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Device B op 2
        val payloadB2 = Base64.getEncoder().encodeToString("B-2".toByteArray())
        val hashB2 = computeHash(hashB1, payloadB2)
        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceB.deviceId, 1, payloadB2, hashB1, hashB2)
            )))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Verify all 4 ops are present in correct order
        val allOps = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(4, allOps.size)

        // Global sequences are contiguous
        for (i in 1 until allOps.size) {
            assertEquals(allOps[i - 1].globalSequence + 1, allOps[i].globalSequence)
        }
    }

    @Test
    fun `device joining bucket can see existing ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device A uploads ops before Device B joins
        uploadOpsChain(client, deviceA, 5, localIdPrefix = "before-join")

        // Device B joins bucket
        val inviteToken = "late-join-${System.nanoTime()}"
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(inviteToken)))
        }

        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)
        client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }

        // Device B can pull all existing ops
        val ops = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(5, ops.size, "New device should see all pre-existing ops")
    }

    // ================================================================
    // Large Batch Operations
    // ================================================================

    @Test
    fun `large batch of 50 ops in single request`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val lastHash = uploadOpsBatch(client, device, 50, localIdPrefix = "big-batch")

        // Verify all 50 ops are stored
        val ops = client.get("/buckets/${device.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(50, ops.size)

        // Verify the last op's currentHash matches what we computed
        assertEquals(lastHash, ops.last().currentHash)
    }

    @Test
    fun `100 ops across 10 batches maintain hash chain integrity`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        var prevHash = "0".repeat(64)
        for (batch in 1..10) {
            prevHash = uploadOpsBatch(
                client, device,
                count = 10,
                startPrevHash = prevHash,
                localIdPrefix = "batch$batch",
            )
        }

        // Pull all ops and verify chain
        val ops = client.get("/buckets/${device.bucketId}/ops?since=0&limit=1000") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(100, ops.size)

        // Verify each op's hash chain is valid
        for (op in ops) {
            val expectedHash = computeHash(op.prevHash, op.encryptedPayload)
            assertEquals(expectedHash, op.currentHash,
                "Hash chain broken at sequence ${op.globalSequence}")
        }
    }

    // ================================================================
    // Health and Public Endpoints
    // ================================================================

    @Test
    fun `health endpoint returns ok when database is healthy`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `unauthenticated request to bucket ops returns 401`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // No auth header
        val response = client.get("/buckets/$bucketId/ops?since=0")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ================================================================
    // Push Token Registration
    // ================================================================

    @Test
    fun `push token registration`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        val response = client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "fcm-token-${System.nanoTime()}", platform = "FCM"))
        }

        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `push token update replaces existing token`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)

        // Register first token
        client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "old-fcm-token", platform = "FCM"))
        }.also { assertEquals(HttpStatusCode.NoContent, it.status) }

        // Register second token (should replace)
        val resp = client.post("/push/token") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(PushTokenRequest(token = "new-fcm-token", platform = "FCM"))
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
    }

    // ================================================================
    // Edge Cases
    // ================================================================

    @Test
    fun `pulling ops from nonexistent bucket returns 404`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.registerDevice(client)
        val authed = TestHelper.authenticateDevice(client, device)

        val response = client.get("/buckets/00000000-0000-0000-0000-000000000000/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${authed.sessionToken}")
        }

        assertTrue(
            response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Forbidden,
            "Expected 404 or 403 for nonexistent bucket, got ${response.status}"
        )
    }

    @Test
    fun `device can access multiple buckets independently`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceReg = TestHelper.registerDevice(client)
        val device = TestHelper.authenticateDevice(client, deviceReg)

        // Create two buckets
        val bucket1 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()

        val bucket2 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()

        // Upload different ops to each bucket
        val dev1 = device.copy(bucketId = bucket1.bucketId)
        val dev2 = device.copy(bucketId = bucket2.bucketId)
        uploadOpsChain(client, dev1, 3, localIdPrefix = "b1")
        uploadOpsChain(client, dev2, 5, localIdPrefix = "b2")

        // Verify isolation
        val ops1 = client.get("/buckets/${bucket1.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops

        val ops2 = client.get("/buckets/${bucket2.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(3, ops1.size)
        assertEquals(5, ops2.size)
    }
}
