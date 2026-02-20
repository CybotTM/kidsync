package com.kidsync.app.crypto

import java.security.KeyPair
import java.security.PublicKey

/**
 * Core cryptographic operations interface.
 * Implements the encryption pipeline from encryption-spec.md:
 * - X25519 key pair generation
 * - AES-256-GCM encryption/decryption with gzip compression
 * - HKDF-SHA256 key derivation
 * - SHA-256 hashing
 */
interface CryptoManager {

    companion object {
        /**
         * Build the Additional Authenticated Data string for AES-256-GCM payload encryption.
         *
         * Format: "familyId|deviceId|deviceSequence|keyEpoch"
         * All components are UTF-8 encoded.
         */
        fun buildPayloadAad(
            familyId: String,
            deviceId: String,
            deviceSequence: Long,
            keyEpoch: Int
        ): String = "$familyId|$deviceId|$deviceSequence|$keyEpoch"
    }

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
     * @param aad Additional Authenticated Data: "familyId|deviceId|deviceSequence|keyEpoch"
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
     * @param aad Additional Authenticated Data: "familyId|deviceId|deviceSequence|keyEpoch"
     * @return The decrypted canonical JSON string
     */
    fun decryptPayload(encryptedPayload: String, dek: ByteArray, aad: String): String

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
}
