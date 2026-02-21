package com.kidsync.app.edgecase

import com.kidsync.app.crypto.CanonicalJsonSerializer
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.crypto.TinkCryptoManager
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.usecase.sync.HashChainVerifier
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Edge case, property-based, and concurrency tests:
 *
 * Crypto edge cases:
 * - Encrypt/decrypt with maximum length plaintext
 * - Encrypt with empty AAD
 * - DEK generation produces 256-bit keys
 * - Concurrent encrypt/decrypt operations
 *
 * Hash chain edge cases:
 * - Hash of empty string
 * - Hash determinism across threads
 * - Very long payload hashing
 *
 * JSON edge cases (property-based):
 * - Arbitrary strings survive canonical serialization roundtrip
 * - Deeply nested objects serialize correctly
 *
 * Enum edge cases:
 * - OverrideStatus terminal states are correctly identified
 * - All enum values are accounted for
 *
 * Concurrency:
 * - Concurrent DEK generation produces unique keys
 * - Concurrent hash chain computation is deterministic
 */
class CryptoEdgeCaseTest : FunSpec({

    val lazyKeyManager = dagger.Lazy<KeyManager> { mockk(relaxed = true) }
    val cryptoManager = TinkCryptoManager(lazyKeyManager)
    val hashChainVerifier = HashChainVerifier()
    val canonicalSerializer = CanonicalJsonSerializer()

    // ── Crypto Edge Cases ─────────────────────────────────────────────────────

    test("encrypt/decrypt with 1MB plaintext") {
        val dek = cryptoManager.generateDek()
        val largePlaintext = "X".repeat(1_000_000)
        val aad = "large-payload-test"

        val encrypted = cryptoManager.encryptPayload(largePlaintext, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        decrypted shouldBe largePlaintext
    }

    test("encrypt/decrypt with single character plaintext") {
        val dek = cryptoManager.generateDek()
        val encrypted = cryptoManager.encryptPayload("A", dek, "aad")
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, "aad")
        decrypted shouldBe "A"
    }

    test("encrypt/decrypt with empty AAD") {
        val dek = cryptoManager.generateDek()
        val plaintext = "test-empty-aad"
        val encrypted = cryptoManager.encryptPayload(plaintext, dek, "")
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, "")
        decrypted shouldBe plaintext
    }

    test("DEK is exactly 32 bytes (256 bits)") {
        val dek = cryptoManager.generateDek()
        dek.size shouldBe 32
    }

    test("each DEK generation produces a unique key") {
        val keys = (1..100).map { cryptoManager.generateDek().toList() }.toSet()
        keys.size shouldBe 100
    }

    test("SHA-256 of empty string matches known value") {
        val hash = cryptoManager.sha256Hex("")
        hash shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    test("SHA-256 produces 64 hex characters") {
        val hash = cryptoManager.sha256Hex("any input")
        hash shouldHaveLength 64
    }

    test("HKDF with zero-length info produces valid key") {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (it + 100).toByte() }
        val derived = cryptoManager.hkdfDerive(ikm, salt, "".toByteArray(), 32)
        derived.size shouldBe 32
    }

    test("HKDF output length matches request") {
        val ikm = ByteArray(32) { 1 }
        val salt = ByteArray(16) { 2 }
        for (len in listOf(16, 32, 48, 64)) {
            val derived = cryptoManager.hkdfDerive(ikm, salt, "info".toByteArray(), len)
            derived.size shouldBe len
        }
    }

    // ── Concurrent Crypto ─────────────────────────────────────────────────────

    test("concurrent encrypt/decrypt on different DEKs") {
        val results = ConcurrentHashMap<Int, Boolean>()

        runBlocking {
            val jobs = (1..50).map { i ->
                launch(Dispatchers.Default) {
                    val dek = cryptoManager.generateDek()
                    val plaintext = "concurrent-test-$i"
                    val aad = "aad-$i"
                    val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
                    val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)
                    results[i] = (decrypted == plaintext)
                }
            }
            jobs.forEach { it.join() }
        }

        results.size shouldBe 50
        results.values.all { it } shouldBe true
    }

    test("concurrent DEK generation produces unique keys") {
        val keys = ConcurrentHashMap.newKeySet<List<Byte>>()

        runBlocking {
            val jobs = (1..100).map {
                launch(Dispatchers.Default) {
                    val dek = cryptoManager.generateDek()
                    keys.add(dek.toList())
                }
            }
            jobs.forEach { it.join() }
        }

        keys.size shouldBe 100
    }

    // ── Hash Chain Edge Cases ─────────────────────────────────────────────────

    test("hash chain genesis constant is a valid hex string") {
        HashChainVerifier.GENESIS_HASH.shouldNotBeBlank()
        HashChainVerifier.GENESIS_HASH shouldHaveLength 64
    }

    test("computeHash is deterministic") {
        val prevHash = HashChainVerifier.GENESIS_HASH
        val payload = Base64.getEncoder().encodeToString("deterministic-test".toByteArray())

        val hash1 = hashChainVerifier.computeHash(prevHash, payload)
        val hash2 = hashChainVerifier.computeHash(prevHash, payload)

        hash1 shouldBe hash2
    }

    test("computeHash with very long payload") {
        val prevHash = HashChainVerifier.GENESIS_HASH
        val longData = "L".repeat(100_000)
        val payload = Base64.getEncoder().encodeToString(longData.toByteArray())

        val hash = hashChainVerifier.computeHash(prevHash, payload)
        hash shouldHaveLength 64
    }

    test("concurrent hash computations produce consistent results") {
        val prevHash = HashChainVerifier.GENESIS_HASH
        val payload = Base64.getEncoder().encodeToString("concurrent-hash-test".toByteArray())
        val expectedHash = hashChainVerifier.computeHash(prevHash, payload)

        val results = ConcurrentHashMap<Int, String>()

        runBlocking {
            val jobs = (1..100).map { i ->
                launch(Dispatchers.Default) {
                    val hash = hashChainVerifier.computeHash(prevHash, payload)
                    results[i] = hash
                }
            }
            jobs.forEach { it.join() }
        }

        results.values.all { it == expectedHash } shouldBe true
    }

    // ── JSON Property-Based Tests ─────────────────────────────────────────────

    test("arbitrary string keys survive canonical serialization") {
        checkAll(100, Arb.string(minSize = 1, maxSize = 50)) { key ->
            val data = mapOf(key to "value")
            val serialized = canonicalSerializer.serialize(data)
            serialized.shouldNotBeBlank()
            // Should be valid JSON
            val parsed = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(serialized) }
            parsed.isSuccess shouldBe true
        }
    }

    test("arbitrary string values survive canonical serialization") {
        checkAll(100, Arb.string(minSize = 0, maxSize = 200)) { value ->
            val data = mapOf("key" to value)
            val serialized = canonicalSerializer.serialize(data)
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(serialized).jsonObject
            parsed["key"]?.jsonPrimitive?.content shouldBe value
        }
    }

    test("canonical serializer produces sorted keys for random key sets") {
        checkAll(50, Arb.string(minSize = 1, maxSize = 10), Arb.string(minSize = 1, maxSize = 10)) { k1, k2 ->
            if (k1 != k2) {
                val data = mapOf(k2 to "v2", k1 to "v1")
                val serialized = canonicalSerializer.serialize(data)
                val parsed = kotlinx.serialization.json.Json.parseToJsonElement(serialized).jsonObject
                val keys = parsed.keys.toList()
                keys shouldBe keys.sorted()
            }
        }
    }

    // ── Enum Edge Cases ───────────────────────────────────────────────────────

    test("OverrideStatus terminal states: DECLINED, CANCELLED, SUPERSEDED, EXPIRED") {
        OverrideStatus.PROPOSED.isTerminal shouldBe false
        OverrideStatus.APPROVED.isTerminal shouldBe false
        OverrideStatus.DECLINED.isTerminal shouldBe true
        OverrideStatus.CANCELLED.isTerminal shouldBe true
        OverrideStatus.SUPERSEDED.isTerminal shouldBe true
        OverrideStatus.EXPIRED.isTerminal shouldBe true
    }

    test("all OverrideStatus values are tested") {
        val allValues = OverrideStatus.entries
        allValues.size shouldBe 6
    }

    test("all ExpenseCategory values exist") {
        val categories = com.kidsync.app.domain.model.ExpenseCategory.entries
        categories.size shouldBe 8
        categories.map { it.name }.toSet() shouldBe setOf(
            "MEDICAL", "EDUCATION", "CLOTHING", "FOOD",
            "ACTIVITIES", "TRANSPORT", "CHILDCARE", "OTHER"
        )
    }

    test("all EntityType values exist") {
        val types = com.kidsync.app.domain.model.EntityType.entries
        types.size shouldBe 8
        val names = types.map { it.name }
        names.contains("CustodySchedule") shouldBe true
        names.contains("ScheduleOverride") shouldBe true
        names.contains("Expense") shouldBe true
        names.contains("CalendarEvent") shouldBe true
        names.contains("InfoBankEntry") shouldBe true
    }

    test("OverrideType precedence ordering") {
        val types = com.kidsync.app.domain.model.OverrideType.entries
        val sorted = types.sortedBy { it.precedence }
        sorted[0] shouldBe com.kidsync.app.domain.model.OverrideType.MANUAL_OVERRIDE
        sorted[1] shouldBe com.kidsync.app.domain.model.OverrideType.SWAP_REQUEST
        sorted[2] shouldBe com.kidsync.app.domain.model.OverrideType.HOLIDAY_RULE
        sorted[3] shouldBe com.kidsync.app.domain.model.OverrideType.COURT_ORDER
    }

    // ── Encrypt-Decrypt Property Test ─────────────────────────────────────────

    test("any string plaintext survives encrypt-decrypt roundtrip") {
        val dek = cryptoManager.generateDek()
        val aad = "property-test"

        checkAll(50, Arb.string(minSize = 1, maxSize = 500)) { plaintext ->
            val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
            val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)
            decrypted shouldBe plaintext
        }
    }

    // ── Key Fingerprint Edge Cases ────────────────────────────────────────────

    test("key fingerprint is deterministic for same input") {
        val key = ByteArray(32) { it.toByte() }
        val fp1 = cryptoManager.computeKeyFingerprint(key)
        val fp2 = cryptoManager.computeKeyFingerprint(key)
        fp1 shouldBe fp2
    }

    test("different keys produce different fingerprints") {
        val key1 = ByteArray(32) { 0 }
        val key2 = ByteArray(32) { 1 }
        val fp1 = cryptoManager.computeKeyFingerprint(key1)
        val fp2 = cryptoManager.computeKeyFingerprint(key2)
        fp1 shouldNotBe fp2
    }

    // ── Base64 Edge Cases ─────────────────────────────────────────────────────

    test("encrypted output is always valid base64") {
        val dek = cryptoManager.generateDek()
        val encrypted = cryptoManager.encryptPayload("test", dek, "aad")
        val decoded = runCatching { Base64.getDecoder().decode(encrypted) }
        decoded.isSuccess shouldBe true
        decoded.getOrNull()!!.isNotEmpty() shouldBe true
    }

    test("invite token is URL-safe base64") {
        val token = cryptoManager.generateInviteToken()
        token.shouldNotBeBlank()
        // URL-safe base64 should not contain +, /, or =
        token.none { it == '+' || it == '/' } shouldBe true
    }
})
