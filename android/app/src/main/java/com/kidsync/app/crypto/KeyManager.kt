package com.kidsync.app.crypto

import java.security.KeyPair
import java.util.UUID

/**
 * Manages cryptographic keys: device key pairs, DEK storage, epoch management.
 * Keys are stored securely using Android Keystore and EncryptedSharedPreferences.
 */
interface KeyManager {
    /**
     * Store a device key pair securely.
     */
    suspend fun storeDeviceKeyPair(deviceId: UUID, keyPair: KeyPair)

    /**
     * Retrieve the device key pair.
     */
    suspend fun getDeviceKeyPair(deviceId: UUID): KeyPair?

    /**
     * Get or create a stable device ID.
     */
    suspend fun getOrCreateDeviceId(): UUID

    /**
     * Get the DEK for a specific epoch.
     */
    suspend fun getDek(familyId: UUID, epoch: Int): ByteArray?

    /**
     * Store a DEK for a specific epoch.
     */
    suspend fun storeDek(familyId: UUID, epoch: Int, dek: ByteArray)

    /**
     * Get the current (latest) key epoch for a family.
     */
    suspend fun getCurrentEpoch(familyId: UUID): Int

    /**
     * Fetch wrapped DEKs from the server and unwrap them locally.
     */
    suspend fun fetchAndStoreWrappedDeks(familyId: UUID, deviceId: UUID)

    /**
     * Wrap current DEK with a recovery key for backup.
     */
    suspend fun wrapDekWithRecoveryKey(familyId: UUID, recoveryKey: ByteArray)

    /**
     * Unwrap DEK using a recovery key (device recovery flow).
     */
    suspend fun unwrapDekWithRecoveryKey(familyId: UUID, recoveryKey: ByteArray)

    /**
     * Handle key rotation: generate new DEK, wrap for all active devices.
     */
    suspend fun rotateKey(familyId: UUID, newEpoch: Int, excludeDeviceId: UUID?)

    /**
     * Get all epochs available locally.
     */
    suspend fun getAvailableEpochs(familyId: UUID): List<Int>
}
