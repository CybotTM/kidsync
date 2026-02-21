package com.kidsync.app.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject

/**
 * BIP39 mnemonic generation and recovery key derivation using 256-bit entropy (24 words).
 *
 * Algorithm:
 * 1. Generate 32 bytes of SecureRandom entropy
 * 2. SHA-256 hash the entropy, take first 8 bits as checksum
 * 3. Concatenate entropy (256 bits) + checksum (8 bits) = 264 bits
 * 4. Split into 24 groups of 11 bits, each indexing the BIP39 word list
 *
 * Recovery key derivation uses HKDF-SHA256 per encryption-spec.md:
 *   HKDF-SHA256(IKM=entropy || passphrase, salt="kidsync-recovery-v2", info="recovery-key", L=32)
 */
class RecoveryKeyGeneratorImpl @Inject constructor() : RecoveryKeyGenerator {

    companion object {
        private const val ENTROPY_BYTES = 32 // 256 bits
        private const val CHECKSUM_BITS = 8 // ENT / 32 = 256 / 32 = 8
        private const val WORD_COUNT = 24 // (ENT + CS) / 11 = 264 / 11 = 24
        private const val BITS_PER_WORD = 11
        private const val HKDF_SALT = "kidsync-recovery-v2"
        private const val HKDF_INFO = "recovery-key"
        private const val RECOVERY_KEY_LENGTH = 32 // 256 bits
    }

    override fun generateMnemonic(): Pair<List<String>, ByteArray> {
        // Step 1: Generate 32 bytes of cryptographically secure random entropy
        val entropy = ByteArray(ENTROPY_BYTES)
        SecureRandom().nextBytes(entropy)

        // Step 2: Compute SHA-256 checksum
        val hash = sha256(entropy)
        val checksumByte = hash[0] // First 8 bits of the hash

        // Step 3: Convert entropy + checksum to a bit string (264 bits total)
        val bits = ByteArray(ENTROPY_BYTES * 8 + CHECKSUM_BITS)

        // Entropy bits (256)
        for (i in entropy.indices) {
            for (bit in 7 downTo 0) {
                bits[i * 8 + (7 - bit)] = ((entropy[i].toInt() ushr bit) and 1).toByte()
            }
        }

        // Checksum bits (8)
        for (bit in 7 downTo 0) {
            bits[ENTROPY_BYTES * 8 + (7 - bit)] =
                ((checksumByte.toInt() ushr bit) and 1).toByte()
        }

        // Step 4: Split into 24 groups of 11 bits, each indexing the word list
        val words = mutableListOf<String>()
        for (i in 0 until WORD_COUNT) {
            var index = 0
            for (bit in 0 until BITS_PER_WORD) {
                index = (index shl 1) or bits[i * BITS_PER_WORD + bit].toInt()
            }
            words.add(Bip39WordList.WORDS[index])
        }

        return Pair(words, entropy)
    }

    override fun mnemonicToEntropy(mnemonic: List<String>): ByteArray {
        require(mnemonic.size == WORD_COUNT) {
            "Invalid mnemonic: expected $WORD_COUNT words, got ${mnemonic.size}"
        }

        // Build a lookup map for O(1) word-to-index resolution
        val wordIndex = HashMap<String, Int>(Bip39WordList.WORDS.size * 2)
        Bip39WordList.WORDS.forEachIndexed { index, word -> wordIndex[word] = index }

        // Step 1: Look up each word's 11-bit index
        val indices = IntArray(WORD_COUNT)
        for (i in mnemonic.indices) {
            val word = mnemonic[i].lowercase()
            indices[i] = wordIndex[word]
                ?: throw IllegalArgumentException("Unknown BIP39 word: '${mnemonic[i]}' at position $i")
        }

        // Step 2: Reconstruct 264 bits from the 24 x 11-bit indices
        val bits = ByteArray(WORD_COUNT * BITS_PER_WORD) // 264 bits
        for (i in indices.indices) {
            for (bit in BITS_PER_WORD - 1 downTo 0) {
                bits[i * BITS_PER_WORD + (BITS_PER_WORD - 1 - bit)] =
                    ((indices[i] ushr bit) and 1).toByte()
            }
        }

        // Step 3: Split into 256-bit entropy + 8-bit checksum
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in entropy.indices) {
            var byte = 0
            for (bit in 0 until 8) {
                byte = (byte shl 1) or bits[i * 8 + bit].toInt()
            }
            entropy[i] = byte.toByte()
        }

        var checksum = 0
        for (bit in 0 until CHECKSUM_BITS) {
            checksum = (checksum shl 1) or bits[ENTROPY_BYTES * 8 + bit].toInt()
        }

        // Step 4: Verify checksum - SHA-256 hash the entropy and compare first 8 bits
        val hash = sha256(entropy)
        val expectedChecksum = (hash[0].toInt() and 0xFF) // unsigned first byte
        val actualChecksum = checksum and 0xFF

        require(expectedChecksum == actualChecksum) {
            "Invalid mnemonic checksum: expected 0x${expectedChecksum.toString(16).padStart(2, '0')}, " +
                "got 0x${actualChecksum.toString(16).padStart(2, '0')}"
        }

        return entropy
    }

    override fun deriveRecoveryKey(entropy: ByteArray, passphrase: String): ByteArray {
        // IKM = entropy || UTF-8(passphrase)
        val passphraseBytes = passphrase.toByteArray(Charsets.UTF_8)
        val ikm = ByteArray(entropy.size + passphraseBytes.size)
        System.arraycopy(entropy, 0, ikm, 0, entropy.size)
        System.arraycopy(passphraseBytes, 0, ikm, entropy.size, passphraseBytes.size)

        // salt = "kidsync-recovery-v2"
        val salt = HKDF_SALT.toByteArray(Charsets.UTF_8)

        // info = "recovery-key"
        val info = HKDF_INFO.toByteArray(Charsets.UTF_8)

        // HKDF-SHA256 expand to 32 bytes
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, info))

        val recoveryKey = ByteArray(RECOVERY_KEY_LENGTH)
        hkdf.generateBytes(recoveryKey, 0, RECOVERY_KEY_LENGTH)

        return recoveryKey
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }
}
