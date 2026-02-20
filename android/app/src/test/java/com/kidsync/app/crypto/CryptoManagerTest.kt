package com.kidsync.app.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch
import java.util.Base64

/**
 * Tests for TinkCryptoManager covering encrypt/decrypt round-trip,
 * DEK wrap/unwrap, SHA-256 hashing, HKDF derivation, blob encryption,
 * and fingerprint computation.
 */
class CryptoManagerTest : FunSpec({

    val cryptoManager = TinkCryptoManager()

    test("generateDek produces 32-byte key") {
        val dek = cryptoManager.generateDek()
        dek.size shouldBe 32
    }

    test("generateDek produces unique keys") {
        val dek1 = cryptoManager.generateDek()
        val dek2 = cryptoManager.generateDek()
        dek1.toList() shouldNotBe dek2.toList()
    }

    test("encrypt then decrypt round-trip with AAD") {
        val dek = cryptoManager.generateDek()
        val plaintext = """{"payloadType":"CreateEvent","entityId":"test-123"}"""
        val aad = "device-abc-123"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        decrypted shouldBe plaintext
    }

    test("encrypted payload is base64-encoded") {
        val dek = cryptoManager.generateDek()
        val plaintext = "test payload"
        val aad = "device-id"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)

        // Should not throw when decoding base64
        val decoded = Base64.getDecoder().decode(encrypted)
        // Should be at least nonce (12) + some ciphertext + tag (16)
        (decoded.size >= 28) shouldBe true
    }

    test("decrypt with wrong DEK fails") {
        val dek1 = cryptoManager.generateDek()
        val dek2 = cryptoManager.generateDek()
        val plaintext = "secret data"
        val aad = "device"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek1, aad)

        val result = runCatching {
            cryptoManager.decryptPayload(encrypted, dek2, aad)
        }
        result.isFailure shouldBe true
    }

    test("decrypt with wrong AAD fails") {
        val dek = cryptoManager.generateDek()
        val plaintext = "secret data"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek, "correct-aad")

        val result = runCatching {
            cryptoManager.decryptPayload(encrypted, dek, "wrong-aad")
        }
        result.isFailure shouldBe true
    }

    test("encrypt handles empty string") {
        val dek = cryptoManager.generateDek()
        val plaintext = ""
        val aad = "device"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        decrypted shouldBe plaintext
    }

    test("encrypt handles unicode content") {
        val dek = cryptoManager.generateDek()
        val plaintext = """{"title":"Kinderarzttermin","notes":"Bitte Impfpass mitbringen"}"""
        val aad = "device-de"

        val encrypted = cryptoManager.encryptPayload(plaintext, dek, aad)
        val decrypted = cryptoManager.decryptPayload(encrypted, dek, aad)

        decrypted shouldBe plaintext
    }

    test("DEK wrap and unwrap round-trip") {
        val dek = cryptoManager.generateDek()
        val keyPair = cryptoManager.generateX25519KeyPair()
        val deviceId = "test-device-001"
        val keyEpoch = 1

        val wrapped = cryptoManager.wrapDek(
            dek = dek,
            recipientPublicKey = keyPair.public,
            deviceId = deviceId,
            keyEpoch = keyEpoch
        )

        val unwrapped = cryptoManager.unwrapDek(
            wrappedDek = wrapped,
            devicePrivateKey = keyPair.private,
            deviceId = deviceId,
            keyEpoch = keyEpoch
        )

        unwrapped.toList() shouldBe dek.toList()
    }

    test("DEK unwrap with wrong deviceId fails") {
        val dek = cryptoManager.generateDek()
        val keyPair = cryptoManager.generateX25519KeyPair()

        val wrapped = cryptoManager.wrapDek(
            dek = dek,
            recipientPublicKey = keyPair.public,
            deviceId = "correct-device",
            keyEpoch = 1
        )

        val result = runCatching {
            cryptoManager.unwrapDek(
                wrappedDek = wrapped,
                devicePrivateKey = keyPair.private,
                deviceId = "wrong-device",
                keyEpoch = 1
            )
        }
        result.isFailure shouldBe true
    }

    test("DEK unwrap with wrong epoch fails") {
        val dek = cryptoManager.generateDek()
        val keyPair = cryptoManager.generateX25519KeyPair()

        val wrapped = cryptoManager.wrapDek(
            dek = dek,
            recipientPublicKey = keyPair.public,
            deviceId = "device",
            keyEpoch = 1
        )

        val result = runCatching {
            cryptoManager.unwrapDek(
                wrappedDek = wrapped,
                devicePrivateKey = keyPair.private,
                deviceId = "device",
                keyEpoch = 2
            )
        }
        result.isFailure shouldBe true
    }

    test("sha256Hex produces correct hex string") {
        val hash = cryptoManager.sha256Hex("hello")
        hash shouldHaveLength 64
        hash shouldMatch Regex("[0-9a-f]{64}")
        // Known SHA-256 of "hello"
        hash shouldBe "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
    }

    test("sha256Hex is deterministic") {
        val hash1 = cryptoManager.sha256Hex("test data")
        val hash2 = cryptoManager.sha256Hex("test data")
        hash1 shouldBe hash2
    }

    test("hkdfDerive produces correct length output") {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16) { (it + 100).toByte() }
        val info = "test-info".toByteArray()

        val derived = cryptoManager.hkdfDerive(ikm, salt, info, 32)
        derived.size shouldBe 32

        val derived64 = cryptoManager.hkdfDerive(ikm, salt, info, 64)
        derived64.size shouldBe 64
    }

    test("hkdfDerive is deterministic") {
        val ikm = ByteArray(32) { it.toByte() }
        val salt = ByteArray(16)
        val info = "info".toByteArray()

        val d1 = cryptoManager.hkdfDerive(ikm, salt, info, 32)
        val d2 = cryptoManager.hkdfDerive(ikm, salt, info, 32)
        d1.toList() shouldBe d2.toList()
    }

    test("X25519 key pair generation and encoding round-trip") {
        val keyPair = cryptoManager.generateX25519KeyPair()
        val encoded = cryptoManager.encodePublicKey(keyPair.public)
        val decoded = cryptoManager.decodePublicKey(encoded)

        decoded.encoded.toList() shouldBe keyPair.public.encoded.toList()
    }

    test("computeFingerprint is symmetric") {
        val kp1 = cryptoManager.generateX25519KeyPair()
        val kp2 = cryptoManager.generateX25519KeyPair()

        val pk1 = cryptoManager.encodePublicKey(kp1.public)
        val pk2 = cryptoManager.encodePublicKey(kp2.public)

        val fp1 = cryptoManager.computeFingerprint(pk1, pk2)
        val fp2 = cryptoManager.computeFingerprint(pk2, pk1)

        fp1 shouldBe fp2
        fp1 shouldHaveLength 16
    }

    test("blob encrypt and decrypt round-trip") {
        val data = "receipt image data".toByteArray()

        val (encrypted, key) = cryptoManager.encryptBlob(data)
        val decrypted = cryptoManager.decryptBlob(encrypted, key)

        decrypted.toList() shouldBe data.toList()
    }

    test("blob decrypt with wrong key fails") {
        val data = "receipt data".toByteArray()
        val (encrypted, _) = cryptoManager.encryptBlob(data)
        val wrongKey = cryptoManager.generateDek()

        val result = runCatching {
            cryptoManager.decryptBlob(encrypted, wrongKey)
        }
        result.isFailure shouldBe true
    }
})
