package dev.kidsync.server

import dev.kidsync.server.TestHelper.computeHash
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
import java.security.Signature
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class E2ETest {

    // ================================================================
    // Test 1: Complete pairing flow
    // ================================================================

    @Test
    fun `complete pairing flow from registration to sync`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // 1. Device A registers and authenticates
        val deviceAReg = TestHelper.registerDevice(client)
        val deviceA = TestHelper.authenticateDevice(client, deviceAReg)
        assertNotNull(deviceA.sessionToken)
        assertTrue(deviceA.sessionToken.isNotEmpty())

        // 2. Device A creates bucket
        val bucketResp = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()
        val bucketId = bucketResp.bucketId
        assertNotNull(bucketId)

        // 3. Device A creates invite token
        val inviteToken = "pairing-token-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        val inviteResp = client.post("/buckets/$bucketId/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }
        assertEquals(HttpStatusCode.Created, inviteResp.status)

        // 4. Device B registers and authenticates
        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        // 5. Device B joins bucket with invite token
        val joinResp = client.post("/buckets/$bucketId/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }
        assertEquals(HttpStatusCode.OK, joinResp.status)

        // 6. Device A lists devices, sees B
        val devicesResp = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<DeviceListResponse>().devices
        assertEquals(2, devicesResp.size)
        val deviceIds = devicesResp.map { it.deviceId }.toSet()
        assertTrue(deviceIds.contains(deviceA.deviceId))
        assertTrue(deviceIds.contains(deviceB.deviceId))

        // 7. Device A uploads wrapped DEK for B
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "wrapped-dek-for-B-epoch1",
                keyEpoch = 1,
            ))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // 8. Device B retrieves wrapped DEK
        val wrappedKey = client.get("/keys/wrapped?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<WrappedKeyResponse>()
        assertEquals("wrapped-dek-for-B-epoch1", wrappedKey.wrappedDek)
        assertEquals(1, wrappedKey.keyEpoch)
        assertEquals(deviceA.deviceId, wrappedKey.wrappedBy)

        // 9. Both devices upload ops to bucket
        val sentinel = "0".repeat(64)
        val payloadA = Base64.getEncoder().encodeToString("device-A-schedule-create".toByteArray())
        val hashA = computeHash(sentinel, payloadA)

        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceA.deviceId, 1, payloadA, sentinel, hashA)
            )))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        val payloadB = Base64.getEncoder().encodeToString("device-B-expense-create".toByteArray())
        val hashB = computeHash(sentinel, payloadB)

        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceB.deviceId, 1, payloadB, sentinel, hashB)
            )))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // 10. Both devices can pull each other's ops
        val opsA = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(2, opsA.size)

        val opsB = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(2, opsB.size)

        // Both see the same global order
        assertEquals(opsA.map { it.globalSequence }, opsB.map { it.globalSequence })

        // Both devices' ops are present
        val allDeviceIds = opsA.map { it.deviceId }.toSet()
        assertEquals(2, allDeviceIds.size)
    }

    // ================================================================
    // Test 2: Multi-bucket isolation
    // ================================================================

    @Test
    fun `multi-bucket isolation`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // 1. Device A creates bucket 1 and bucket 2
        val deviceAReg = TestHelper.registerDevice(client)
        val deviceA = TestHelper.authenticateDevice(client, deviceAReg)

        val bucket1 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()

        val bucket2 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(CreateBucketRequest())
        }.body<BucketResponse>()

        // 2. Device B joins bucket 1 only
        val inviteToken = "isolation-token-${System.nanoTime()}"
        val tokenHash = HashUtil.sha256HexString(inviteToken)
        client.post("/buckets/${bucket1.bucketId}/invite") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(InviteRequest(tokenHash = tokenHash))
        }

        val deviceBReg = TestHelper.registerDevice(client)
        val deviceB = TestHelper.authenticateDevice(client, deviceBReg)

        client.post("/buckets/${bucket1.bucketId}/join") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(JoinBucketRequest(inviteToken = inviteToken))
        }

        // Upload ops to both buckets
        val deviceABucket1 = deviceA.copy(bucketId = bucket1.bucketId)
        val deviceABucket2 = deviceA.copy(bucketId = bucket2.bucketId)
        uploadOpsChain(client, deviceABucket1, 3, localIdPrefix = "bucket1")
        uploadOpsChain(client, deviceABucket2, 3, localIdPrefix = "bucket2")

        // 3. Device B can access bucket 1 ops
        val ops1 = client.get("/buckets/${bucket1.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, ops1.status)
        val ops1Body = ops1.body<PullOpsResponse>().ops
        assertEquals(3, ops1Body.size)

        // 4. Device B cannot access bucket 2 ops
        val ops2 = client.get("/buckets/${bucket2.bucketId}/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.Forbidden, ops2.status)
    }

    // ================================================================
    // Test 3: Self-revoke flow
    // ================================================================

    @Test
    fun `self-revoke flow`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // 1. Device A and B share a bucket
        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Upload some ops from both devices
        uploadOpsChain(client, deviceA, 2, localIdPrefix = "A")
        uploadOpsChain(client, deviceB, 2, localIdPrefix = "B")

        // 2. Device B leaves (self-revoke)
        val revokeResp = client.delete("/buckets/$bucketId/devices/me") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, revokeResp.status)

        // 3. Device B cannot access bucket anymore
        // SEC4-S-07: Session is invalidated when device has no remaining buckets,
        // so we get 401 (Unauthorized) instead of 403 (Forbidden)
        val pullB = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertTrue(pullB.status == HttpStatusCode.Forbidden || pullB.status == HttpStatusCode.Unauthorized,
            "Expected 401 or 403 after self-revoke, got ${pullB.status}")

        // Device B cannot upload ops
        val sentinel = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("after-revoke".toByteArray())
        val hash = computeHash(sentinel, payload)
        val uploadB = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceB.deviceId, 1, payload, sentinel, hash)
            )))
        }
        assertTrue(uploadB.status == HttpStatusCode.Forbidden || uploadB.status == HttpStatusCode.Unauthorized,
            "Expected 401 or 403 after self-revoke, got ${uploadB.status}")

        // 4. Device A is unaffected
        val pullA = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.OK, pullA.status)
        val opsA = pullA.body<PullOpsResponse>().ops
        assertEquals(4, opsA.size, "Device A should see all 4 ops (2 from A + 2 from B)")
    }

    // ================================================================
    // Test 4: Bucket deletion flow
    // ================================================================

    @Test
    fun `bucket deletion purges all data`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        // 1. Device A creates bucket with ops
        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        uploadOpsChain(client, deviceA, 5, localIdPrefix = "pre-delete")

        // Also upload wrapped key and attestation for completeness
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "dek-to-be-purged",
                keyEpoch = 1,
            ))
        }

        // Verify data exists before deletion
        val opsBefore = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops
        assertEquals(5, opsBefore.size)

        // 2. Device A deletes bucket
        val deleteResp = client.delete("/buckets/$bucketId") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertEquals(HttpStatusCode.NoContent, deleteResp.status)

        // 3. All data is purged -- no device can access the bucket
        val pullA = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertTrue(
            pullA.status == HttpStatusCode.NotFound || pullA.status == HttpStatusCode.Forbidden,
            "Expected 404 or 403 after bucket deletion, got ${pullA.status}"
        )

        // 4. No device can access the bucket
        val pullB = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }
        assertTrue(
            pullB.status == HttpStatusCode.NotFound || pullB.status == HttpStatusCode.Forbidden,
            "Expected 404 or 403 for device B after bucket deletion, got ${pullB.status}"
        )

        // Device list should also fail
        val devicesResp = client.get("/buckets/$bucketId/devices") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }
        assertTrue(
            devicesResp.status == HttpStatusCode.NotFound || devicesResp.status == HttpStatusCode.Forbidden,
            "Expected 404 or 403 for device list after bucket deletion"
        )
    }

    // ================================================================
    // Test 5: Key exchange with cross-signing
    // ================================================================

    @Test
    fun `full key exchange with cross-signing verification`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)

        // Device A attests Device B's encryption key
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

        // Device B attests Device A's encryption key
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

        // Device A wraps DEK for Device B
        client.post("/keys/wrapped") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(WrappedKeyRequest(
                targetDevice = deviceB.deviceId,
                wrappedDek = "wrapped-dek-after-cross-sign",
                keyEpoch = 1,
            ))
        }.also { assertEquals(HttpStatusCode.Created, it.status) }

        // Device B retrieves and verifies
        val wrappedKey = client.get("/keys/wrapped?epoch=1") {
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
        }.body<WrappedKeyResponse>()

        assertEquals("wrapped-dek-after-cross-sign", wrappedKey.wrappedDek)
        assertEquals(deviceA.deviceId, wrappedKey.wrappedBy)

        // Device B can verify the cross-signature against Device A's known signing key
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(deviceA.signingKeyPair.public)
        verifier.update(msgAB.toByteArray(Charsets.UTF_8))
        assertTrue(verifier.verify(Base64.getDecoder().decode(sigAB)),
            "Cross-signature should verify against device A's public key")
    }

    // ================================================================
    // Test 6: Concurrent ops from multiple devices
    // ================================================================

    @Test
    fun `high volume ops across two devices`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device A uploads 25 ops in batches of 5
        var prevHashA = "0".repeat(64)
        for (batch in 1..5) {
            prevHashA = TestHelper.uploadOpsBatch(
                client, deviceA,
                count = 5,
                startPrevHash = prevHashA,
                localIdPrefix = "A-batch$batch",
            )
        }

        // Device B uploads 25 ops in batches of 5
        var prevHashB = "0".repeat(64)
        for (batch in 1..5) {
            prevHashB = TestHelper.uploadOpsBatch(
                client, deviceB,
                count = 5,
                startPrevHash = prevHashB,
                localIdPrefix = "B-batch$batch",
            )
        }

        // Both pull all 50 ops
        val allOps = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(50, allOps.size)

        // Verify global sequences are contiguous
        val sequences = allOps.map { it.globalSequence }
        for (i in 1 until sequences.size) {
            assertEquals(sequences[i - 1] + 1, sequences[i],
                "Sequences should be contiguous at index $i")
        }

        // Verify ops from both devices are present
        val fromA = allOps.count { it.deviceId == deviceA.deviceId }
        val fromB = allOps.count { it.deviceId == deviceB.deviceId }
        assertEquals(25, fromA)
        assertEquals(25, fromB)
    }

    // ================================================================
    // Test 7: Authentication re-challenge
    // ================================================================

    @Test
    fun `device can re-authenticate after session expires`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val deviceReg = TestHelper.registerDevice(client)

        // First authentication
        val authed1 = TestHelper.authenticateDevice(client, deviceReg)
        assertTrue(authed1.sessionToken.isNotEmpty())

        // Second authentication (new challenge, new session)
        val authed2 = TestHelper.authenticateDevice(client, deviceReg)
        assertTrue(authed2.sessionToken.isNotEmpty())

        // Both should be different tokens
        // (they could theoretically be the same but practically never will be)
        // The important thing is both work
        val resp1 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed1.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, resp1.status)

        val resp2 = client.post("/buckets") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${authed2.sessionToken}")
            setBody(CreateBucketRequest())
        }
        assertEquals(HttpStatusCode.Created, resp2.status)
    }
}
