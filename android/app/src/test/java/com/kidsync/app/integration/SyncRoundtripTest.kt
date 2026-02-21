package com.kidsync.app.integration

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.crypto.TinkCryptoManager
import com.kidsync.app.domain.model.OpLogEntry
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import java.time.Instant
import java.util.Base64

/**
 * Integration tests for the sync roundtrip:
 * - Create op -> encrypt -> build hash chain -> verify chain
 * - Multi-op push with correct device sequence numbering
 * - Canonical JSON -> encrypt -> decrypt -> verify content
 */
class SyncRoundtripTest : FunSpec({

    val lazyKeyManager = dagger.Lazy<KeyManager> { mockk(relaxed = true) }
    val cryptoManager = TinkCryptoManager(lazyKeyManager)
    val hashChainVerifier = HashChainVerifier()
    val canonicalSerializer = CanonicalJsonSerializer()
    val bucketId = "bucket-roundtrip-test"
    val deviceId = "device-roundtrip-001"

    test("create op -> encrypt -> build hash chain -> verify chain") {
        val dek = cryptoManager.generateDek()
        val aad = "$bucketId|$deviceId"

        // Build 3 operations
        val plainPayloads = listOf(
            """{"data":{"title":"Event 1"},"deviceSequence":1,"entityId":"e1","entityType":"CalendarEvent","operation":"CREATE","protocolVersion":2}""",
            """{"data":{"title":"Event 2"},"deviceSequence":2,"entityId":"e2","entityType":"CalendarEvent","operation":"CREATE","protocolVersion":2}""",
            """{"data":{"title":"Event 3"},"deviceSequence":3,"entityId":"e3","entityType":"CalendarEvent","operation":"CREATE","protocolVersion":2}"""
        )

        val encryptedPayloads = plainPayloads.map { cryptoManager.encryptPayload(it, dek, aad) }

        // Build hash chain
        val hashes = hashChainVerifier.buildChain(encryptedPayloads)
        hashes.size shouldBe 3

        // Verify each hash is correctly chained
        val ops = encryptedPayloads.mapIndexed { i, enc ->
            val prevHash = if (i == 0) HashChainVerifier.GENESIS_HASH else hashes[i - 1]
            OpLogEntry(
                globalSequence = (i + 1).toLong(),
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = (i + 1).toLong(),
                keyEpoch = 1,
                encryptedPayload = enc,
                devicePrevHash = prevHash,
                currentHash = hashes[i],
                serverTimestamp = Instant.now()
            )
        }

        val result = hashChainVerifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("multi-op push with correct device sequence numbering") {
        val dek = cryptoManager.generateDek()
        val aad = "$bucketId|$deviceId"

        var prevHash = HashChainVerifier.GENESIS_HASH
        val ops = mutableListOf<OpLogEntry>()

        for (seq in 1L..5L) {
            val payload = canonicalSerializer.serialize(mapOf(
                "deviceSequence" to seq,
                "entityType" to "Expense",
                "entityId" to "exp-$seq",
                "operation" to "CREATE",
                "protocolVersion" to 2,
                "data" to mapOf("amount" to seq * 1000)
            ))

            val encrypted = cryptoManager.encryptPayload(payload, dek, aad)
            val currentHash = hashChainVerifier.computeHash(prevHash, encrypted)

            ops.add(OpLogEntry(
                globalSequence = seq,
                bucketId = bucketId,
                deviceId = deviceId,
                deviceSequence = seq,
                keyEpoch = 1,
                encryptedPayload = encrypted,
                devicePrevHash = prevHash,
                currentHash = currentHash,
                serverTimestamp = Instant.now()
            ))

            prevHash = currentHash
        }

        // Verify entire chain
        val result = hashChainVerifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }

    test("decrypt all ops and verify content is preserved") {
        val dek = cryptoManager.generateDek()
        val aad = "$bucketId|$deviceId"

        val originalPayloads = listOf(
            mapOf<String, Any?>(
                "data" to mapOf("childId" to "child-001", "title" to "Doctor"),
                "deviceSequence" to 1,
                "entityId" to "evt-001",
                "entityType" to "CalendarEvent",
                "operation" to "CREATE",
                "protocolVersion" to 2
            ),
            mapOf<String, Any?>(
                "data" to mapOf("childId" to "child-001", "amountCents" to 5000),
                "deviceSequence" to 2,
                "entityId" to "exp-001",
                "entityType" to "Expense",
                "operation" to "CREATE",
                "protocolVersion" to 2
            )
        )

        val encryptedPayloads = originalPayloads.map { data ->
            val canonical = canonicalSerializer.serialize(data)
            cryptoManager.encryptPayload(canonical, dek, aad)
        }

        // Decrypt and verify
        for (i in originalPayloads.indices) {
            val decrypted = cryptoManager.decryptPayload(encryptedPayloads[i], dek, aad)
            val expected = canonicalSerializer.serialize(originalPayloads[i])
            decrypted shouldBe expected
        }
    }

    test("tampered encrypted payload breaks hash chain verification") {
        val dek = cryptoManager.generateDek()
        val aad = "$bucketId|$deviceId"

        val enc1 = cryptoManager.encryptPayload("payload-1", dek, aad)
        val enc2 = cryptoManager.encryptPayload("payload-2", dek, aad)
        val enc3 = cryptoManager.encryptPayload("payload-3", dek, aad)

        val hashes = hashChainVerifier.buildChain(listOf(enc1, enc2, enc3))

        // Tamper with enc2 but keep original hash
        val tamperedEnc2 = cryptoManager.encryptPayload("TAMPERED-payload-2", dek, aad)

        val ops = listOf(
            OpLogEntry(1, bucketId, deviceId, 1, 1, enc1, HashChainVerifier.GENESIS_HASH, hashes[0], Instant.now()),
            OpLogEntry(2, bucketId, deviceId, 2, 1, tamperedEnc2, hashes[0], hashes[1], Instant.now()),
            OpLogEntry(3, bucketId, deviceId, 3, 1, enc3, hashes[1], hashes[2], Instant.now())
        )

        val result = hashChainVerifier.verifyChains(ops)
        result.isFailure shouldBe true
    }

    test("two-device sync: independent chains both verify") {
        val dek = cryptoManager.generateDek()
        val deviceA = "device-aaa"
        val deviceB = "device-bbb"

        val aadA = "$bucketId|$deviceA"
        val aadB = "$bucketId|$deviceB"

        val encA1 = cryptoManager.encryptPayload("A-op1", dek, aadA)
        val encA2 = cryptoManager.encryptPayload("A-op2", dek, aadA)
        val encB1 = cryptoManager.encryptPayload("B-op1", dek, aadB)

        val hashA1 = hashChainVerifier.computeHash(HashChainVerifier.GENESIS_HASH, encA1)
        val hashA2 = hashChainVerifier.computeHash(hashA1, encA2)
        val hashB1 = hashChainVerifier.computeHash(HashChainVerifier.GENESIS_HASH, encB1)

        val ops = listOf(
            OpLogEntry(1, bucketId, deviceA, 1, 1, encA1, HashChainVerifier.GENESIS_HASH, hashA1, Instant.now()),
            OpLogEntry(2, bucketId, deviceB, 1, 1, encB1, HashChainVerifier.GENESIS_HASH, hashB1, Instant.now()),
            OpLogEntry(3, bucketId, deviceA, 2, 1, encA2, hashA1, hashA2, Instant.now())
        )

        val result = hashChainVerifier.verifyChains(ops)
        result.isSuccess shouldBe true
    }
})
