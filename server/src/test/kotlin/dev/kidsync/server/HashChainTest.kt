package dev.kidsync.server

import dev.kidsync.server.util.HashUtil
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HashChainTest {

    @Test
    fun `sha256Hex produces correct hex hash`() {
        // SHA-256 of empty byte array
        val hash = HashUtil.sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256Hex with multiple parts`() {
        val part1 = "hello".toByteArray()
        val part2 = " world".toByteArray()
        val combined = "hello world".toByteArray()

        val hashMultiPart = HashUtil.sha256Hex(part1, part2)
        val hashCombined = HashUtil.sha256Hex(combined)

        assertEquals(hashCombined, hashMultiPart)
    }

    @Test
    fun `verifyHashChain succeeds for valid chain`() {
        val prevHash = "0".repeat(64)
        val payload = "dGVzdCBwYXlsb2Fk" // base64 of "test payload"

        // Compute what the hash should be
        val prevHashBytes = HashUtil.hexToBytes(prevHash)
        val payloadBytes = Base64.getDecoder().decode(payload)
        val expectedHash = HashUtil.sha256Hex(prevHashBytes, payloadBytes)

        assertTrue(HashUtil.verifyHashChain(prevHash, payload, expectedHash))
    }

    @Test
    fun `verifyHashChain fails for tampered payload`() {
        val prevHash = "0".repeat(64)
        val originalPayload = "dGVzdCBwYXlsb2Fk"
        val tamperedPayload = "dGFtcGVyZWQ=" // base64 of "tampered"

        val prevHashBytes = HashUtil.hexToBytes(prevHash)
        val originalPayloadBytes = Base64.getDecoder().decode(originalPayload)
        val hashForOriginal = HashUtil.sha256Hex(prevHashBytes, originalPayloadBytes)

        // Verification with tampered payload should fail
        assertFalse(HashUtil.verifyHashChain(prevHash, tamperedPayload, hashForOriginal))
    }

    @Test
    fun `hash chain links correctly across multiple ops`() {
        // Simulate a 3-op chain from one device
        val sentinel = "0".repeat(64)
        val payload1 = Base64.getEncoder().encodeToString("op1-data".toByteArray())
        val payload2 = Base64.getEncoder().encodeToString("op2-data".toByteArray())
        val payload3 = Base64.getEncoder().encodeToString("op3-data".toByteArray())

        // Op 1
        val hash1 = HashUtil.sha256Hex(
            HashUtil.hexToBytes(sentinel),
            Base64.getDecoder().decode(payload1),
        )
        assertTrue(HashUtil.verifyHashChain(sentinel, payload1, hash1))

        // Op 2
        val hash2 = HashUtil.sha256Hex(
            HashUtil.hexToBytes(hash1),
            Base64.getDecoder().decode(payload2),
        )
        assertTrue(HashUtil.verifyHashChain(hash1, payload2, hash2))

        // Op 3
        val hash3 = HashUtil.sha256Hex(
            HashUtil.hexToBytes(hash2),
            Base64.getDecoder().decode(payload3),
        )
        assertTrue(HashUtil.verifyHashChain(hash2, payload3, hash3))

        // All three hashes are different
        assertTrue(hash1 != hash2)
        assertTrue(hash2 != hash3)
        assertTrue(hash1 != hash3)
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
    fun `computeCheckpointHash concatenates payloads`() {
        val payload1 = Base64.getEncoder().encodeToString("data1".toByteArray())
        val payload2 = Base64.getEncoder().encodeToString("data2".toByteArray())

        val hash = HashUtil.computeCheckpointHash(listOf(payload1, payload2))

        // It should equal SHA-256("data1" + "data2")
        val expected = HashUtil.sha256Hex("data1".toByteArray(), "data2".toByteArray())
        assertEquals(expected, hash)
    }

    @Test
    fun `sha256HexString produces correct hash`() {
        val hash = HashUtil.sha256HexString("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }
}
