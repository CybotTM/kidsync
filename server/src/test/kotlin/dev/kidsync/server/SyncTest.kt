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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SyncTest {

    // ================================================================
    // Op Upload
    // ================================================================

    @Test
    fun `single op upload succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("test-payload".toByteArray())
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
        val body = response.body<OpsBatchResponse>()
        assertEquals(1, body.accepted.size)
        assertTrue(body.latestSequence > 0)
        assertEquals(0, body.accepted[0].index)
        assertTrue(body.accepted[0].globalSequence > 0)
        assertTrue(body.accepted[0].serverTimestamp.isNotEmpty())
    }

    @Test
    fun `batch upload succeeds`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash1 = "0".repeat(64)
        val payload1 = Base64.getEncoder().encodeToString("payload-1".toByteArray())
        val hash1 = computeHash(prevHash1, payload1)
        val payload2 = Base64.getEncoder().encodeToString("payload-2".toByteArray())
        val hash2 = computeHash(hash1, payload2)
        val payload3 = Base64.getEncoder().encodeToString("payload-3".toByteArray())
        val hash3 = computeHash(hash2, payload3)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(
                    OpInput(device.deviceId, 1, payload1, prevHash1, hash1),
                    OpInput(device.deviceId, 1, payload2, hash1, hash2),
                    OpInput(device.deviceId, 1, payload3, hash2, hash3),
                )
            ))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val body = response.body<OpsBatchResponse>()
        assertEquals(3, body.accepted.size)
    }

    @Test
    fun `server assigns global sequence numbers`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload 3 ops in chain
        uploadOpsChain(client, device, 3)

        // Pull and verify sequences are contiguous
        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, pullResp.status)
        val ops = pullResp.body<PullOpsResponse>().ops
        assertEquals(3, ops.size)

        for (i in 1 until ops.size) {
            assertEquals(ops[i - 1].globalSequence + 1, ops[i].globalSequence,
                "Sequences should be contiguous")
        }
    }

    @Test
    fun `server adds server timestamp to ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsChain(client, device, 1)

        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, pullResp.status)
        val ops = pullResp.body<PullOpsResponse>().ops
        assertEquals(1, ops.size)

        assertNotNull(ops[0].serverTimestamp)
        assertTrue(ops[0].serverTimestamp.isNotEmpty())
    }

    @Test
    fun `encrypted payload is stored opaque`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload an op with arbitrary opaque payload
        val opaquePayload = Base64.getEncoder().encodeToString(
            "this-could-be-anything-binary-or-json".toByteArray()
        )
        val prevHash = "0".repeat(64)
        val curHash = computeHash(prevHash, opaquePayload)

        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(
                    deviceId = device.deviceId,
                    keyEpoch = 1,
                    encryptedPayload = opaquePayload,
                    prevHash = prevHash,
                    currentHash = curHash,
                ))
            ))
        }

        // Pull and verify payload is returned unmodified
        val pullResp = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }
        val ops = pullResp.body<PullOpsResponse>().ops
        assertEquals(opaquePayload, ops[0].encryptedPayload)
    }

    @Test
    fun `upload rejects empty ops batch`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(ops = emptyList()))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ================================================================
    // Op Pull
    // ================================================================

    @Test
    fun `pull ops after given sequence`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsChain(client, device, 5)

        // Pull ops after sequence 0 (all)
        val allOps = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(5, allOps.size)

        // Pull ops after the 3rd one
        val sinceSeq = allOps[2].globalSequence
        val laterOps = client.get("/buckets/$bucketId/ops?since=$sinceSeq") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(2, laterOps.size)
        assertTrue(laterOps.all { it.globalSequence > sinceSeq })
    }

    @Test
    fun `pull returns empty when no new ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        uploadOpsChain(client, device, 3)

        // Get all ops
        val allOps = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops
        val lastSeq = allOps.last().globalSequence

        // Pull since last -- should be empty
        val newOps = client.get("/buckets/$bucketId/ops?since=$lastSeq") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(0, newOps.size)
    }

    @Test
    fun `pull respects bucket access`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload ops
        uploadOpsChain(client, device, 3)

        // Device without access tries to pull
        val outsiderReg = TestHelper.registerDevice(client)
        val outsider = TestHelper.authenticateDevice(client, outsiderReg)

        val response = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${outsider.sessionToken}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `revoked device cannot pull ops`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        uploadOpsChain(client, deviceA, 3)

        // Device B self-revokes
        client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Device B tries to pull
        val response = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    // ================================================================
    // Bucket Isolation
    // ================================================================

    @Test
    fun `ops from bucket A not visible in bucket B`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Device creates two buckets
        val deviceReg = TestHelper.registerDevice(client)
        val device = TestHelper.authenticateDevice(client, deviceReg)

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

        // Upload ops to bucket 1
        val deviceWithBucket1 = device.copy(bucketId = bucket1.bucketId)
        uploadOpsChain(client, deviceWithBucket1, 5)

        // Pull from bucket 2 -- should be empty
        val ops2 = client.get("/buckets/${bucket2.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(0, ops2.size)

        // Pull from bucket 1 -- should have 5
        val ops1 = client.get("/buckets/${bucket1.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(5, ops1.size)
    }

    @Test
    fun `device with access to A but not B cannot pull from B`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Device A creates bucket 1
        val deviceA = TestHelper.setupDeviceWithBucket(client)

        // Device A creates a second bucket
        val bucket2 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()

        // Device B joins bucket 1 only
        val bucketId1 = deviceA.bucketId!!
        val inviteToken = "cross-bucket-token-${System.nanoTime()}"
        val tokenHash = dev.kidsync.server.util.HashUtil.sha256HexString(inviteToken)
        client.post("/buckets/$bucketId1/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }

        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)
        client.post("/buckets/$bucketId1/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }

        // Device B can pull from bucket 1
        val resp1 = client.get("/buckets/$bucketId1/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, resp1.status)

        // Device B cannot pull from bucket 2
        val resp2 = client.get("/buckets/${bucket2.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, resp2.status)
    }

    @Test
    fun `two devices see each others ops in shared bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device A uploads 3 ops
        uploadOpsChain(client, deviceA, 3, localIdPrefix = "devA")

        // Device B uploads 3 ops (own hash chain)
        uploadOpsChain(client, deviceB, 3, localIdPrefix = "devB")

        // Both pull all ops
        val opsA = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops

        val opsB = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(6, opsA.size)
        assertEquals(6, opsB.size)

        // Both see same global sequences in same order
        assertEquals(
            opsA.map { it.globalSequence },
            opsB.map { it.globalSequence },
        )

        // Both devices' ops are present
        val deviceIds = opsA.map { it.deviceId }.toSet()
        assertEquals(2, deviceIds.size)
        assertTrue(deviceIds.contains(deviceA.deviceId))
        assertTrue(deviceIds.contains(deviceB.deviceId))
    }
}
