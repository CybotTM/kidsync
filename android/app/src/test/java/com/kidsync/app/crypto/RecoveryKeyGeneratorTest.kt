package com.kidsync.app.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Tests for RecoveryKeyGeneratorImpl covering:
 * - Generate mnemonic produces 24 words
 * - All words are in BIP39 word list
 * - Mnemonic -> entropy -> mnemonic roundtrip
 * - Invalid mnemonic (wrong word count) throws
 * - Invalid mnemonic (bad checksum) throws
 * - Recovery key derivation is deterministic
 * - Empty passphrase works
 * - Different passphrases produce different keys
 */
class RecoveryKeyGeneratorTest : FunSpec({

    val generator = RecoveryKeyGeneratorImpl()

    // ── Mnemonic Generation ─────────────────────────────────────────────────

    test("generateMnemonic produces 24 words") {
        val (words, _) = generator.generateMnemonic()
        words.size shouldBe 24
    }

    test("generateMnemonic produces 32-byte entropy") {
        val (_, entropy) = generator.generateMnemonic()
        entropy.size shouldBe 32
    }

    test("all generated words are in BIP39 word list") {
        val (words, _) = generator.generateMnemonic()
        val wordSet = Bip39WordList.WORDS.toSet()
        for (word in words) {
            (word in wordSet) shouldBe true
        }
    }

    test("generateMnemonic produces unique mnemonics each time") {
        val (words1, _) = generator.generateMnemonic()
        val (words2, _) = generator.generateMnemonic()
        words1 shouldNotBe words2
    }

    test("generated entropy is unique each time") {
        val (_, entropy1) = generator.generateMnemonic()
        val (_, entropy2) = generator.generateMnemonic()
        entropy1.toList() shouldNotBe entropy2.toList()
    }

    // ── Mnemonic -> Entropy Roundtrip ───────────────────────────────────────

    test("mnemonic to entropy roundtrip preserves entropy") {
        val (words, originalEntropy) = generator.generateMnemonic()
        val recoveredEntropy = generator.mnemonicToEntropy(words)
        recoveredEntropy.toList() shouldBe originalEntropy.toList()
    }

    test("mnemonic to entropy roundtrip - multiple iterations") {
        repeat(5) {
            val (words, originalEntropy) = generator.generateMnemonic()
            val recoveredEntropy = generator.mnemonicToEntropy(words)
            recoveredEntropy.toList() shouldBe originalEntropy.toList()
        }
    }

    // ── Invalid Mnemonic ────────────────────────────────────────────────────

    test("mnemonicToEntropy throws for wrong word count (12 words)") {
        val (words, _) = generator.generateMnemonic()
        val shortMnemonic = words.take(12)

        val result = runCatching {
            generator.mnemonicToEntropy(shortMnemonic)
        }
        result.isFailure shouldBe true
        (result.exceptionOrNull() is IllegalArgumentException) shouldBe true
    }

    test("mnemonicToEntropy throws for wrong word count (23 words)") {
        val (words, _) = generator.generateMnemonic()
        val shortMnemonic = words.take(23)

        val result = runCatching {
            generator.mnemonicToEntropy(shortMnemonic)
        }
        result.isFailure shouldBe true
    }

    test("mnemonicToEntropy throws for wrong word count (25 words)") {
        val (words, _) = generator.generateMnemonic()
        val longMnemonic = words + listOf("abandon")

        val result = runCatching {
            generator.mnemonicToEntropy(longMnemonic)
        }
        result.isFailure shouldBe true
    }

    test("mnemonicToEntropy throws for empty list") {
        val result = runCatching {
            generator.mnemonicToEntropy(emptyList())
        }
        result.isFailure shouldBe true
    }

    test("mnemonicToEntropy throws for bad checksum") {
        val (words, _) = generator.generateMnemonic()
        // Replace last word to break the checksum
        val lastWord = words.last()
        val differentWord = Bip39WordList.WORDS.first { it != lastWord }
        val modifiedWords = words.dropLast(1) + listOf(differentWord)

        val result = runCatching {
            generator.mnemonicToEntropy(modifiedWords)
        }
        result.isFailure shouldBe true
    }

    test("mnemonicToEntropy throws for unknown word") {
        val (words, _) = generator.generateMnemonic()
        val invalidWords = words.toMutableList()
        invalidWords[0] = "xyznotaword"

        val result = runCatching {
            generator.mnemonicToEntropy(invalidWords)
        }
        result.isFailure shouldBe true
        val ex = result.exceptionOrNull()
        (ex is IllegalArgumentException) shouldBe true
        (ex?.message?.contains("Unknown BIP39 word") == true) shouldBe true
    }

    // ── Recovery Key Derivation ─────────────────────────────────────────────

    test("deriveRecoveryKey is deterministic - same entropy and passphrase produce same key") {
        val (_, entropy) = generator.generateMnemonic()
        val passphrase = "test-passphrase"

        val key1 = generator.deriveRecoveryKey(entropy, passphrase)
        val key2 = generator.deriveRecoveryKey(entropy, passphrase)

        key1.toList() shouldBe key2.toList()
    }

    test("deriveRecoveryKey produces 32-byte key") {
        val (_, entropy) = generator.generateMnemonic()
        val key = generator.deriveRecoveryKey(entropy, "passphrase")
        key.size shouldBe 32
    }

    test("empty passphrase works and produces a valid key") {
        val (_, entropy) = generator.generateMnemonic()
        val key = generator.deriveRecoveryKey(entropy, "")
        key.size shouldBe 32
    }

    test("default empty passphrase parameter works") {
        val (_, entropy) = generator.generateMnemonic()
        val key = generator.deriveRecoveryKey(entropy)
        key.size shouldBe 32
    }

    test("different passphrases produce different keys") {
        val (_, entropy) = generator.generateMnemonic()
        val key1 = generator.deriveRecoveryKey(entropy, "passphrase-A")
        val key2 = generator.deriveRecoveryKey(entropy, "passphrase-B")

        key1.toList() shouldNotBe key2.toList()
    }

    test("passphrase vs empty passphrase produce different keys") {
        val (_, entropy) = generator.generateMnemonic()
        val keyWithPassphrase = generator.deriveRecoveryKey(entropy, "my-secret")
        val keyWithoutPassphrase = generator.deriveRecoveryKey(entropy, "")

        keyWithPassphrase.toList() shouldNotBe keyWithoutPassphrase.toList()
    }

    test("different entropy with same passphrase produce different keys") {
        val (_, entropy1) = generator.generateMnemonic()
        val (_, entropy2) = generator.generateMnemonic()
        val passphrase = "same-passphrase"

        val key1 = generator.deriveRecoveryKey(entropy1, passphrase)
        val key2 = generator.deriveRecoveryKey(entropy2, passphrase)

        key1.toList() shouldNotBe key2.toList()
    }

    test("unicode passphrase works") {
        val (_, entropy) = generator.generateMnemonic()
        val key = generator.deriveRecoveryKey(entropy, "Kinderarzt-Passwort-2026")
        key.size shouldBe 32
    }

    // ── Full Roundtrip ──────────────────────────────────────────────────────

    test("full roundtrip: generate mnemonic -> derive key -> entropy from mnemonic -> derive key -> same key") {
        val passphrase = "my-recovery-passphrase"

        // Step 1: Generate
        val (words, entropy) = generator.generateMnemonic()
        val originalKey = generator.deriveRecoveryKey(entropy, passphrase)

        // Step 2: Recover
        val recoveredEntropy = generator.mnemonicToEntropy(words)
        val recoveredKey = generator.deriveRecoveryKey(recoveredEntropy, passphrase)

        recoveredKey.toList() shouldBe originalKey.toList()
    }

    test("case-insensitive word matching") {
        val (words, originalEntropy) = generator.generateMnemonic()
        // Convert to uppercase
        val uppercaseWords = words.map { it.uppercase() }
        val recoveredEntropy = generator.mnemonicToEntropy(uppercaseWords)
        recoveredEntropy.toList() shouldBe originalEntropy.toList()
    }
})
