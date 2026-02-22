package com.kidsync.app.sync

import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.usecase.sync.HashChainBreakException
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant
import java.util.Base64

/**
 * Additional tests for HashChainVerifier covering:
 *
 * - Hex input validation (odd-length hex should fail)
 * - Out-of-order ops are re-sorted correctly
 * - Long chains (10+ ops) verify correctly
 * - Chains where the last op is tampered
 * - Three-device chain isolation
 * - buildChain output matches verifyChains expectations
 * - Payload with padding variations
 */
class HashChainVerifierTest : FunSpec({

    val verifier = HashChainVerifier()
    val bucketId = "bucket-verifier-test"

    fun makeOp(
        seq: Long,
        prevHash: String,
        payload: String,
        currentHash: String,
        deviceId: String = "device-default"
    ): OpLogEntry = OpLogEntry(
        globalSequence = seq,
        bucketId = bucketId,
        deviceId = deviceId,
        deviceSequence = seq,
        keyEpoch = 1,
        encryptedPayload = payload,
        devicePrevHash = prevHash,
        currentHash = currentHash,
        serverTimestamp = Instant.now()
    )

    // ── Hex Validation ───────────────────────────────────────────────────────

    test("computeHash with odd-length hex prevHash throws") {
        val result = runCatching {
            verifier.computeHash("abc", "dGVzdA==")
        }
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<IllegalArgumentException>()
    }

    test("computeHash with valid non-zero prevHash") {
        val prevHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val payload = "dGVzdA=="

        val hash = verifier.computeHash(prevHash, payload)
        hash.length shouldBe 64
        hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    // ── Out-of-Order Ops ─────────────────────────────────────────────────────

    test("verifyChains handles out-of-order ops (sorted by deviceSequence)") {
        val hash1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cDE=")
        val hash2 = verifier.computeHash(hash1, "cDI=")
        val hash3 = verifier.computeHash(hash2, "cDM=")

        // Submit ops out of order (3, 1, 2)
        val ops = listOf(
            makeOp(3, hash2, "cDM=", hash3),
            makeOp(1, HashChainVerifier.GENESIS_HASH, "cDE=", hash1),
            makeOp(2, hash1, "cDI=", hash2)
        )

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    // ── Long Chain ───────────────────────────────────────────────────────────

    test("verifyChains with 20-op chain succeeds") {
        val payloads = (1..20).map {
            Base64.getEncoder().encodeToString("payload-$it".toByteArray())
        }
        val hashes = verifier.buildChain(payloads)

        val ops = payloads.mapIndexed { i, payload ->
            val prevHash = if (i == 0) HashChainVerifier.GENESIS_HASH else hashes[i - 1]
            makeOp((i + 1).toLong(), prevHash, payload, hashes[i])
        }

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    // ── Last Op Tampered ─────────────────────────────────────────────────────

    test("verifyChains detects tampered last op in chain") {
        val hash1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cDE=")
        val hash2 = verifier.computeHash(hash1, "cDI=")

        val ops = listOf(
            makeOp(1, HashChainVerifier.GENESIS_HASH, "cDE=", hash1),
            makeOp(2, hash1, "cDI=", hash2),
            // Last op has wrong currentHash
            makeOp(3, hash2, "cDM=", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        )

        val result = verifier.verifyChains(ops)
        result.isFailure shouldBe true
        val ex = result.exceptionOrNull() as HashChainBreakException
        ex.deviceSequence shouldBe 3L
    }

    // ── Three Device Chains ──────────────────────────────────────────────────

    test("three independent device chains all verify") {
        val devices = listOf("device-A", "device-B", "device-C")
        val ops = mutableListOf<OpLogEntry>()

        for (device in devices) {
            val payload = Base64.getEncoder().encodeToString("hello-from-$device".toByteArray())
            val hash = verifier.computeHash(HashChainVerifier.GENESIS_HASH, payload)
            ops.add(makeOp(1, HashChainVerifier.GENESIS_HASH, payload, hash, device))
        }

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("one broken chain among three devices fails") {
        val ops = mutableListOf<OpLogEntry>()

        // Device A - valid
        val payloadA = Base64.getEncoder().encodeToString("A-data".toByteArray())
        val hashA = verifier.computeHash(HashChainVerifier.GENESIS_HASH, payloadA)
        ops.add(makeOp(1, HashChainVerifier.GENESIS_HASH, payloadA, hashA, "device-A"))

        // Device B - broken
        ops.add(makeOp(1, HashChainVerifier.GENESIS_HASH, "cDI=",
            "badhash0000000000000000000000000000000000000000000000000000000000", "device-B"))

        // Device C - valid
        val payloadC = Base64.getEncoder().encodeToString("C-data".toByteArray())
        val hashC = verifier.computeHash(HashChainVerifier.GENESIS_HASH, payloadC)
        ops.add(makeOp(1, HashChainVerifier.GENESIS_HASH, payloadC, hashC, "device-C"))

        val result = verifier.verifyChains(ops)
        result.isFailure shouldBe true
        val ex = result.exceptionOrNull() as HashChainBreakException
        ex.deviceId shouldBe "device-B"
    }

    // ── buildChain + verifyChains Consistency ────────────────────────────────

    test("buildChain output is consistent with verifyChains") {
        val payloads = listOf("cDE=", "cDI=", "cDM=", "cDQ=", "cDU=")
        val hashes = verifier.buildChain(payloads)

        val ops = payloads.mapIndexed { i, payload ->
            val prevHash = if (i == 0) HashChainVerifier.GENESIS_HASH else hashes[i - 1]
            makeOp((i + 1).toLong(), prevHash, payload, hashes[i])
        }

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    // ── Payload Variations ───────────────────────────────────────────────────

    test("computeHash with base64 padding variations") {
        // All these are valid base64 with different padding
        val hash1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "YQ==")   // "a"
        val hash2 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "YWI=")   // "ab"
        val hash3 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "YWJj")   // "abc"

        // Each should produce a unique hash
        hash1 shouldNotBe hash2
        hash2 shouldNotBe hash3
        hash1 shouldNotBe hash3
    }

    // ── Continuity Break (prevHash mismatch) ─────────────────────────────────

    test("verifyChains detects continuity break even when individual hashes are correct") {
        val hash1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cDE=")
        val hash2 = verifier.computeHash(hash1, "cDI=")
        // Op 3: correctly computed hash, but based on a different prevHash (genesis instead of hash2)
        val wrongPrev = HashChainVerifier.GENESIS_HASH
        val hash3 = verifier.computeHash(wrongPrev, "cDM=")

        val ops = listOf(
            makeOp(1, HashChainVerifier.GENESIS_HASH, "cDE=", hash1),
            makeOp(2, hash1, "cDI=", hash2),
            makeOp(3, wrongPrev, "cDM=", hash3) // Valid hash, but breaks continuity
        )

        val result = verifier.verifyChains(ops)
        result.isFailure shouldBe true
        val ex = result.exceptionOrNull() as HashChainBreakException
        ex.deviceSequence shouldBe 3L
    }
})
