package com.kidsync.app.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.math.ec.rfc7748.X25519
import org.bouncycastle.math.ec.rfc8032.Ed25519
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject

/**
 * CryptoManager implementation using standard Java crypto primitives and BouncyCastle.
 *
 * Implements the full encryption pipeline from encryption-spec.md:
 * - Ed25519 signing/verification (BouncyCastle)
 * - Ed25519-to-X25519 key conversion (BouncyCastle)
 * - X25519 ECDH key agreement
 * - AES-256-GCM authenticated encryption
 * - HKDF-SHA256 key derivation
 * - Gzip compression
 */
class TinkCryptoManager @Inject constructor() : CryptoManager {

    companion object {
        private const val AES_GCM_NONCE_SIZE = 12
        private const val AES_GCM_TAG_SIZE = 128 // bits
        private const val DEK_SIZE = 32 // bytes = 256 bits
        private const val HKDF_DEK_WRAP_INFO = "kidsync-dek-wrap-v1"
        private const val ALGORITHM_AES_GCM = "AES/GCM/NoPadding"
        private const val ED25519_SEED_SIZE = 32
        private const val ED25519_PUBLIC_KEY_SIZE = 32
        private const val ED25519_SIGNATURE_SIZE = 64
    }

    // ─── Ed25519 Signing ────────────────────────────────────────────────────────

    override fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val seed = ByteArray(ED25519_SEED_SIZE)
        SecureRandom().nextBytes(seed)
        val privateKeyParams = Ed25519PrivateKeyParameters(seed, 0)
        val publicKeyParams = privateKeyParams.generatePublicKey()
        return Pair(publicKeyParams.encoded, seed)
    }

    override fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray {
        val privateKeyParams = Ed25519PrivateKeyParameters(privateKey, 0)
        val signer = Ed25519Signer()
        signer.init(true, privateKeyParams)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    override fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val publicKeyParams = Ed25519PublicKeyParameters(publicKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, publicKeyParams)
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    override fun ed25519PrivateToX25519(ed25519PrivateKey: ByteArray): ByteArray {
        // Derive the full 64-byte expanded Ed25519 secret key from the 32-byte seed
        // by hashing with SHA-512, then use the first 32 bytes (clamped) as X25519 private key.
        val hash = MessageDigest.getInstance("SHA-512").digest(ed25519PrivateKey)
        // Clamp the scalar (standard X25519 clamping)
        hash[0] = (hash[0].toInt() and 248).toByte()
        hash[31] = (hash[31].toInt() and 127).toByte()
        hash[31] = (hash[31].toInt() or 64).toByte()
        return hash.copyOfRange(0, 32)
    }

    override fun ed25519PublicToX25519(ed25519PublicKey: ByteArray): ByteArray {
        // Convert Ed25519 point (in compressed Edwards form) to X25519 (Montgomery u-coordinate).
        // Uses BouncyCastle's internal conversion via the RFC 7748 X25519 field arithmetic.
        val x25519Public = ByteArray(X25519.POINT_SIZE)
        // BouncyCastle Ed25519 provides this conversion
        val edPoint = Ed25519PublicKeyParameters(ed25519PublicKey, 0)
        // Use the internal BouncyCastle conversion: extract Edwards Y coordinate, compute Montgomery u = (1+y)/(1-y)
        convertEdwardsToMontgomery(edPoint.encoded, x25519Public)
        return x25519Public
    }

    override fun computeKeyFingerprint(publicKey: ByteArray): String {
        val hashBytes = sha256(publicKey)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    // ─── X25519 Key Exchange ────────────────────────────────────────────────────

    override fun generateX25519KeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("X25519")
        return keyPairGenerator.generateKeyPair()
    }

    override fun encodePublicKey(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    override fun decodePublicKey(encoded: String): PublicKey {
        val bytes = Base64.getDecoder().decode(encoded)
        val keySpec = X509EncodedKeySpec(bytes)
        val keyFactory = KeyFactory.getInstance("X25519")
        return keyFactory.generatePublic(keySpec)
    }

    // ─── AES-256-GCM Encryption ─────────────────────────────────────────────────

    override fun generateDek(): ByteArray {
        val dek = ByteArray(DEK_SIZE)
        SecureRandom().nextBytes(dek)
        return dek
    }

    override fun encryptPayload(plaintext: String, dek: ByteArray, aad: String): String {
        // 1. Gzip compress
        val compressed = gzipCompress(plaintext.toByteArray(Charsets.UTF_8))

        // 2. Generate random nonce
        val nonce = ByteArray(AES_GCM_NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        // 3. AES-256-GCM encrypt
        val cipher = Cipher.getInstance(ALGORITHM_AES_GCM)
        val keySpec = SecretKeySpec(dek, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_SIZE, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val ciphertextAndTag = cipher.doFinal(compressed)

        // 4. Concatenate: nonce || ciphertext || tag
        // Note: Java's GCM implementation appends the tag to the ciphertext
        val result = ByteArray(nonce.size + ciphertextAndTag.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(ciphertextAndTag, 0, result, nonce.size, ciphertextAndTag.size)

        // 5. Base64 encode
        return Base64.getEncoder().encodeToString(result)
    }

    override fun decryptPayload(encryptedPayload: String, dek: ByteArray, aad: String): String {
        // 1. Base64 decode
        val data = Base64.getDecoder().decode(encryptedPayload)

        // 2. Extract nonce and ciphertext+tag
        val nonce = data.sliceArray(0 until AES_GCM_NONCE_SIZE)
        val ciphertextAndTag = data.sliceArray(AES_GCM_NONCE_SIZE until data.size)

        // 3. AES-256-GCM decrypt
        val cipher = Cipher.getInstance(ALGORITHM_AES_GCM)
        val keySpec = SecretKeySpec(dek, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_SIZE, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val compressed = cipher.doFinal(ciphertextAndTag)

        // 4. Gzip decompress
        val plaintext = gzipDecompress(compressed)

        return String(plaintext, Charsets.UTF_8)
    }

    // ─── DEK Wrapping ───────────────────────────────────────────────────────────

    override fun wrapDek(
        dek: ByteArray,
        recipientPublicKey: PublicKey,
        deviceId: String,
        keyEpoch: Int
    ): String {
        // 1. Generate ephemeral X25519 key pair
        val ephemeralKeyPair = generateX25519KeyPair()

        // 2. ECDH: sharedSecret = X25519(ephemeralPrivate, recipientPublic)
        val keyAgreement = KeyAgreement.getInstance("X25519")
        keyAgreement.init(ephemeralKeyPair.private)
        keyAgreement.doPhase(recipientPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // 3. Generate random salt
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)

        // 4. HKDF: derive wrapping key
        val info = (HKDF_DEK_WRAP_INFO + deviceId).toByteArray(Charsets.UTF_8)
        val wrappingKey = hkdfDerive(sharedSecret, salt, info, DEK_SIZE)

        // 5. AES-256-GCM encrypt the DEK
        val nonce = ByteArray(AES_GCM_NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        val aad = "epoch=$keyEpoch,device=$deviceId".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(ALGORITHM_AES_GCM)
        val keySpec = SecretKeySpec(wrappingKey, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_SIZE, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad)
        val wrappedDekAndTag = cipher.doFinal(dek)

        // 6. Construct result: ephemeralPublicKey || salt || nonce || wrappedDek || tag
        val ephemeralPublicKeyBytes = ephemeralKeyPair.public.encoded
        val result = ByteArrayOutputStream()
        result.write(ephemeralPublicKeyBytes)
        result.write(salt)
        result.write(nonce)
        result.write(wrappedDekAndTag)

        return Base64.getEncoder().encodeToString(result.toByteArray())
    }

    override fun unwrapDek(
        wrappedDek: String,
        devicePrivateKey: PrivateKey,
        deviceId: String,
        keyEpoch: Int
    ): ByteArray {
        val data = Base64.getDecoder().decode(wrappedDek)

        // Parse components (X25519 public key is 44 bytes in X509 encoding)
        val ephemeralPublicKeyBytes = data.sliceArray(0 until 44) // X509-encoded X25519 public key
        var offset = 44

        val salt = data.sliceArray(offset until offset + 32)
        offset += 32

        val nonce = data.sliceArray(offset until offset + AES_GCM_NONCE_SIZE)
        offset += AES_GCM_NONCE_SIZE

        val wrappedDekAndTag = data.sliceArray(offset until data.size)

        // 1. Reconstruct ephemeral public key
        val keySpec = X509EncodedKeySpec(ephemeralPublicKeyBytes)
        val keyFactory = KeyFactory.getInstance("X25519")
        val ephemeralPublicKey = keyFactory.generatePublic(keySpec)

        // 2. ECDH
        val keyAgreement = KeyAgreement.getInstance("X25519")
        keyAgreement.init(devicePrivateKey)
        keyAgreement.doPhase(ephemeralPublicKey, true)
        val sharedSecret = keyAgreement.generateSecret()

        // 3. HKDF: derive wrapping key
        val info = (HKDF_DEK_WRAP_INFO + deviceId).toByteArray(Charsets.UTF_8)
        val wrappingKey = hkdfDerive(sharedSecret, salt, info, DEK_SIZE)

        // 4. AES-256-GCM decrypt
        val aad = "epoch=$keyEpoch,device=$deviceId".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(ALGORITHM_AES_GCM)
        val aesKeySpec = SecretKeySpec(wrappingKey, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_SIZE, nonce)
        cipher.init(Cipher.DECRYPT_MODE, aesKeySpec, gcmSpec)
        cipher.updateAAD(aad)

        return cipher.doFinal(wrappedDekAndTag)
    }

    // ─── Hashing & Key Derivation ───────────────────────────────────────────────

    override fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    override fun sha256Hex(data: String): String {
        val hashBytes = sha256(data.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    override fun hkdfDerive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // HKDF-SHA256 implementation (RFC 5869)
        // Step 1: Extract
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(actualSalt, ikm)

        // Step 2: Expand
        val n = (length + 31) / 32 // ceil(length / hashLen)
        val okm = ByteArray(length)
        var t = ByteArray(0)

        for (i in 1..n) {
            val input = ByteArray(t.size + info.size + 1)
            System.arraycopy(t, 0, input, 0, t.size)
            System.arraycopy(info, 0, input, t.size, info.size)
            input[input.size - 1] = i.toByte()

            t = hmacSha256(prk, input)

            val copyLen = minOf(32, length - (i - 1) * 32)
            System.arraycopy(t, 0, okm, (i - 1) * 32, copyLen)
        }

        return okm
    }

    override fun computeFingerprint(publicKeyA: String, publicKeyB: String): String {
        // Sort keys lexicographically
        val (first, second) = if (publicKeyA <= publicKeyB) {
            publicKeyA to publicKeyB
        } else {
            publicKeyB to publicKeyA
        }

        val combined = Base64.getDecoder().decode(first) + Base64.getDecoder().decode(second)
        val hash = sha256(combined)
        return hash.joinToString("") { "%02x".format(it) }.substring(0, 16)
    }

    // ─── Blob Encryption ────────────────────────────────────────────────────────

    override fun encryptBlob(data: ByteArray): Pair<ByteArray, ByteArray> {
        // Generate per-blob key
        val blobKey = generateDek()

        // Encrypt with AES-256-GCM (no compression for blobs)
        val nonce = ByteArray(AES_GCM_NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance(ALGORITHM_AES_GCM)
        val keySpec = SecretKeySpec(blobKey, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_SIZE, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        val encrypted = cipher.doFinal(data)

        // nonce || ciphertext || tag
        val result = ByteArray(nonce.size + encrypted.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(encrypted, 0, result, nonce.size, encrypted.size)

        return Pair(result, blobKey)
    }

    override fun decryptBlob(encryptedData: ByteArray, key: ByteArray): ByteArray {
        val nonce = encryptedData.sliceArray(0 until AES_GCM_NONCE_SIZE)
        val ciphertextAndTag = encryptedData.sliceArray(AES_GCM_NONCE_SIZE until encryptedData.size)

        val cipher = Cipher.getInstance(ALGORITHM_AES_GCM)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(AES_GCM_TAG_SIZE, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertextAndTag)
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(key, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun gzipDecompress(data: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(data)
        return GZIPInputStream(bis).use { it.readBytes() }
    }

    /**
     * Convert an Ed25519 public key (Edwards form) to an X25519 public key (Montgomery form).
     *
     * The conversion uses the birational map between the Edwards curve and Montgomery curve:
     *   u = (1 + y) / (1 - y)  mod p
     *
     * where y is the y-coordinate of the Edwards point, and p = 2^255 - 19.
     */
    private fun convertEdwardsToMontgomery(edwardsPublicKey: ByteArray, montgomeryPublicKey: ByteArray) {
        // The Ed25519 public key encodes the y-coordinate (with the sign of x in the high bit).
        // We need to extract y and compute u = (1+y)/(1-y) mod p.
        val p = java.math.BigInteger.ONE.shiftLeft(255).subtract(java.math.BigInteger.valueOf(19))

        // Decode y from the Ed25519 point encoding (little-endian, with x-sign in bit 255)
        val yBytes = edwardsPublicKey.copyOf()
        yBytes[31] = (yBytes[31].toInt() and 0x7F).toByte() // Clear the sign bit

        // Convert from little-endian to BigInteger
        val reversed = yBytes.reversedArray()
        val y = java.math.BigInteger(1, reversed)

        // u = (1 + y) * (1 - y)^(-1) mod p
        val one = java.math.BigInteger.ONE
        val numerator = one.add(y).mod(p)
        val denominator = one.subtract(y).mod(p)
        val denominatorInverse = denominator.modInverse(p)
        val u = numerator.multiply(denominatorInverse).mod(p)

        // Encode u as 32 bytes little-endian
        val uBytes = u.toByteArray()
        // BigInteger may have a leading zero byte; we need exactly 32 bytes LE
        val uBytesReversed = ByteArray(32)
        for (i in uBytes.indices) {
            val targetIdx = uBytes.size - 1 - i
            if (targetIdx >= 0 && i < 32) {
                uBytesReversed[i] = uBytes[targetIdx]
            }
        }
        System.arraycopy(uBytesReversed, 0, montgomeryPublicKey, 0, 32)
    }
}
