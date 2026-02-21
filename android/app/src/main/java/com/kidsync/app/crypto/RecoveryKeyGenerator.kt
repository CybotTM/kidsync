package com.kidsync.app.crypto

/**
 * BIP39 mnemonic generation and recovery key derivation.
 *
 * From encryption-spec.md:
 * - Generate 256 bits of entropy
 * - Encode as BIP39 24-word mnemonic
 * - Derive recovery key via HKDF-SHA256(IKM=entropy || passphrase, salt="kidsync-recovery-v2", info="recovery-key", L=32)
 *
 * The optional passphrase acts as a "25th word" per BIP39 specification.
 * Without a passphrase, the mnemonic alone is sufficient for recovery.
 * With a passphrase, both the mnemonic and passphrase are required.
 */
interface RecoveryKeyGenerator {
    /**
     * Generate a new BIP39 24-word mnemonic from 256 bits of secure random entropy.
     *
     * @return Pair of (word list, raw entropy bytes)
     */
    fun generateMnemonic(): Pair<List<String>, ByteArray>

    /**
     * Convert a mnemonic word list back to entropy bytes.
     * Validates checksum.
     *
     * @param mnemonic The 24-word mnemonic
     * @return The original 256-bit entropy
     * @throws IllegalArgumentException if mnemonic is invalid
     */
    fun mnemonicToEntropy(mnemonic: List<String>): ByteArray

    /**
     * Derive a 32-byte recovery key from entropy using HKDF-SHA256.
     *
     * HKDF-SHA256(IKM=entropy || passphrase_bytes, salt="kidsync-recovery-v2", info="recovery-key", L=32)
     *
     * The passphrase provides additional security: a stolen mnemonic alone cannot
     * derive the recovery key without the passphrase.
     *
     * @param entropy The 256-bit entropy from the mnemonic
     * @param passphrase Optional passphrase (BIP39 "25th word"), defaults to empty
     * @return 32-byte derived recovery key
     */
    fun deriveRecoveryKey(entropy: ByteArray, passphrase: String = ""): ByteArray
}
