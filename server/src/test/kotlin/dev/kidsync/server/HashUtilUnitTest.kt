package dev.kidsync.server

import dev.kidsync.server.util.HashUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure unit tests for HashUtil - no server, no database.
 */
class HashUtilUnitTest {

    // ================================================================
    // sha256Hex
    // ================================================================

    @Test
    fun `sha256Hex of known input matches expected hash`() {
        // SHA-256("hello world") is well-known
        val hash = HashUtil.sha256Hex("hello world".toByteArray())
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash)
    }

    @Test
    fun `sha256Hex of empty byte array returns known empty hash`() {
        val hash = HashUtil.sha256Hex(ByteArray(0))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256Hex with multiple parts equals single combined input`() {
        val part1 = "hello".toByteArray()
        val part2 = " ".toByteArray()
        val part3 = "world".toByteArray()
        val combined = "hello world".toByteArray()
        assertEquals(HashUtil.sha256Hex(combined), HashUtil.sha256Hex(part1, part2, part3))
    }

    @Test
    fun `sha256Hex with single byte array`() {
        val hash = HashUtil.sha256Hex("test".toByteArray())
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sha256Hex returns lowercase hex`() {
        val hash = HashUtil.sha256Hex("abc".toByteArray())
        assertEquals(hash, hash.lowercase())
    }

    @Test
    fun `sha256Hex produces different hashes for different inputs`() {
        val hash1 = HashUtil.sha256Hex("input1".toByteArray())
        val hash2 = HashUtil.sha256Hex("input2".toByteArray())
        assertTrue(hash1 != hash2)
    }

    @Test
    fun `sha256Hex is deterministic`() {
        val input = "deterministic-test".toByteArray()
        val hash1 = HashUtil.sha256Hex(input)
        val hash2 = HashUtil.sha256Hex(input)
        assertEquals(hash1, hash2)
    }

    // ================================================================
    // sha256HexString
    // ================================================================

    @Test
    fun `sha256HexString of hello produces known hash`() {
        val hash = HashUtil.sha256HexString("hello")
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash)
    }

    @Test
    fun `sha256HexString of empty string produces known hash`() {
        val hash = HashUtil.sha256HexString("")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `sha256HexString handles unicode`() {
        val hash = HashUtil.sha256HexString("hello \u00e9\u00e8\u00ea")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    // ================================================================
    // hexToBytes
    // ================================================================

    @Test
    fun `hexToBytes converts correctly`() {
        val bytes = HashUtil.hexToBytes("00ff10ab")
        assertEquals(4, bytes.size)
        assertEquals(0x00.toByte(), bytes[0])
        assertEquals(0xff.toByte(), bytes[1])
        assertEquals(0x10.toByte(), bytes[2])
        assertEquals(0xab.toByte(), bytes[3])
    }

    @Test
    fun `hexToBytes empty string returns empty array`() {
        val bytes = HashUtil.hexToBytes("")
        assertEquals(0, bytes.size)
    }

    @Test
    fun `hexToBytes all zeros`() {
        val bytes = HashUtil.hexToBytes("0".repeat(64))
        assertEquals(32, bytes.size)
        assertTrue(bytes.all { it == 0.toByte() })
    }

    @Test
    fun `hexToBytes all ff`() {
        val bytes = HashUtil.hexToBytes("ff".repeat(32))
        assertEquals(32, bytes.size)
        assertTrue(bytes.all { it == 0xff.toByte() })
    }

    @Test
    fun `hexToBytes rejects odd-length string`() {
        assertThrows<IllegalArgumentException> {
            HashUtil.hexToBytes("abc")
        }
    }

    // ================================================================
    // verifyHashChain
    // ================================================================

    @Test
    fun `verifyHashChain with valid chain returns true`() {
        val prevHash = "0".repeat(64)
        val payloadBytes = "data".toByteArray()
        val currentHash = HashUtil.sha256Hex(
            HashUtil.hexToBytes(prevHash),
            payloadBytes,
        )
        assertTrue(HashUtil.verifyHashChain(prevHash, payloadBytes, currentHash))
    }

    @Test
    fun `verifyHashChain with tampered payload returns false`() {
        val prevHash = "0".repeat(64)
        val originalBytes = "original".toByteArray()
        val tamperedBytes = "tampered".toByteArray()
        val currentHash = HashUtil.sha256Hex(
            HashUtil.hexToBytes(prevHash),
            originalBytes,
        )
        assertFalse(HashUtil.verifyHashChain(prevHash, tamperedBytes, currentHash))
    }

    @Test
    fun `verifyHashChain with wrong prevHash returns false`() {
        val correctPrev = "0".repeat(64)
        val wrongPrev = "a".repeat(64)
        val payloadBytes = "data".toByteArray()
        val currentHash = HashUtil.sha256Hex(
            HashUtil.hexToBytes(correctPrev),
            payloadBytes,
        )
        assertFalse(HashUtil.verifyHashChain(wrongPrev, payloadBytes, currentHash))
    }

    @Test
    fun `verifyHashChain with wrong currentHash returns false`() {
        val prevHash = "0".repeat(64)
        val payloadBytes = "data".toByteArray()
        val wrongCurrent = "b".repeat(64)
        assertFalse(HashUtil.verifyHashChain(prevHash, payloadBytes, wrongCurrent))
    }

    @Test
    fun `verifyHashChain with genesis hash (all zeros prev)`() {
        val genesis = "0".repeat(64)
        val payloadBytes = "first-op".toByteArray()
        val hash = HashUtil.sha256Hex(
            HashUtil.hexToBytes(genesis),
            payloadBytes,
        )
        assertTrue(HashUtil.verifyHashChain(genesis, payloadBytes, hash))
    }

    @Test
    fun `verifyHashChain multi-hop chain integrity`() {
        val sentinel = "0".repeat(64)
        val payloadBytesList = (1..5).map { "op-$it".toByteArray() }

        var prev = sentinel
        val hashes = mutableListOf<String>()
        for (payloadBytes in payloadBytesList) {
            val hash = HashUtil.sha256Hex(
                HashUtil.hexToBytes(prev),
                payloadBytes,
            )
            hashes.add(hash)
            prev = hash
        }

        // Verify each link in the chain
        prev = sentinel
        for (i in payloadBytesList.indices) {
            assertTrue(HashUtil.verifyHashChain(prev, payloadBytesList[i], hashes[i]),
                "Chain link $i should verify")
            prev = hashes[i]
        }
    }

    @Test
    fun `verifyHashChain broken mid-chain detected`() {
        val sentinel = "0".repeat(64)
        val payload1Bytes = "op-1".toByteArray()
        val hash1 = HashUtil.sha256Hex(
            HashUtil.hexToBytes(sentinel),
            payload1Bytes,
        )
        val payload2Bytes = "op-2".toByteArray()
        val hash2 = HashUtil.sha256Hex(
            HashUtil.hexToBytes(hash1),
            payload2Bytes,
        )

        // Tamper: try to verify op-2 with sentinel instead of hash1
        assertFalse(HashUtil.verifyHashChain(sentinel, payload2Bytes, hash2),
            "Using wrong prevHash should fail verification")
    }

    // ================================================================
    // computeCheckpointHash
    // ================================================================

    @Test
    fun `computeCheckpointHash with empty list returns hash of empty input`() {
        val hash = HashUtil.computeCheckpointHash(emptyList())
        // SHA-256 of empty input
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash)
    }

    @Test
    fun `computeCheckpointHash is deterministic`() {
        val payloads = listOf(
            Base64.getEncoder().encodeToString("a".toByteArray()),
            Base64.getEncoder().encodeToString("b".toByteArray()),
        )
        val hash1 = HashUtil.computeCheckpointHash(payloads)
        val hash2 = HashUtil.computeCheckpointHash(payloads)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `computeCheckpointHash order matters`() {
        val p1 = Base64.getEncoder().encodeToString("a".toByteArray())
        val p2 = Base64.getEncoder().encodeToString("b".toByteArray())
        val hash1 = HashUtil.computeCheckpointHash(listOf(p1, p2))
        val hash2 = HashUtil.computeCheckpointHash(listOf(p2, p1))
        assertTrue(hash1 != hash2, "Different order should produce different hash")
    }

    @Test
    fun `computeCheckpointHash single payload`() {
        val payload = Base64.getEncoder().encodeToString("single".toByteArray())
        val hash = HashUtil.computeCheckpointHash(listOf(payload))
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
