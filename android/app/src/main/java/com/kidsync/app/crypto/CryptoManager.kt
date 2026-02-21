package com.kidsync.app.crypto

import java.security.KeyPair
import java.security.PublicKey

/**
 * Core cryptographic operations interface.
 * Implements the encryption pipeline from encryption-spec.md:
 * - Ed25519 signing and verification
 * - X25519 key pair generation and ECDH
 * - AES-256-GCM encryption/decryption with gzip compression
 * - HKDF-SHA256 key derivation
 * - SHA-256 hashing
 * - Ed25519-to-X25519 key conversion
 */
interface CryptoManager {

    companion object {
        /**
         * Build the Additional Authenticated Data string for AES-256-GCM payload encryption.
         *
         * Format: "bucketId|deviceId"
         * All components are UTF-8 encoded.
         */
        fun buildPayloadAad(
            bucketId: String,
            deviceId: String
        ): String = "$bucketId|$deviceId"
    }

    // ─── Ed25519 Signing ────────────────────────────────────────────────────────

    /**
     * Generate a new Ed25519 key pair for signing and authentication.
     *
     * @return Pair of (publicKey 32 bytes, privateKey 32 bytes seed)
     */
    fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray>

    /**
     * Sign a message using Ed25519.
     *
     * @param message The message to sign
     * @param privateKey The 32-byte Ed25519 private key seed
     * @return The 64-byte Ed25519 signature
     */
    fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray

    /**
     * Verify an Ed25519 signature.
     *
     * @param message The original message
     * @param signature The 64-byte Ed25519 signature
     * @param publicKey The 32-byte Ed25519 public key
     * @return true if the signature is valid
     */
    fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean

    /**
     * Convert an Ed25519 private key seed to an X25519 private key.
     * This allows deriving both signing and encryption keys from a single seed.
     *
     * Uses the standard crypto_sign_ed25519_sk_to_curve25519 conversion.
     *
     * @param ed25519PrivateKey The 32-byte Ed25519 private key seed
     * @return The 32-byte X25519 private key
     */
    fun ed25519PrivateToX25519(ed25519PrivateKey: ByteArray): ByteArray

    /**
     * Convert an Ed25519 public key to an X25519 public key.
     *
     * @param ed25519PublicKey The 32-byte Ed25519 public key
     * @return The 32-byte X25519 public key
     */
    fun ed25519PublicToX25519(ed25519PublicKey: ByteArray): ByteArray

    /**
     * Compute a key fingerprint: hex-encoded SHA-256 of the public key bytes.
     *
     * @param publicKey The raw public key bytes (Ed25519 or X25519)
     * @return Hex-encoded SHA-256 hash of the key
     */
    fun computeKeyFingerprint(publicKey: ByteArray): String

    // ─── X25519 Key Exchange ────────────────────────────────────────────────────

    /**
     * Generate a new X25519 key pair for DEK wrapping (ECDH key agreement).
     */
    fun generateX25519KeyPair(): KeyPair

    /**
     * Encode a public key to base64 string for transmission.
     */
    fun encodePublicKey(publicKey: PublicKey): String

    /**
     * Decode a base64-encoded public key.
     */
    fun decodePublicKey(encoded: String): PublicKey

    // ─── AES-256-GCM Encryption ─────────────────────────────────────────────────

    /**
     * Generate a new 256-bit AES DEK (Data Encryption Key).
     */
    fun generateDek(): ByteArray

    /**
     * Encrypt a payload using AES-256-GCM.
     *
     * Pipeline: plaintext UTF-8 bytes -> gzip compress -> AES-256-GCM encrypt
     * Output: Base64(nonce || ciphertext || tag)
     *
     * @param plaintext The canonical JSON string to encrypt
     * @param dek The 256-bit Data Encryption Key
     * @param aad Additional Authenticated Data: "bucketId|deviceId"
     * @return Base64-encoded string: nonce (12 bytes) || ciphertext || tag (16 bytes)
     */
    fun encryptPayload(plaintext: String, dek: ByteArray, aad: String): String

    /**
     * Decrypt a payload encrypted with AES-256-GCM.
     *
     * Pipeline: Base64 decode -> AES-256-GCM decrypt -> gzip decompress -> UTF-8 string
     *
     * @param encryptedPayload Base64-encoded string: nonce || ciphertext || tag
     * @param dek The 256-bit Data Encryption Key
     * @param aad Additional Authenticated Data: "bucketId|deviceId"
     * @return The decrypted canonical JSON string
     */
    fun decryptPayload(encryptedPayload: String, dek: ByteArray, aad: String): String

    // ─── DEK Wrapping ───────────────────────────────────────────────────────────

    /**
     * Wrap a DEK for a specific device using X25519 ECDH + HKDF + AES-256-GCM.
     *
     * Protocol (encryption-spec.md Section 8):
     * 1. Generate ephemeral X25519 key pair
     * 2. ECDH: sharedSecret = X25519(ephemeralPrivate, recipientPublic)
     * 3. HKDF-SHA256(IKM=sharedSecret, salt=random, info="kidsync-dek-wrap-v1"||deviceId) -> wrappingKey
     * 4. AES-256-GCM(wrappingKey, nonce, DEK, AAD="epoch="||keyEpoch||",device="||deviceId) -> wrappedDek
     * 5. Return: ephemeralPublicKey || salt || nonce || wrappedDek || tag
     *
     * @param dek The DEK to wrap
     * @param recipientPublicKey The recipient device's X25519 public key
     * @param deviceId The recipient device ID
     * @param keyEpoch The current key epoch
     * @return Base64-encoded wrapped DEK
     */
    fun wrapDek(dek: ByteArray, recipientPublicKey: PublicKey, deviceId: String, keyEpoch: Int): String

    /**
     * Unwrap a DEK using the device's X25519 private key.
     *
     * Reverse of wrapDek.
     *
     * @param wrappedDek Base64-encoded wrapped DEK
     * @param devicePrivateKey The device's X25519 private key
     * @param deviceId The device ID (must match the one used during wrapping)
     * @param keyEpoch The key epoch (must match)
     * @return The unwrapped 256-bit DEK
     */
    fun unwrapDek(wrappedDek: String, devicePrivateKey: java.security.PrivateKey, deviceId: String, keyEpoch: Int): ByteArray

    // ─── Hashing & Key Derivation ───────────────────────────────────────────────

    /**
     * Compute SHA-256 hash of a byte array.
     */
    fun sha256(data: ByteArray): ByteArray

    /**
     * Compute SHA-256 hash of a UTF-8 string, returning hex-encoded result.
     */
    fun sha256Hex(data: String): String

    /**
     * Derive a key using HKDF-SHA256.
     *
     * @param ikm Input Key Material
     * @param salt Salt (can be empty)
     * @param info Context info string
     * @param length Output key length in bytes
     * @return Derived key bytes
     */
    fun hkdfDerive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /**
     * Compute the safety number fingerprint for key verification.
     *
     * Formula: HexEncode(SHA256(PublicKeyA || PublicKeyB))[:16]
     * Keys are concatenated in lexicographic order of their encoded forms.
     */
    fun computeFingerprint(publicKeyA: String, publicKeyB: String): String

    // ─── Blob Encryption ────────────────────────────────────────────────────────

    /**
     * Encrypt blob data with a per-blob key.
     *
     * @param data The raw blob data
     * @return Pair of (encrypted data, encryption key)
     */
    fun encryptBlob(data: ByteArray): Pair<ByteArray, ByteArray>

    /**
     * Decrypt blob data.
     *
     * @param encryptedData The encrypted blob data
     * @param key The blob encryption key
     * @return Decrypted raw data
     */
    fun decryptBlob(encryptedData: ByteArray, key: ByteArray): ByteArray

    // ─── Invite Token ────────────────────────────────────────────────────────────

    /**
     * Generate a cryptographically random invite token string.
     *
     * @return URL-safe base64-encoded random token
     */
    fun generateInviteToken(): String

    // ─── DEK Lifecycle ───────────────────────────────────────────────────────────

    /**
     * Generate a new DEK and store it for a bucket (epoch 1).
     * Used when creating a new bucket.
     *
     * @param bucketId The bucket to generate and store the DEK for
     */
    suspend fun generateAndStoreDek(bucketId: String)

    /**
     * Unwrap a received wrapped DEK and store it locally.
     *
     * @param bucketId The bucket the DEK belongs to
     * @param wrappedDek The base64-encoded wrapped DEK
     * @param senderPublicKey The sender's public key (base64-encoded)
     * @param privateKey The device's private key for unwrapping
     */
    suspend fun unwrapAndStoreDek(
        bucketId: String,
        wrappedDek: String,
        senderPublicKey: String,
        privateKey: java.security.PrivateKey
    )

    /**
     * Compute a key fingerprint from a single base64-encoded public key.
     * This is a convenience overload of computeFingerprint for single-key use.
     *
     * @param publicKey Base64-encoded public key
     * @return Hex-encoded SHA-256 fingerprint
     */
    fun computeKeyFingerprint(publicKey: String): String
}
