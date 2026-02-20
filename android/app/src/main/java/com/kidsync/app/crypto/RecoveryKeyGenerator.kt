package com.kidsync.app.crypto

import java.util.UUID

/**
 * BIP39 mnemonic generation and recovery key derivation.
 *
 * From encryption-spec.md:
 * - Generate 256 bits of entropy
 * - Encode as BIP39 24-word mnemonic
 * - Derive recovery key via HKDF-SHA256(IKM=entropy, salt="kidsync-recovery-v1", info=userId, L=32)
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
     * HKDF-SHA256(IKM=entropy, salt="kidsync-recovery-v1", info=userId, L=32)
     *
     * @param entropy The 256-bit entropy from the mnemonic
     * @param userId The user's UUID
     * @return 32-byte derived recovery key
     */
    fun deriveRecoveryKey(entropy: ByteArray, userId: UUID): ByteArray
}
