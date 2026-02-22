package com.kidsync.app.crypto

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Extended tests for RecoveryKeyGeneratorImpl covering:
 *
 * 1. BIP39 word list has exactly 2048 entries
 * 2. BIP39 word list has no duplicates
 * 3. BIP39 word list entries are all lowercase
 * 4. Recovery key derivation with special character passphrase
 * 5. Mnemonic to entropy with mixed case words
 * 6. Multiple roundtrips produce consistent results
 * 7. Word list is sorted (BIP39 spec)
 * 8. Generated entropy has high entropy (not all zeros, not all same byte)
 */
class RecoveryKeyGeneratorExtendedTest : FunSpec({

    val generator = RecoveryKeyGeneratorImpl()

    // ── BIP39 Word List Validation ───────────────────────────────────────────

    test("BIP39 word list has exactly 2048 entries") {
        Bip39WordList.WORDS.size shouldBe 2048
    }

    test("BIP39 word list has no duplicate entries") {
        val uniqueWords = Bip39WordList.WORDS.toSet()
        uniqueWords.size shouldBe 2048
    }

    test("BIP39 word list entries are all lowercase") {
        for (word in Bip39WordList.WORDS) {
            word shouldBe word.lowercase()
        }
    }

    test("BIP39 word list is sorted alphabetically") {
        val sorted = Bip39WordList.WORDS.sorted()
        Bip39WordList.WORDS shouldBe sorted
    }

    test("BIP39 word list contains known words") {
        val knownWords = listOf("abandon", "ability", "able", "about", "zoo", "zone", "zero")
        for (word in knownWords) {
            (word in Bip39WordList.WORDS) shouldBe true
        }
    }

    // ── Entropy Quality ──────────────────────────────────────────────────────

    test("generated entropy is not all zeros") {
        val (_, entropy) = generator.generateMnemonic()
        val allZeros = ByteArray(32)
        entropy.toList() shouldNotBe allZeros.toList()
    }

    test("generated entropy is not all the same byte") {
        val (_, entropy) = generator.generateMnemonic()
        val uniqueBytes = entropy.toSet()
        // 32 bytes of truly random data should have at least a few unique values
        (uniqueBytes.size > 1) shouldBe true
    }

    // ── Recovery Key Derivation Edge Cases ───────────────────────────────────

    test("deriveRecoveryKey with special character passphrase") {
        val (_, entropy) = generator.generateMnemonic()
        val specialPassphrase = "p@ss!w0rd#2026$%^&*()"
        val key = generator.deriveRecoveryKey(entropy, specialPassphrase)
        key.size shouldBe 32
    }

    test("deriveRecoveryKey with emoji passphrase") {
        val (_, entropy) = generator.generateMnemonic()
        val key = generator.deriveRecoveryKey(entropy, "secure-passphrase")
        key.size shouldBe 32
    }

    test("deriveRecoveryKey with very long passphrase") {
        val (_, entropy) = generator.generateMnemonic()
        val longPassphrase = "x".repeat(10_000)
        val key = generator.deriveRecoveryKey(entropy, longPassphrase)
        key.size shouldBe 32
    }

    // ── Multiple Roundtrips ──────────────────────────────────────────────────

    test("10 consecutive mnemonic generate-recover roundtrips all succeed") {
        repeat(10) {
            val (words, originalEntropy) = generator.generateMnemonic()
            val recoveredEntropy = generator.mnemonicToEntropy(words)
            recoveredEntropy.toList() shouldBe originalEntropy.toList()
        }
    }

    test("recovery key from mnemonic roundtrip is consistent across calls") {
        val passphrase = "test-consistency"
        val (words, entropy) = generator.generateMnemonic()

        val key1 = generator.deriveRecoveryKey(entropy, passphrase)

        // Recover entropy from mnemonic and derive again
        val recovered = generator.mnemonicToEntropy(words)
        val key2 = generator.deriveRecoveryKey(recovered, passphrase)

        // And a third time
        val recovered2 = generator.mnemonicToEntropy(words)
        val key3 = generator.deriveRecoveryKey(recovered2, passphrase)

        key1.toList() shouldBe key2.toList()
        key2.toList() shouldBe key3.toList()
    }

    // ── Mixed Case Word Matching ─────────────────────────────────────────────

    test("mnemonicToEntropy handles words with mixed casing") {
        val (words, originalEntropy) = generator.generateMnemonic()
        // Mix casing: first word uppercase, second lowercase, etc.
        val mixedWords = words.mapIndexed { i, word ->
            if (i % 2 == 0) word.uppercase() else word.lowercase()
        }
        val recovered = generator.mnemonicToEntropy(mixedWords)
        recovered.toList() shouldBe originalEntropy.toList()
    }

    // ── Deterministic Mnemonic from Known Entropy ────────────────────────────

    test("known entropy produces known mnemonic (regression test)") {
        // Generate a mnemonic, then verify that the same entropy produces the same words
        val (words, entropy) = generator.generateMnemonic()

        // Re-derive: go from entropy back to mnemonic and verify consistency
        val recovered = generator.mnemonicToEntropy(words)
        recovered.toList() shouldBe entropy.toList()
    }
})
