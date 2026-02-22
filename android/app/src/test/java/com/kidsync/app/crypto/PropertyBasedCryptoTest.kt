package com.kidsync.app.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.byte
import io.kotest.property.arbitrary.byteArray
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.kotest.property.forAll
import io.mockk.mockk
import java.security.MessageDigest
import java.util.Base64

/**
 * Property-based tests for cryptographic operations.
 * Uses Kotest's property testing to verify invariants hold across random inputs.
 */
class PropertyBasedCryptoTest : FunSpec({

    val lazyKeyManager = dagger.Lazy<KeyManager> { mockk(relaxed = true) }
    val cryptoManager = TinkCryptoManager(lazyKeyManager)
    val canonicalSerializer = CanonicalJsonSerializer()

    // ── Encrypt/Decrypt Round-Trip ──────────────────────────────────────────

    test("encrypt-decrypt round-trip preserves any plaintext") {
        val dek = cryptoManager.generateDek()
        checkAll(100, Arb.string(1..500)) { plaintext ->
            val aad = "bucket-1|device-1"
            val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
            val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)
            decrypted shouldBe plaintext
        }
    }

    test("encrypt-decrypt round-trip works with varying AAD") {
        val dek = cryptoManager.generateDek()
        val plaintext = """{"test":"data"}"""
        checkAll(100, Arb.string(1..200)) { aad ->
            val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
            val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)
            decrypted shouldBe plaintext
        }
    }

    test("encryption is non-deterministic (different ciphertext for same plaintext)") {
        val dek = cryptoManager.generateDek()
        val plaintext = "identical-plaintext"
        val aad = "test-aad"

        val encrypted1 = cryptoManager.encryptPayload(plaintext, dek, aad)
        val encrypted2 = cryptoManager.encryptPayload(plaintext, dek, aad)

        // IVs should differ, making ciphertext different
        encrypted1 shouldNotBe encrypted2

        // But both should decrypt to the same plaintext
        cryptoManager.decryptPayload(encrypted1, dek, aad) shouldBe plaintext
        cryptoManager.decryptPayload(encrypted2, dek, aad) shouldBe plaintext
    }

    // ── SHA-256 Properties ──────────────────────────────────────────────────

    test("sha256 produces 32-byte output for any input") {
        forAll(100, Arb.string(0..1000)) { input ->
            val hash = cryptoManager.sha256(input.toByteArray())
            hash.size == 32
        }
    }

    test("sha256 is deterministic") {
        checkAll(100, Arb.string(1..500)) { input ->
            val hash1 = cryptoManager.sha256(input.toByteArray())
            val hash2 = cryptoManager.sha256(input.toByteArray())
            hash1.toList() shouldBe hash2.toList()
        }
    }

    test("sha256Hex produces 64-char hex string") {
        forAll(100, Arb.string(0..500)) { input ->
            val hex = cryptoManager.sha256Hex(input)
            hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }
        }
    }

    // ── Ed25519 Signing ─────────────────────────────────────────────────────

    test("Ed25519 sign-verify round-trip for any message") {
        val (publicKey, privateKey) = cryptoManager.generateEd25519KeyPair()
        checkAll(100, Arb.string(1..500)) { message ->
            val messageBytes = message.toByteArray()
            val signature = cryptoManager.signEd25519(messageBytes, privateKey)
            cryptoManager.verifyEd25519(messageBytes, signature, publicKey) shouldBe true
        }
    }

    test("Ed25519 signature is always 64 bytes") {
        val (_, privateKey) = cryptoManager.generateEd25519KeyPair()
        checkAll(100, Arb.string(1..500)) { message ->
            val signature = cryptoManager.signEd25519(message.toByteArray(), privateKey)
            signature.size shouldBe 64
        }
    }

    test("Ed25519 verification fails with wrong public key") {
        val (_, privateKey1) = cryptoManager.generateEd25519KeyPair()
        val (publicKey2, _) = cryptoManager.generateEd25519KeyPair()
        checkAll(50, Arb.string(1..200)) { message ->
            val messageBytes = message.toByteArray()
            val signature = cryptoManager.signEd25519(messageBytes, privateKey1)
            cryptoManager.verifyEd25519(messageBytes, signature, publicKey2) shouldBe false
        }
    }

    test("Ed25519 verification fails with tampered message") {
        val (publicKey, privateKey) = cryptoManager.generateEd25519KeyPair()
        val message = "original message".toByteArray()
        val signature = cryptoManager.signEd25519(message, privateKey)

        val tampered = "tampered message".toByteArray()
        cryptoManager.verifyEd25519(tampered, signature, publicKey) shouldBe false
    }

    // ── DEK Generation ──────────────────────────────────────────────────────

    test("generateDek always produces 32-byte keys") {
        repeat(50) {
            val dek = cryptoManager.generateDek()
            dek.size shouldBe 32
        }
    }

    test("generated DEKs are unique") {
        val deks = (1..50).map { cryptoManager.generateDek().toList() }.toSet()
        deks.size shouldBe 50
    }

    // ── Blob Encryption ─────────────────────────────────────────────────────

    test("blob encrypt-decrypt round-trip for any data") {
        checkAll(50, Arb.byteArray(Arb.int(1..200), Arb.byte())) { data ->
            val blobId = "blob-test-${data.hashCode()}"
            val (encrypted, key) = cryptoManager.encryptBlob(data, blobId)
            val decrypted = cryptoManager.decryptBlob(encrypted, key, blobId)
            decrypted.toList() shouldBe data.toList()
        }
    }

    // ── Canonical JSON Properties ───────────────────────────────────────────

    test("canonical serialization is deterministic") {
        checkAll(50, Arb.string(1..100), Arb.string(1..100)) { key1, value1 ->
            val data = mapOf("z_key" to value1, "a_key" to key1)
            val json1 = canonicalSerializer.serialize(data)
            val json2 = canonicalSerializer.serialize(data)
            json1 shouldBe json2
        }
    }

    test("canonical serialization sorts keys lexicographically") {
        forAll(50, Arb.string(1..50), Arb.string(1..50)) { val1, val2 ->
            val data = mapOf("zebra" to val1, "alpha" to val2)
            val json = canonicalSerializer.serialize(data)
            val alphaIdx = json.indexOf("\"alpha\"")
            val zebraIdx = json.indexOf("\"zebra\"")
            alphaIdx < zebraIdx
        }
    }
})
