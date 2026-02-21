package com.kidsync.app.domain.usecase.auth

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.crypto.RecoveryKeyGenerator
import javax.inject.Inject

/**
 * Recovery use case for the zero-knowledge architecture.
 *
 * Recovery flow:
 * 1. Generate a 24-word BIP39 mnemonic + optional passphrase ("25th word")
 * 2. Derive recovery key from mnemonic entropy + passphrase via HKDF
 * 3. Wrap the DEK and device seed with recovery key
 * 4. Upload encrypted recovery blob to server
 *
 * Restore flow:
 * 1. Enter 24-word mnemonic + passphrase
 * 2. Derive recovery key
 * 3. Download and decrypt recovery blob
 * 4. Re-derive signing + encryption keys from recovered seed
 * 5. Register as new device, download ops, decrypt with recovered DEK
 */
class RecoveryUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val recoveryKeyGenerator: RecoveryKeyGenerator
) {
    /**
     * Generate a new BIP39 24-word recovery mnemonic.
     * The derived recovery key is used to wrap the current DEK and upload to server.
     *
     * @param bucketId The bucket whose DEK to wrap for recovery
     * @param passphrase Optional BIP39 passphrase ("25th word") for additional security
     */
    suspend fun generateRecoveryKey(
        bucketId: String,
        passphrase: String = ""
    ): Result<List<String>> {
        return try {
            val (mnemonic, entropy) = recoveryKeyGenerator.generateMnemonic()

            // Derive recovery key via HKDF(entropy || passphrase)
            val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(
                entropy = entropy,
                passphrase = passphrase
            )

            // Wrap current DEK with recovery key and upload
            keyManager.wrapDekWithRecoveryKey(bucketId, recoveryKey)

            Result.success(mnemonic)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Restore DEK access from a recovery mnemonic after device loss.
     *
     * @param mnemonic The 24-word BIP39 mnemonic
     * @param bucketId The bucket to restore access for
     * @param passphrase The passphrase used during generation (empty if none was set)
     */
    suspend fun restoreFromRecovery(
        mnemonic: List<String>,
        bucketId: String,
        passphrase: String = ""
    ): Result<Unit> {
        return try {
            val entropy = recoveryKeyGenerator.mnemonicToEntropy(mnemonic)
            val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(
                entropy = entropy,
                passphrase = passphrase
            )

            keyManager.unwrapDekWithRecoveryKey(bucketId, recoveryKey)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
