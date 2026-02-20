package com.kidsync.app.domain.usecase.auth

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.crypto.RecoveryKeyGenerator
import java.util.UUID
import javax.inject.Inject

class RecoveryUseCase @Inject constructor(
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager,
    private val recoveryKeyGenerator: RecoveryKeyGenerator
) {
    /**
     * Generate a new BIP39 24-word recovery mnemonic.
     * The derived recovery key is used to wrap the current DEK and upload to server.
     */
    suspend fun generateRecoveryKey(userId: UUID, familyId: UUID): Result<List<String>> {
        return try {
            val (mnemonic, entropy) = recoveryKeyGenerator.generateMnemonic()

            // Derive recovery key via HKDF
            val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(
                entropy = entropy,
                userId = userId
            )

            // Wrap current DEK with recovery key and upload
            keyManager.wrapDekWithRecoveryKey(familyId, recoveryKey)

            Result.success(mnemonic)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Restore DEK access from a recovery mnemonic after device loss.
     */
    suspend fun restoreFromRecovery(
        mnemonic: List<String>,
        userId: UUID,
        familyId: UUID
    ): Result<Unit> {
        return try {
            val entropy = recoveryKeyGenerator.mnemonicToEntropy(mnemonic)
            val recoveryKey = recoveryKeyGenerator.deriveRecoveryKey(
                entropy = entropy,
                userId = userId
            )

            keyManager.unwrapDekWithRecoveryKey(familyId, recoveryKey)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
