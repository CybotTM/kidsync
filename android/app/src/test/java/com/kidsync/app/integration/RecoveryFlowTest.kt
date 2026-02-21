package com.kidsync.app.integration

import com.kidsync.app.crypto.RecoveryKeyGeneratorImpl
import com.kidsync.app.crypto.TinkCryptoManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Integration tests for the recovery flow:
 * - Generate mnemonic -> derive recovery key -> wrap DEK -> unwrap DEK
 * - Recovery with passphrase -> verify all epochs restored
 * - Multi-epoch DEK wrapping/unwrapping
 */
class RecoveryFlowTest : FunSpec({

    val cryptoManager = TinkCryptoManager()
    val recoveryKeyGenerator = RecoveryKeyGeneratorImpl()

    test("generate mnemonic -> derive recovery key -> wrap and unwrap single DEK") {
        // Step 1: Generate mnemonic and derive recovery key
        val (words, entropy) = recoveryKeyGenerator.generateMnemonic()
        val passphrase = "test-passphrase-2026"
        val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(entropy, passphrase)

        // Step 2: Wrap a DEK with the recovery key (simulating backup)
        val dek = cryptoManager.generateDek()
        val blobId = "recovery-blob-001"
        val wrappedDek = cryptoManager.encryptPayload(
            plaintext = java.util.Base64.getEncoder().encodeToString(dek),
            dek = recoveryKey,
            aad = blobId
        )

        // Step 3: Recover - derive the same recovery key from mnemonic
        val recoveredEntropy = recoveryKeyGenerator.mnemonicToEntropy(words)
        val recoveredKey = recoveryKeyGenerator.deriveRecoveryKey(recoveredEntropy, passphrase)

        // Step 4: Unwrap the DEK
        val unwrappedDekBase64 = cryptoManager.decryptPayload(wrappedDek, recoveredKey, blobId)
        val unwrappedDek = java.util.Base64.getDecoder().decode(unwrappedDekBase64)

        unwrappedDek.toList() shouldBe dek.toList()
    }

    test("recovery with passphrase: wrong passphrase fails unwrap") {
        val (words, entropy) = recoveryKeyGenerator.generateMnemonic()
        val correctPassphrase = "correct-passphrase"
        val wrongPassphrase = "wrong-passphrase"

        val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(entropy, correctPassphrase)

        val dek = cryptoManager.generateDek()
        val blobId = "recovery-blob-002"
        val wrappedDek = cryptoManager.encryptPayload(
            plaintext = java.util.Base64.getEncoder().encodeToString(dek),
            dek = recoveryKey,
            aad = blobId
        )

        // Try to recover with wrong passphrase
        val recoveredEntropy = recoveryKeyGenerator.mnemonicToEntropy(words)
        val wrongKey = recoveryKeyGenerator.deriveRecoveryKey(recoveredEntropy, wrongPassphrase)

        val result = runCatching {
            cryptoManager.decryptPayload(wrappedDek, wrongKey, blobId)
        }
        result.isFailure shouldBe true
    }

    test("multi-epoch DEK wrapping and unwrapping via recovery key") {
        val (words, entropy) = recoveryKeyGenerator.generateMnemonic()
        val passphrase = "multi-epoch-pass"
        val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(entropy, passphrase)

        // Simulate multiple epochs
        val epochs = (1..5).map { epoch ->
            val dek = cryptoManager.generateDek()
            val blobId = "recovery-epoch-$epoch"
            val wrapped = cryptoManager.encryptPayload(
                plaintext = java.util.Base64.getEncoder().encodeToString(dek),
                dek = recoveryKey,
                aad = blobId
            )
            Triple(epoch, dek, wrapped)
        }

        // Recover all epochs
        val recoveredEntropy = recoveryKeyGenerator.mnemonicToEntropy(words)
        val recoveredKey = recoveryKeyGenerator.deriveRecoveryKey(recoveredEntropy, passphrase)

        for ((epoch, originalDek, wrappedDek) in epochs) {
            val blobId = "recovery-epoch-$epoch"
            val unwrappedBase64 = cryptoManager.decryptPayload(wrappedDek, recoveredKey, blobId)
            val unwrappedDek = java.util.Base64.getDecoder().decode(unwrappedBase64)
            unwrappedDek.toList() shouldBe originalDek.toList()
        }
    }

    test("recovery without passphrase: empty passphrase roundtrip") {
        val (words, entropy) = recoveryKeyGenerator.generateMnemonic()
        val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(entropy) // empty passphrase

        val dek = cryptoManager.generateDek()
        val blobId = "recovery-no-pass"
        val wrappedDek = cryptoManager.encryptPayload(
            plaintext = java.util.Base64.getEncoder().encodeToString(dek),
            dek = recoveryKey,
            aad = blobId
        )

        val recoveredEntropy = recoveryKeyGenerator.mnemonicToEntropy(words)
        val recoveredKey = recoveryKeyGenerator.deriveRecoveryKey(recoveredEntropy)

        val unwrapped = cryptoManager.decryptPayload(wrappedDek, recoveredKey, blobId)
        val unwrappedDek = java.util.Base64.getDecoder().decode(unwrapped)
        unwrappedDek.toList() shouldBe dek.toList()
    }

    test("different mnemonics produce different recovery keys") {
        val (_, entropy1) = recoveryKeyGenerator.generateMnemonic()
        val (_, entropy2) = recoveryKeyGenerator.generateMnemonic()

        val key1 = recoveryKeyGenerator.deriveRecoveryKey(entropy1, "same-pass")
        val key2 = recoveryKeyGenerator.deriveRecoveryKey(entropy2, "same-pass")

        key1.toList() shouldNotBe key2.toList()
    }
})
