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
import java.security.Signature
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end tests covering complete multi-device pairing scenarios.
 */
class TwoDevicePairingE2ETest {

    @Test
    fun `complete two-device pairing with key exchange and sync`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // Device A registers
        val deviceAReg = TestHelper.registerDevice(client)
        val deviceA = TestHelper.authenticateDevice(client, deviceAReg)

        // Device A creates bucket
        val bucketResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()
        val bucketId = bucketResp.bucketId

        // Device A creates invite
        val inviteToken = "pairing-e2e-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }

        // Device B registers and authenticates
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        // Device B joins via invite
        val joinResp = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }
        assertEquals(HttpStatusCode.OK, joinResp.status)

        // Device A uploads wrapped DEK for B
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "dek-for-B-epoch1-padded-to-meet-minimum-length",
                keyEpoch = 1,
            ))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Device B retrieves wrapped DEK
        val wrappedKey = client.get("/keys/wrapped?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<WrappedKeyResponse>()
        assertEquals("dek-for-B-epoch1-padded-to-meet-minimum-length", wrappedKey.wrappedDek)
        assertEquals(deviceA.deviceId, wrappedKey.wrappedBy)

        // Both devices exchange key attestations
        // Device A attests B
        val msgAB = "${deviceB.deviceId}${deviceB.encryptionKeyBase64}"
        val signerA = Signature.getInstance("Ed25519")
        signerA.initSign(deviceA.signingKeyPair.private)
        signerA.update(msgAB.toByteArray(Charsets.UTF_8))
        val sigAB = Base64.getEncoder().encodeToString(signerA.sign())

        client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceB.deviceId,
                attestedKey = deviceB.encryptionKeyBase64,
                signature = sigAB,
            ))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Device B attests A
        val msgBA = "${deviceA.deviceId}${deviceA.encryptionKeyBase64}"
        val signerB = Signature.getInstance("Ed25519")
        signerB.initSign(deviceB.signingKeyPair.private)
        signerB.update(msgBA.toByteArray(Charsets.UTF_8))
        val sigBA = Base64.getEncoder().encodeToString(signerB.sign())

        client.post("/keys/attestations") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(KeyAttestationRequest(
                attestedDevice = deviceA.deviceId,
                attestedKey = deviceA.encryptionKeyBase64,
                signature = sigBA,
            ))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Both devices push ops
        val deviceABucket = deviceA.copy(bucketId = bucketId)
        val deviceBBucket = deviceB.copy(bucketId = bucketId)

        val lastHashA = uploadOpsBatch(client, deviceABucket, 5, localIdPrefix = "A-ops")
        val lastHashB = uploadOpsBatch(client, deviceBBucket, 5, localIdPrefix = "B-ops")
        assertTrue(lastHashA.isNotEmpty())
        assertTrue(lastHashB.isNotEmpty())

        // Both devices pull and see all 10 ops
        val opsA = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(10, opsA.size)

        val opsB = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(10, opsB.size)

        // Same global order
        assertEquals(opsA.map { it.globalSequence }, opsB.map { it.globalSequence })

        // Verify attestations are retrievable
        val attestsA = client.get("/keys/attestations/${deviceA.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<AttestationListResponse>().attestations
        assertEquals(1, attestsA.size)

        val attestsB = client.get("/keys/attestations/${deviceB.deviceId}") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<AttestationListResponse>().attestations
        assertEquals(1, attestsB.size)
    }

    @Test
    fun `two devices with independent hash chains push and pull concurrently`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Interleave uploads: A1, B1, A2, B2, ...
        val sentinel = "0".repeat(64)
        val encoder = Base64.getEncoder()
        var prevA = sentinel
        var prevB = sentinel

        for (i in 1..10) {
            // Device A op
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

            // Device B op
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

        // Both see all 20 ops
        val allOps = client.get("/buckets/$bucketId/ops?since=0&limit=1000") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(20, allOps.size)
        assertEquals(10, allOps.count { it.deviceId == deviceA.deviceId })
        assertEquals(10, allOps.count { it.deviceId == deviceB.deviceId })

        // Verify contiguous sequences
        for (i in 1 until allOps.size) {
            assertEquals(allOps[i - 1].globalSequence + 1, allOps[i].globalSequence)
        }
    }

    @Test
    fun `device join then leave then ops still visible to remaining device`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Both devices upload ops
        TestHelper.uploadOpsChain(client, deviceA, 3, localIdPrefix = "A")
        TestHelper.uploadOpsChain(client, deviceB, 3, localIdPrefix = "B")

        // Device B leaves
        client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }

        // Device A still sees all 6 ops (including B's)
        val ops = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(6, ops.size)
        assertTrue(ops.any { it.deviceId == deviceB.deviceId },
            "Ops from left device should remain visible")
    }
}
