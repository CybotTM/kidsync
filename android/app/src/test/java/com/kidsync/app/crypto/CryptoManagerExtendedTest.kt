package com.kidsync.app.crypto

import com.kidsync.app.crypto.KeyManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import io.mockk.mockk
import java.util.Base64

/**
 * Extended tests for TinkCryptoManager covering:
 * - Tampered ciphertext detection
 * - Key fingerprint computation (single key and two-key)
 * - Fingerprint format validation (32 hex chars)
 * - Invite token generation uniqueness
 * - Blob encryption/decryption roundtrip
 * - Zero-length plaintext
 * - Large plaintext
 * - Corrupted base64 input handling
 * - Ed25519 signing and verification
 * - Ed25519-to-X25519 key conversion
 */
class CryptoManagerExtendedTest : FunSpec({

    val lazyKeyManager = dagger.Lazy<KeyManager> { mockk(relaxed = true) }
    val cryptoManager = TinkCryptoManager(lazyKeyManager)

    // ── Tampered Ciphertext ─────────────────────────────────────────────────

    test("decrypt with tampered ciphertext fails") {
        val dek = cryptoManager.generateDek()
        val plaintext = "sensitive custody data"
        val aad = "bucket-123|device-456"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)

        // Decode, tamper with one byte, re-encode
        val bytes = Base64.getDecoder().decode(encrypted)
        // Flip a byte in the ciphertext portion (after nonce)
        if (bytes.size > 14) {
            bytes[14] = (bytes[14].toInt() xor 0xFF).toByte()
        }
        val tampered = Base64.getEncoder().encodeToString(bytes)

        val result = runCatching {
            cryptoManager.decryptPayload(tampered, dek, aad)
        }
        result.isFailure shouldBe true
    }

    // ── Key Fingerprint ─────────────────────────────────────────────────────

    test("computeKeyFingerprint(ByteArray) returns 32 hex char string") {
        val (publicKey, _) = cryptoManager.generateEd25519KeyPair()
        val fingerprint = cryptoManager.computeKeyFingerprint(publicKey)
        fingerprint shouldHaveLength 32
        fingerprint shouldMatch Regex("[0-9a-f]{32}")
    }

    test("computeKeyFingerprint is deterministic") {
        val (publicKey, _) = cryptoManager.generateEd25519KeyPair()
        val fp1 = cryptoManager.computeKeyFingerprint(publicKey)
        val fp2 = cryptoManager.computeKeyFingerprint(publicKey)
        fp1 shouldBe fp2
    }

    test("different keys produce different fingerprints") {
        val (pk1, _) = cryptoManager.generateEd25519KeyPair()
        val (pk2, _) = cryptoManager.generateEd25519KeyPair()
        val fp1 = cryptoManager.computeKeyFingerprint(pk1)
        val fp2 = cryptoManager.computeKeyFingerprint(pk2)
        fp1 shouldNotBe fp2
    }

    test("computeFingerprint (two-key) is 32 hex chars") {
        val kp1 = cryptoManager.generateX25519KeyPair()
        val kp2 = cryptoManager.generateX25519KeyPair()

        val pk1 = cryptoManager.encodePublicKey(kp1.public)
        val pk2 = cryptoManager.encodePublicKey(kp2.public)

        val fp = cryptoManager.computeFingerprint(pk1, pk2)
        fp shouldHaveLength 32
        fp shouldMatch Regex("[0-9a-f]{32}")
    }

    test("computeFingerprint is symmetric (order independent)") {
        val kp1 = cryptoManager.generateX25519KeyPair()
        val kp2 = cryptoManager.generateX25519KeyPair()

        val pk1 = cryptoManager.encodePublicKey(kp1.public)
        val pk2 = cryptoManager.encodePublicKey(kp2.public)

        val fp1 = cryptoManager.computeFingerprint(pk1, pk2)
        val fp2 = cryptoManager.computeFingerprint(pk2, pk1)

        fp1 shouldBe fp2
    }

    test("computeFingerprint with same key yields specific result") {
        val kp = cryptoManager.generateX25519KeyPair()
        val pk = cryptoManager.encodePublicKey(kp.public)

        val fp = cryptoManager.computeFingerprint(pk, pk)
        fp shouldHaveLength 32
    }

    // ── Invite Token ────────────────────────────────────────────────────────

    test("generateInviteToken produces non-empty string") {
        val token = cryptoManager.generateInviteToken()
        (token.isNotBlank()) shouldBe true
    }

    test("generateInviteToken produces unique tokens") {
        val tokens = (1..100).map { cryptoManager.generateInviteToken() }.toSet()
        tokens.size shouldBe 100
    }

    test("generateInviteToken is URL-safe base64") {
        val token = cryptoManager.generateInviteToken()
        // URL-safe base64 should not contain +, /, or = (padding may be stripped)
        // Actually some implementations allow padding, so just check decodeability
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(token)
        }
        decoded.isSuccess shouldBe true
    }

    // ── Blob Encryption Extended ────────────────────────────────────────────

    test("blob encrypt and decrypt roundtrip with large data") {
        val data = ByteArray(1024 * 100) { (it % 256).toByte() } // 100KB
        val blobId = "large-blob-001"

        val (encrypted, key) = cryptoManager.encryptBlob(data, blobId)
        val decrypted = cryptoManager.decryptBlob(encrypted, key, blobId)

        decrypted.toList() shouldBe data.toList()
    }

    test("blob decrypt with wrong blobId fails") {
        val data = "receipt photo".toByteArray()
        val (encrypted, key) = cryptoManager.encryptBlob(data, "correct-blob-id")

        val result = runCatching {
            cryptoManager.decryptBlob(encrypted, key, "wrong-blob-id")
        }
        result.isFailure shouldBe true
    }

    test("blob encrypt produces different ciphertext each time (random nonce)") {
        val data = "same data".toByteArray()
        val blobId = "blob-nonce-test"

        val (enc1, key1) = cryptoManager.encryptBlob(data, blobId)
        val (enc2, key2) = cryptoManager.encryptBlob(data, blobId)

        // Different keys -> different ciphertext
        enc1.toList() shouldNotBe enc2.toList()
    }

    // ── Zero/Max Length Plaintext ────────────────────────────────────────────

    test("zero-length plaintext encryption roundtrip") {
        val dek = cryptoManager.generateDek()
        val encrypted = cryptoManager.encryptPayload("", dek, "aad")
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, "aad")
        decrypted shouldBe ""
    }

    test("large plaintext encryption roundtrip") {
        val dek = cryptoManager.generateDek()
        val plaintext = "x".repeat(100_000) // 100KB of text
        val aad = "device-001"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        decrypted shouldBe plaintext
    }

    // ── Corrupted Base64 Input ──────────────────────────────────────────────

    test("decrypt with corrupted base64 throws") {
        val dek = cryptoManager.generateDek()

        val result = runCatching {
            cryptoManager.decryptPayload("not!!valid!!base64!!", dek, "aad")
        }
        result.isFailure shouldBe true
    }

    test("decrypt with empty string throws") {
        val dek = cryptoManager.generateDek()

        val result = runCatching {
            cryptoManager.decryptPayload("", dek, "aad")
        }
        result.isFailure shouldBe true
    }

    test("decrypt with truncated ciphertext throws") {
        val dek = cryptoManager.generateDek()
        val encrypted = cryptoManager.encryptPayload("test", dek, "aad")
        // Truncate to just a few bytes
        val truncated = encrypted.substring(0, 8)

        val result = runCatching {
            cryptoManager.decryptPayload(truncated, dek, "aad")
        }
        result.isFailure shouldBe true
    }

    // ── Ed25519 Signing ─────────────────────────────────────────────────────

    test("Ed25519 sign and verify roundtrip") {
        val (publicKey, privateKey) = cryptoManager.generateEd25519KeyPair()
        val message = "challenge-nonce-12345".toByteArray()

        val signature = cryptoManager.signEd25519(message, privateKey)
        val valid = cryptoManager.verifyEd25519(message, signature, publicKey)

        valid shouldBe true
    }

    test("Ed25519 signature is 64 bytes") {
        val (_, privateKey) = cryptoManager.generateEd25519KeyPair()
        val signature = cryptoManager.signEd25519("test".toByteArray(), privateKey)
        signature.size shouldBe 64
    }

    test("Ed25519 verify with wrong public key fails") {
        val (_, privateKey1) = cryptoManager.generateEd25519KeyPair()
        val (publicKey2, _) = cryptoManager.generateEd25519KeyPair()

        val message = "test message".toByteArray()
        val signature = cryptoManager.signEd25519(message, privateKey1)

        val valid = cryptoManager.verifyEd25519(message, signature, publicKey2)
        valid shouldBe false
    }

    test("Ed25519 verify with tampered message fails") {
        val (publicKey, privateKey) = cryptoManager.generateEd25519KeyPair()
        val message = "original message".toByteArray()

        val signature = cryptoManager.signEd25519(message, privateKey)
        val valid = cryptoManager.verifyEd25519("tampered message".toByteArray(), signature, publicKey)

        valid shouldBe false
    }

    // ── Ed25519 to X25519 Conversion ────────────────────────────────────────

    test("Ed25519 private key converts to X25519 private key") {
        val (_, ed25519Private) = cryptoManager.generateEd25519KeyPair()
        val x25519Private = cryptoManager.ed25519PrivateToX25519(ed25519Private)
        x25519Private.size shouldBe 32
    }

    test("Ed25519 public key converts to X25519 public key") {
        val (ed25519Public, _) = cryptoManager.generateEd25519KeyPair()
        val x25519Public = cryptoManager.ed25519PublicToX25519(ed25519Public)
        x25519Public.size shouldBe 32
    }

    test("Ed25519 to X25519 conversion is deterministic") {
        val (ed25519Public, ed25519Private) = cryptoManager.generateEd25519KeyPair()

        val x25519Pub1 = cryptoManager.ed25519PublicToX25519(ed25519Public)
        val x25519Pub2 = cryptoManager.ed25519PublicToX25519(ed25519Public)
        x25519Pub1.toList() shouldBe x25519Pub2.toList()

        val x25519Priv1 = cryptoManager.ed25519PrivateToX25519(ed25519Private)
        val x25519Priv2 = cryptoManager.ed25519PrivateToX25519(ed25519Private)
        x25519Priv1.toList() shouldBe x25519Priv2.toList()
    }

    // ── SHA-256 ─────────────────────────────────────────────────────────────

    test("sha256 of empty byte array is known hash") {
        val hash = cryptoManager.sha256(ByteArray(0))
        hash.size shouldBe 32
        // Known SHA-256 of empty input
        val hex = hash.joinToString("") { "%02x".format(it) }
        hex shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }

    test("sha256Hex produces consistent output") {
        val hash1 = cryptoManager.sha256Hex("deterministic input")
        val hash2 = cryptoManager.sha256Hex("deterministic input")
        hash1 shouldBe hash2
    }

    // ── HKDF ────────────────────────────────────────────────────────────────

    test("hkdfDerive with different info produces different keys") {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16)

        val key1 = cryptoManager.hkdfDerive(ikm, salt, "info-1".toByteArray(), 32)
        val key2 = cryptoManager.hkdfDerive(ikm, salt, "info-2".toByteArray(), 32)

        key1.toList() shouldNotBe key2.toList()
    }

    test("hkdfDerive with different salt produces different keys") {
        val ikm = ByteArray(32) { it.toByte() }
        val info = "same-info".toByteArray()

        val key1 = cryptoManager.hkdfDerive(ikm, ByteArray(16) { 1 }, info, 32)
        val key2 = cryptoManager.hkdfDerive(ikm, ByteArray(16) { 2 }, info, 32)

        key1.toList() shouldNotBe key2.toList()
    }

    // ── buildPayloadAad ─────────────────────────────────────────────────────

    test("buildPayloadAad format is bucketId|deviceId") {
        val aad = CryptoManager.buildPayloadAad("bucket-123", "device-456")
        aad shouldBe "bucket-123|device-456"
    }
})
