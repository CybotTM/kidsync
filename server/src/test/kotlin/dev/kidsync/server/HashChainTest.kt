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
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HashChainTest {

    // ================================================================
    // Hash Computation
    // ================================================================

    @Test
    fun `hash computation is SHA-256 of prevHash bytes plus payload bytes`() {
        val prevHash = "0".repeat(64) // 32 zero bytes
        val payload = Base64.getEncoder().encodeToString("test-data".toByteArray())

        val hash = computeHash(prevHash, payload)

        // Verify manually: SHA-256(0x00*32 + "test-data")
        val prevBytes = HashUtil.hexToBytes(prevHash)
        val payloadBytes = Base64.getDecoder().decode(payload)
        val expected = HashUtil.sha256Hex(prevBytes, payloadBytes)

        assertEquals(expected, hash)
        assertEquals(64, hash.length) // 32 bytes = 64 hex chars
    }

    @Test
    fun `genesis hash starts from all zeros`() {
        val genesisHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("first-op".toByteArray())

        val hash = computeHash(genesisHash, payload)

        // Should be a valid 64-char hex string
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })

        // Should NOT be all zeros
        assertTrue(hash != genesisHash)
    }

    @Test
    fun `hash chain links correctly across multiple ops`() {
        val sentinel = "0".repeat(64)
        val payload1 = Base64.getEncoder().encodeToString("op1-data".toByteArray())
        val payload2 = Base64.getEncoder().encodeToString("op2-data".toByteArray())
        val payload3 = Base64.getEncoder().encodeToString("op3-data".toByteArray())

        val hash1 = computeHash(sentinel, payload1)
        val hash2 = computeHash(hash1, payload2)
        val hash3 = computeHash(hash2, payload3)

        // All hashes are different
        assertTrue(hash1 != hash2)
        assertTrue(hash2 != hash3)
        assertTrue(hash1 != hash3)

        // Verify each link
        assertTrue(HashUtil.verifyHashChain(sentinel, payload1, hash1))
        assertTrue(HashUtil.verifyHashChain(hash1, payload2, hash2))
        assertTrue(HashUtil.verifyHashChain(hash2, payload3, hash3))
    }

    @Test
    fun `hash chain detects tampered payload`() {
        val prevHash = "0".repeat(64)
        val originalPayload = Base64.getEncoder().encodeToString("original".toByteArray())
        val tamperedPayload = Base64.getEncoder().encodeToString("tampered".toByteArray())

        val hashForOriginal = computeHash(prevHash, originalPayload)

        // Verification with tampered payload should fail
        assertFalse(HashUtil.verifyHashChain(prevHash, tamperedPayload, hashForOriginal))
    }

    @Test
    fun `hash chain detects wrong prevHash`() {
        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())
        val correctHash = computeHash(prevHash, payload)

        val wrongPrevHash = "a".repeat(64)
        assertFalse(HashUtil.verifyHashChain(wrongPrevHash, payload, correctHash))
    }

    @Test
    fun `same payload with different prevHash produces different hash`() {
        val payload = Base64.getEncoder().encodeToString("same-data".toByteArray())

        val hash1 = computeHash("0".repeat(64), payload)
        val hash2 = computeHash("a".repeat(64), payload)

        assertTrue(hash1 != hash2)
    }

    // ================================================================
    // Chain Validation on Upload
    // ================================================================

    @Test
    fun `valid hash chain accepted by server`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("valid-chain".toByteArray())
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
    fun `invalid prevHash rejected by server`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        // Upload first op
        val prevHash1 = "0".repeat(64)
        val payload1 = Base64.getEncoder().encodeToString("first".toByteArray())
        val hash1 = computeHash(prevHash1, payload1)

        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload1, prevHash1, hash1))
            ))
        }

        // Upload second op with WRONG prevHash
        val wrongPrevHash = "a".repeat(64) // Should be hash1
        val payload2 = Base64.getEncoder().encodeToString("second".toByteArray())
        val hash2 = computeHash(wrongPrevHash, payload2)

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload2, wrongPrevHash, hash2))
            ))
        }

        assertEquals(HttpStatusCode.Conflict, response.status,
            "Expected 409 Conflict for broken hash chain, got ${response.status}")
    }

    @Test
    fun `invalid currentHash rejected by server`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val device = TestHelper.setupDeviceWithBucket(client)
        val bucketId = device.bucketId!!

        val prevHash = "0".repeat(64)
        val payload = Base64.getEncoder().encodeToString("data".toByteArray())
        val wrongCurrentHash = "b".repeat(64) // Not the correct SHA-256

        val response = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${device.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(OpInput(device.deviceId, 1, payload, prevHash, wrongCurrentHash))
            ))
        }

        assertEquals(HttpStatusCode.Conflict, response.status,
            "Expected 409 Conflict for wrong currentHash, got ${response.status}")
    }

    // ================================================================
    // Per-Device Chains
    // ================================================================

    @Test
    fun `two devices maintain independent chains in same bucket`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!

        // Device A uploads ops with its own chain
        val sentinel = "0".repeat(64)
        val payloadA1 = Base64.getEncoder().encodeToString("deviceA-op1".toByteArray())
        val hashA1 = computeHash(sentinel, payloadA1)
        val payloadA2 = Base64.getEncoder().encodeToString("deviceA-op2".toByteArray())
        val hashA2 = computeHash(hashA1, payloadA2)

        val respA = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(
                    OpInput(deviceA.deviceId, 1, payloadA1, sentinel, hashA1),
                    OpInput(deviceA.deviceId, 1, payloadA2, hashA1, hashA2),
                )
            ))
        }
        assertEquals(HttpStatusCode.Created, respA.status)

        // Device B uploads ops with its OWN independent chain (also starts from sentinel)
        val payloadB1 = Base64.getEncoder().encodeToString("deviceB-op1".toByteArray())
        val hashB1 = computeHash(sentinel, payloadB1)
        val payloadB2 = Base64.getEncoder().encodeToString("deviceB-op2".toByteArray())
        val hashB2 = computeHash(hashB1, payloadB2)

        val respB = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(OpsBatchRequest(
                ops = listOf(
                    OpInput(deviceB.deviceId, 1, payloadB1, sentinel, hashB1),
                    OpInput(deviceB.deviceId, 1, payloadB2, hashB1, hashB2),
                )
            ))
        }
        assertEquals(HttpStatusCode.Created, respB.status)

        // Both devices' ops should be in the bucket
        val allOps = client.get("/buckets/$bucketId/ops?since=0") {
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
        }.body<PullOpsResponse>().ops

        assertEquals(4, allOps.size)

        // Verify device A's chain
        val deviceAOps = allOps.filter { it.deviceId == deviceA.deviceId }
        assertEquals(2, deviceAOps.size)
        assertEquals(sentinel, deviceAOps[0].prevHash)
        assertEquals(hashA1, deviceAOps[0].currentHash)
        assertEquals(hashA1, deviceAOps[1].prevHash)
        assertEquals(hashA2, deviceAOps[1].currentHash)

        // Verify device B's chain
        val deviceBOps = allOps.filter { it.deviceId == deviceB.deviceId }
        assertEquals(2, deviceBOps.size)
        assertEquals(sentinel, deviceBOps[0].prevHash)
        assertEquals(hashB1, deviceBOps[0].currentHash)
        assertEquals(hashB1, deviceBOps[1].prevHash)
        assertEquals(hashB2, deviceBOps[1].currentHash)
    }

    @Test
    fun `chain continues correctly after interleaved uploads`() = testApplication {
        application { module(testConfig()) }
        val client = createJsonClient()

        val (deviceA, deviceB) = TestHelper.setupTwoDeviceBucket(client)
        val bucketId = deviceA.bucketId!!
        val sentinel = "0".repeat(64)

        // Device A: op 1
        val payloadA1 = Base64.getEncoder().encodeToString("A1".toByteArray())
        val hashA1 = computeHash(sentinel, payloadA1)
        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceA.deviceId, 1, payloadA1, sentinel, hashA1)
            )))
        }

        // Device B: op 1
        val payloadB1 = Base64.getEncoder().encodeToString("B1".toByteArray())
        val hashB1 = computeHash(sentinel, payloadB1)
        client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceB.deviceId, 1, payloadB1, sentinel, hashB1)
            )))
        }

        // Device A: op 2 (continues from hashA1, not hashB1)
        val payloadA2 = Base64.getEncoder().encodeToString("A2".toByteArray())
        val hashA2 = computeHash(hashA1, payloadA2)
        val respA2 = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceA.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceA.deviceId, 1, payloadA2, hashA1, hashA2)
            )))
        }
        assertEquals(HttpStatusCode.Created, respA2.status,
            "Device A's second op should chain from its own previous hash, not device B's")

        // Device B: op 2 (continues from hashB1)
        val payloadB2 = Base64.getEncoder().encodeToString("B2".toByteArray())
        val hashB2 = computeHash(hashB1, payloadB2)
        val respB2 = client.post("/buckets/$bucketId/ops") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer ${deviceB.sessionToken}")
            setBody(OpsBatchRequest(ops = listOf(
                OpInput(deviceB.deviceId, 1, payloadB2, hashB1, hashB2)
            )))
        }
        assertEquals(HttpStatusCode.Created, respB2.status)
    }

    // ================================================================
    // HashUtil Unit Tests
    // ================================================================

    @Test
    fun `sha256Hex of empty byte array`() {
        val hash = HashUtil.sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256Hex with multiple parts equals combined`() {
        val part1 = "hello".toByteArray()
        val part2 = " world".toByteArray()
        val combined = "hello world".toByteArray()

        assertEquals(HashUtil.sha256Hex(combined), HashUtil.sha256Hex(part1, part2))
    }

    @Test
    fun `hexToBytes converts correctly`() {
        val hex = "00ff10ab"
        val bytes = HashUtil.hexToBytes(hex)
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0xff.toByte(), bytes[1])
        assertEquals(0x10.toByte(), bytes[2])
        assertEquals(0xab.toByte(), bytes[3])
    }

    @Test
    fun `sha256HexString produces correct hash`() {
        val hash = HashUtil.sha256HexString("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }
}
