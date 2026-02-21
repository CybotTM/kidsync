package com.kidsync.app.sync

import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.usecase.sync.HashChainBreakException
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * Extended tests for HashChainVerifier covering:
 * - Chain with wrong prevHash fails
 * - Genesis hash format
 * - Chain continuity verification
 * - Multi-device chain isolation
 * - Wrong prevHash in middle of chain
 */
class HashChainExtendedTest : FunSpec({

    val verifier = HashChainVerifier()
    val bucketId = "bucket-test-0000"

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

    // ── Genesis Hash ────────────────────────────────────────────────────────

    test("genesis hash is exactly 64 hex zeros") {
        HashChainVerifier.GENESIS_HASH shouldBe "0000000000000000000000000000000000000000000000000000000000000000"
        HashChainVerifier.GENESIS_HASH.length shouldBe 64
    }

    test("computeHash produces 64-char hex string") {
        val hash = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdA==")
        hash.length shouldBe 64
        hash.all { it in '0'..'9' || it in 'a'..'f' } shouldBe true
    }

    // ── Chain with Wrong prevHash ────────────────────────────────────────────

    test("chain with wrong prevHash fails verification") {
        val hash1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdC1wYXlsb2FkLTE=")
        val hash2 = verifier.computeHash(hash1, "dGVzdC1wYXlsb2FkLTI=")

        // Op 3 has wrong prevHash (uses genesis instead of hash2)
        val wrongPrevHash = HashChainVerifier.GENESIS_HASH
        val hash3Wrong = verifier.computeHash(wrongPrevHash, "dGVzdC1wYXlsb2FkLTM=")

        val ops = listOf(
            makeOp(1, HashChainVerifier.GENESIS_HASH, "dGVzdC1wYXlsb2FkLTE=", hash1),
            makeOp(2, hash1, "dGVzdC1wYXlsb2FkLTI=", hash2),
            makeOp(3, wrongPrevHash, "dGVzdC1wYXlsb2FkLTM=", hash3Wrong)
        )

        val result = verifier.verifyChains(ops)
        result.isFailure shouldBe true
        result.exceptionOrNull().shouldBeInstanceOf<HashChainBreakException>()
    }

    // ── Empty Chain ─────────────────────────────────────────────────────────

    test("empty chain passes verification") {
        val result = verifier.verifyChains(emptyList())
        result.isSuccess shouldBe true
    }

    // ── Single Op Chain ─────────────────────────────────────────────────────

    test("single op chain starting from genesis verifies") {
        val hash = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "c29tZS1kYXRh")
        val ops = listOf(
            makeOp(1, HashChainVerifier.GENESIS_HASH, "c29tZS1kYXRh", hash)
        )
        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("single op with wrong currentHash fails") {
        val ops = listOf(
            makeOp(1, HashChainVerifier.GENESIS_HASH, "c29tZS1kYXRh", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef")
        )
        val result = verifier.verifyChains(ops)
        result.isFailure shouldBe true
    }

    // ── Multi-Device Chain Isolation ────────────────────────────────────────

    test("two devices have independent chains starting from genesis") {
        val deviceA = "device-aaaa"
        val deviceB = "device-bbbb"

        val hashA1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cGF5bG9hZC1hMQ==")
        val hashA2 = verifier.computeHash(hashA1, "cGF5bG9hZC1hMg==")
        val hashB1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cGF5bG9hZC1iMQ==")

        val ops = listOf(
            makeOp(1, HashChainVerifier.GENESIS_HASH, "cGF5bG9hZC1hMQ==", hashA1, deviceA),
            makeOp(2, hashA1, "cGF5bG9hZC1hMg==", hashA2, deviceA),
            makeOp(1, HashChainVerifier.GENESIS_HASH, "cGF5bG9hZC1iMQ==", hashB1, deviceB)
        )

        val result = verifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("tampered payload in device A does not affect device B") {
        val deviceA = "device-aaaa"
        val deviceB = "device-bbbb"

        val hashB1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cGF5bG9hZC1iMQ==")

        // Device A has tampered payload
        val ops = listOf(
            makeOp(1, HashChainVerifier.GENESIS_HASH, "VEFNUEVSRUQtcGF5bG9hZA==", "wronghash0000000000000000000000000000000000000000000000000000", deviceA),
            makeOp(1, HashChainVerifier.GENESIS_HASH, "cGF5bG9hZC1iMQ==", hashB1, deviceB)
        )

        val result = verifier.verifyChains(ops)
        // Should fail because device A's hash is wrong
        result.isFailure shouldBe true
        val ex = result.exceptionOrNull() as HashChainBreakException
        ex.deviceId shouldBe deviceA
    }

    // ── buildChain ──────────────────────────────────────────────────────────

    test("buildChain with empty list returns empty") {
        val hashes = verifier.buildChain(emptyList())
        hashes shouldBe emptyList()
    }

    test("buildChain with single payload") {
        val hashes = verifier.buildChain(listOf("dGVzdA=="))
        hashes.size shouldBe 1
        hashes[0] shouldBe verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdA==")
    }

    test("buildChain produces correct chained hashes") {
        val payloads = listOf("cDE=", "cDI=", "cDM=")
        val hashes = verifier.buildChain(payloads)

        hashes.size shouldBe 3
        // Verify chain property
        val h1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cDE=")
        val h2 = verifier.computeHash(h1, "cDI=")
        val h3 = verifier.computeHash(h2, "cDM=")

        hashes[0] shouldBe h1
        hashes[1] shouldBe h2
        hashes[2] shouldBe h3
    }

    // ── Hash Computation Properties ─────────────────────────────────────────

    test("same inputs produce same hash (deterministic)") {
        val h1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdA==")
        val h2 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdA==")
        h1 shouldBe h2
    }

    test("different payloads produce different hashes") {
        val h1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cDE=")
        val h2 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "cDI=")
        h1 shouldNotBe h2
    }

    test("different prevHash produce different hashes") {
        val h1 = verifier.computeHash(HashChainVerifier.GENESIS_HASH, "dGVzdA==")
        val h2 = verifier.computeHash(h1, "dGVzdA==")
        h1 shouldNotBe h2
    }

    // ── Chain Break Exception ───────────────────────────────────────────────

    test("HashChainBreakException contains correct fields") {
        val ex = HashChainBreakException("device-123", 5, "expected-hash", "actual-hash")
        ex.deviceId shouldBe "device-123"
        ex.deviceSequence shouldBe 5L
        ex.expectedHash shouldBe "expected-hash"
        ex.actualHash shouldBe "actual-hash"
        (ex.message?.contains("device-123") == true) shouldBe true
        (ex.message?.contains("5") == true) shouldBe true
    }
})
