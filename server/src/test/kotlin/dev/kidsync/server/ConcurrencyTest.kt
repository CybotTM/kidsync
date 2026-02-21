package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
import dev.kidsync.server.TestHelper.createJsonClient
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
import kotlin.test.assertTrue

/**
 * Concurrency and interleaving tests for op uploads and invite consumption.
 */
class ConcurrencyTest {

    @Test
    fun `interleaved op uploads from two devices in same bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Interleave: A uploads 5, B uploads 5, A uploads 5, B uploads 5
        var prevHashA = "0".repeat(64)
        var prevHashB = "0".repeat(64)

        prevHashA = uploadOpsBatch(client, deviceA, 5, startPrevHash = prevHashA, localIdPrefix = "A-1")
        prevHashB = uploadOpsBatch(client, deviceB, 5, startPrevHash = prevHashB, localIdPrefix = "B-1")
        prevHashA = uploadOpsBatch(client, deviceA, 5, startPrevHash = prevHashA, localIdPrefix = "A-2")
        prevHashB = uploadOpsBatch(client, deviceB, 5, startPrevHash = prevHashB, localIdPrefix = "B-2")

        // All 20 ops should be present
        val allOps = client.get("/buckets/$bucketId/ops?since=0&limit=1000") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(20, allOps.size)
        assertEquals(10, allOps.count { it.deviceId == deviceA.deviceId })
        assertEquals(10, allOps.count { it.deviceId == deviceB.deviceId })

        // Global sequences should be contiguous
        for (i in 1 until allOps.size) {
            assertEquals(allOps[i - 1].globalSequence + 1, allOps[i].globalSequence,
                "Sequences should be contiguous at index $i")
        }
    }

    @Test
    fun `interleaved single-op uploads from two devices`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!
        val encoder = Base64.getEncoder()
        val sentinel = "0".repeat(64)
        var prevA = sentinel
        var prevB = sentinel

        // Strictly interleave: A1, B1, A2, B2, ...
        for (i in 1..10) {
            val payloadA = encoder.encodeToString("A-$i".toByteArray())
            val hashA = computeHash(prevA, payloadA)
            client.post("/buckets/$bucketId/ops") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
                setBody(OpsBatchRequest(ops = listOf(
                    OpInput(deviceA.deviceId, 1, payloadA, prevA, hashA)
                )))
            }.also { assertEquals(HttpStatusCode.Created, it.status) }
            prevA = hashA

            val payloadB = encoder.encodeToString("B-$i".toByteArray())
            val hashB = computeHash(prevB, payloadB)
            client.post("/buckets/$bucketId/ops") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
                setBody(OpsBatchRequest(ops = listOf(
                    OpInput(deviceB.deviceId, 1, payloadB, prevB, hashB)
                )))
            }.also { assertEquals(HttpStatusCode.Created, it.status) }
            prevB = hashB
        }

        val allOps = client.get("/buckets/$bucketId/ops?since=0&limit=1000") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(20, allOps.size)
        assertEquals(10, allOps.count { it.deviceId == deviceA.deviceId })
        assertEquals(10, allOps.count { it.deviceId == deviceB.deviceId })

        // Contiguous sequences
        for (i in 1 until allOps.size) {
            assertEquals(allOps[i - 1].globalSequence + 1, allOps[i].globalSequence)
        }
    }

    @Test
    fun `invite creation up to limit then consumption frees slot`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Create 5 invites (at limit)
        val tokens = mutableListOf<String>()
        for (i in 1..5) {
            val token = "slot-$i-${System.nanoTime()}"
            tokens.add(token)
            val resp = client.post("/buckets/$bucketId/invite") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
                setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token)))
            }
            assertEquals(HttpStatusCode.Created, resp.status, "Invite $i should succeed")
        }

        // 6th invite should fail
        val token6 = "slot-6-${System.nanoTime()}"
        val resp6 = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token6)))
        }
        assertEquals(429, resp6.status.value)

        // Consume one invite
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)
        val joinResp = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = tokens[0]))
        }
        assertEquals(HttpStatusCode.OK, joinResp.status)

        // Now we should be able to create a new invite (slot freed)
        val token7 = "slot-7-${System.nanoTime()}"
        val resp7 = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token7)))
        }
        assertEquals(HttpStatusCode.Created, resp7.status)
    }

    @Test
    fun `multiple devices joining via different invites sequentially`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceA = TestHelper.setupDeviceWithBucket(client)
        val bucketId = deviceA.bucketId!!

        // Create 2 invites (keep auth request count within rate limit of 10/min)
        val tokens = (1..2).map { "multi-join-$it-${System.nanoTime()}" }
        for (token in tokens) {
            client.post("/buckets/$bucketId/invite") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
                setBody(InviteRequest(tokenHash = HashUtil.sha256HexString(token)))
            }
        }

        // 2 new devices join sequentially
        for (token in tokens) {
            val devReg = TestHelper.registerDevice(client)
            val dev = TestHelper.authenticateDevice(client, devReg)
            val resp = client.post("/buckets/$bucketId/join") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${dev.sessionToken}")
                setBody(JoinBucketRequest(inviteToken = token))
            }
            assertEquals(HttpStatusCode.OK, resp.status,
                "Join should succeed, got ${resp.status}: ${resp.bodyAsText()}")
        }

        // Verify all 3 devices (A + 2 joiners) are listed
        val devices = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<DeviceListResponse>().devices
        assertEquals(3, devices.size)
    }

    @Test
    fun `rapid push and pull cycling maintains consistency`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Rapidly alternate between pushing and pulling
        var prevHash = "0".repeat(64)
        var totalUploaded = 0

        for (cycle in 1..10) {
            // Push 5 ops
            prevHash = uploadOpsBatch(client, device, 5, startPrevHash = prevHash, localIdPrefix = "cycle$cycle")
            totalUploaded += 5

            // Pull all ops
            val ops = client.get("/buckets/$bucketId/ops?since=0&limit=1000") {
                header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            }.body<PullOpsResponse>().ops

            assertEquals(totalUploaded, ops.size, "After cycle $cycle, expected $totalUploaded ops")
        }

        assertEquals(50, totalUploaded)
    }
}
