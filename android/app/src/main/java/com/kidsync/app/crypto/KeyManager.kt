package com.kidsync.app.crypto

import com.kidsync.app.domain.model.KeyAttestation
import java.security.KeyPair

/**
 * Manages cryptographic keys: device signing/encryption key pairs, DEK storage, epoch management.
 * Keys are stored securely using Android Keystore and EncryptedSharedPreferences.
 *
 * In the zero-knowledge architecture:
 * - Each device has an Ed25519 signing key pair (identity + authentication)
 * - An X25519 encryption key pair is derived from the same seed
 * - DEKs are stored per bucket (not per family) per epoch
 */
interface KeyManager {

    // ─── Device Identity ────────────────────────────────────────────────────────

    /**
     * Get the device's Ed25519 signing key pair.
     * Returns null if no key pair has been created yet.
     *
     * @return Pair of (publicKey 32 bytes, privateKey 32 bytes seed), or null
     */
    suspend fun getSigningKeyPair(): Pair<ByteArray, ByteArray>?

    /**
     * Get the existing signing key pair, or create and store a new one.
     *
     * @return Pair of (publicKey 32 bytes, privateKey 32 bytes seed)
     */
    suspend fun getOrCreateSigningKeyPair(): Pair<ByteArray, ByteArray>

    /**
     * Get the SHA-256 fingerprint of this device's signing public key.
     */
    suspend fun getSigningKeyFingerprint(): String

    /**
     * Get the SHA-256 fingerprint of this device's encryption public key.
     */
    suspend fun getEncryptionKeyFingerprint(): String

    /**
     * Get the device's X25519 encryption key pair, derived from the Ed25519 seed.
     *
     * @return Java KeyPair for X25519 ECDH operations
     */
    suspend fun getEncryptionKeyPair(): KeyPair

    /**
     * Get or create a stable device ID.
     * The device ID is assigned by the server during registration and stored locally.
     */
    suspend fun getDeviceId(): String?

    /**
     * Store the server-assigned device ID.
     */
    suspend fun storeDeviceId(deviceId: String)

    // ─── Key Attestation (Cross-Signing) ────────────────────────────────────────

    /**
     * Create a key attestation: sign another device's encryption key with this device's signing key.
     *
     * The signature covers: attestedDeviceId || attestedEncryptionKey
     *
     * @param attestedDeviceId The device ID being attested
     * @param attestedEncryptionKey The X25519 public key bytes being attested
     * @return A KeyAttestation object ready to upload
     */
    suspend fun createKeyAttestation(
        attestedDeviceId: String,
        attestedEncryptionKey: ByteArray
    ): KeyAttestation

    /**
     * Verify a key attestation from another device.
     *
     * @param attestation The attestation to verify
     * @param signerPublicKey The Ed25519 public key of the signer
     * @return true if the attestation signature is valid
     */
    suspend fun verifyKeyAttestation(
        attestation: KeyAttestation,
        signerPublicKey: ByteArray
    ): Boolean

    // ─── DEK Management ─────────────────────────────────────────────────────────

    /**
     * Get the DEK for a specific bucket and epoch.
     */
    suspend fun getDek(bucketId: String, epoch: Int): ByteArray?

    /**
     * Store a DEK for a specific bucket and epoch.
     */
    suspend fun storeDek(bucketId: String, epoch: Int, dek: ByteArray)

    /**
     * Get the current (latest) key epoch for a bucket.
     */
    suspend fun getCurrentEpoch(bucketId: String): Int

    /**
     * Fetch wrapped DEKs from the server and unwrap them locally.
     */
    suspend fun fetchAndStoreWrappedDeks(bucketId: String)

    /**
     * Wrap current DEK with a recovery key for backup.
     */
    suspend fun wrapDekWithRecoveryKey(bucketId: String, recoveryKey: ByteArray)

    /**
     * Unwrap DEK using a recovery key (device recovery flow).
     */
    suspend fun unwrapDekWithRecoveryKey(bucketId: String, recoveryKey: ByteArray)

    /**
     * Handle key rotation: generate new DEK, wrap for all active devices in the bucket.
     *
     * @param bucketId The bucket to rotate the key for
     * @param newEpoch The new epoch number
     * @param excludeDeviceId Optional device ID to exclude (e.g., revoked device)
     */
    suspend fun rotateKey(bucketId: String, newEpoch: Int, excludeDeviceId: String?)

    /**
     * Get all epochs available locally for a bucket.
     */
    suspend fun getAvailableEpochs(bucketId: String): List<Int>

    // ─── Seed Management ─────────────────────────────────────────────────────────

    /**
     * Generate a new random 32-byte seed for key derivation.
     */
    fun generateSeed(): ByteArray

    /**
     * Store the seed securely.
     */
    suspend fun storeSeed(seed: ByteArray)

    /**
     * Get the stored seed.
     * @throws IllegalStateException if no seed is stored
     */
    suspend fun getSeed(): ByteArray

    /**
     * Derive an Ed25519 signing key pair from a seed.
     *
     * @param seed The 32-byte seed
     * @return Pair of (publicKey, privateKey/seed)
     */
    fun deriveSigningKeyPair(seed: ByteArray): Pair<ByteArray, ByteArray>

    /**
     * Derive an X25519 encryption key pair from a seed.
     *
     * @param seed The 32-byte seed
     * @return Pair of (publicKey, privateKey)
     */
    // TODO(SEC5-A-09): Wrap the returned Pair in a Closeable/AutoCloseable wrapper that zeros
    // the private key ByteArray on close(). Currently callers must manually zero x25519Private
    // in a finally block, which is easy to forget. A Closeable wrapper would enable use() blocks:
    //   deriveEncryptionKeyPair(seed).use { (pub, priv) -> ... }
    // and automatically zero the private key when the scope exits.
    fun deriveEncryptionKeyPair(seed: ByteArray): Pair<ByteArray, ByteArray>

    /**
     * Encode a public key to Base64 string for transmission.
     */
    fun encodePublicKey(publicKey: ByteArray): String

    /**
     * Check if this device already has stored keys.
     */
    suspend fun hasExistingKeys(): Boolean
}
