package com.kidsync.app.crypto

import android.content.SharedPreferences
import com.kidsync.app.data.local.dao.KeyEpochDao
import com.kidsync.app.data.local.entity.KeyEpochEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.UploadWrappedKeyRequest
import com.kidsync.app.domain.model.KeyAttestation
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
// SEC2-A-02: Uses zeroOut() extension from TinkCryptoManager.kt (same package, internal visibility)

/**
 * KeyManager implementation using Android Keystore-backed EncryptedSharedPreferences
 * for secure key storage.
 *
 * In the zero-knowledge architecture:
 * - The Ed25519 seed is the root of device identity
 * - Both Ed25519 (signing) and X25519 (encryption) keys are derived from the same seed
 * - DEKs are indexed by bucketId (not familyId)
 */
class TinkKeyManager(
    private val encryptedPrefs: SharedPreferences,
    private val keyEpochDao: KeyEpochDao,
    private val cryptoManager: CryptoManager,
    private val apiService: ApiService
) : KeyManager {

    companion object {
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_SIGNING_SEED = "signing_seed"
        private const val PREF_SIGNING_PUBLIC_KEY = "signing_public_key"
        private const val PREF_DEK_PREFIX = "dek_"
        private const val PREF_CURRENT_EPOCH_PREFIX = "current_epoch_"

        /**
         * SEC5-A-06: Build the AAD string for recovery blob encryption/decryption.
         * Format: "recovery:v1:{bucketId}" to bind the blob to a specific bucket.
         */
        internal fun buildRecoveryAad(bucketId: String): String = "recovery:v1:$bucketId"

        /**
         * Legacy AAD format for backward compatibility with older recovery blobs.
         */
        internal const val LEGACY_RECOVERY_AAD = "recovery"
    }

    // SEC2-A-14: Lock to prevent concurrent key generation race conditions
    private val keyGenerationLock = Any()

    // ─── Device Identity ────────────────────────────────────────────────────────

    // SEC3-A-05: The seed is stored as a Base64-encoded JVM String in EncryptedSharedPreferences.
    // JVM Strings are immutable and cannot be explicitly zeroed from memory. This is a systemic
    // JVM limitation: the String may persist in the heap until garbage collected. The underlying
    // ByteArray returned here CAN be zeroed by callers, but the intermediate String representation
    // in SharedPreferences and Base64 encoding remains outside our control.
    override suspend fun getSigningKeyPair(): Pair<ByteArray, ByteArray>? {
        val seedBase64 = encryptedPrefs.getString(PREF_SIGNING_SEED, null) ?: return null
        val publicKeyBase64 = encryptedPrefs.getString(PREF_SIGNING_PUBLIC_KEY, null) ?: return null

        val seed = Base64.getDecoder().decode(seedBase64)
        val publicKey = Base64.getDecoder().decode(publicKeyBase64)
        return Pair(publicKey, seed)
    }

    override suspend fun getOrCreateSigningKeyPair(): Pair<ByteArray, ByteArray> {
        val existing = getSigningKeyPair()
        if (existing != null) return existing

        // SEC2-A-14: Synchronized to prevent concurrent key generation.
        // Without this lock, two coroutines could both see null from getSigningKeyPair()
        // and each generate a different key pair, with only the last write persisting.
        synchronized(keyGenerationLock) {
            // Double-check inside the lock
            val existingInLock = encryptedPrefs.getString(PREF_SIGNING_SEED, null)
            if (existingInLock != null) {
                val publicKeyBase64 = encryptedPrefs.getString(PREF_SIGNING_PUBLIC_KEY, null)!!
                return Pair(
                    Base64.getDecoder().decode(publicKeyBase64),
                    Base64.getDecoder().decode(existingInLock)
                )
            }

            val (publicKey, seed) = cryptoManager.generateEd25519KeyPair()
            encryptedPrefs.edit()
                .putString(PREF_SIGNING_SEED, Base64.getEncoder().encodeToString(seed))
                .putString(PREF_SIGNING_PUBLIC_KEY, Base64.getEncoder().encodeToString(publicKey))
                .commit()

            return Pair(publicKey, seed)
        }
    }

    override suspend fun getSigningKeyFingerprint(): String {
        val (publicKey, seed) = getOrCreateSigningKeyPair()
        try {
            return cryptoManager.computeKeyFingerprint(publicKey)
        } finally {
            // SEC6-A-12: Zero the seed copy; we only needed the public key for fingerprinting
            seed.zeroOut()
        }
    }

    override suspend fun getEncryptionKeyFingerprint(): String {
        // SEC6-A-12: getEncryptionKeyPair() internally calls getOrCreateSigningKeyPair()
        // which returns the seed. The seed flows through ed25519PrivateToX25519 and is
        // zeroed in getEncryptionKeyPair's finally block. We also zero the seed from
        // our own call path here.
        val (_, seed) = getOrCreateSigningKeyPair()
        try {
            val encKeyPair = getEncryptionKeyPair()
            val rawPublicKey = encKeyPair.public.encoded.let { encoded ->
                // Extract raw 32-byte X25519 public key from X.509 SubjectPublicKeyInfo (44 bytes)
                if (encoded.size == 44) encoded.copyOfRange(12, 44) else encoded
            }
            return cryptoManager.computeKeyFingerprint(rawPublicKey)
        } finally {
            seed.zeroOut()
        }
    }

    override suspend fun getEncryptionKeyPair(): KeyPair {
        val (_, seed) = getOrCreateSigningKeyPair()

        // Derive X25519 private key from Ed25519 seed
        val x25519PrivateBytes = cryptoManager.ed25519PrivateToX25519(seed)

        try {
            // Derive X25519 public key from Ed25519 public key
            val (ed25519Public, seed2) = getOrCreateSigningKeyPair()
            try {
                val x25519PublicBytes = cryptoManager.ed25519PublicToX25519(ed25519Public)

                // Construct a Java KeyPair from the raw bytes using X25519 key specs
                // Build X509-encoded public key: for X25519, the X509 encoding wraps the 32-byte key
                val x25519PublicX509 = buildX25519PublicKeyEncoding(x25519PublicBytes)
                val x25519PrivatePKCS8 = buildX25519PrivateKeyEncoding(x25519PrivateBytes)

                val keyFactory = KeyFactory.getInstance("X25519")
                val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(x25519PublicX509))
                val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(x25519PrivatePKCS8))

                return KeyPair(publicKey, privateKey)
            } finally {
                // SEC6-A-12: Zero the second seed copy from getOrCreateSigningKeyPair
                seed2.zeroOut()
            }
        } finally {
            // SEC2-A-02: Zero X25519 private key material after constructing KeyPair
            x25519PrivateBytes.zeroOut()
            // SEC6-A-12: Zero the seed copy from getOrCreateSigningKeyPair
            seed.zeroOut()
        }
    }

    override suspend fun getDeviceId(): String? {
        return encryptedPrefs.getString(PREF_DEVICE_ID, null)
    }

    // SEC6-A-06: Use commit() instead of apply() to ensure device ID is persisted
    // synchronously before the caller proceeds with authentication.
    override suspend fun storeDeviceId(deviceId: String) {
        encryptedPrefs.edit()
            .putString(PREF_DEVICE_ID, deviceId)
            .commit()
    }

    // ─── Key Attestation ────────────────────────────────────────────────────────

    override suspend fun createKeyAttestation(
        attestedDeviceId: String,
        attestedEncryptionKey: ByteArray
    ): KeyAttestation {
        val (signerPublicKey, signerSeed) = getOrCreateSigningKeyPair()
        val signerDeviceId = getDeviceId()
            ?: throw IllegalStateException("Device not registered; cannot create attestation")

        // SEC2-A-19: Use ":" delimiter between deviceId and key to prevent ambiguity.
        // Without a delimiter, attestedDeviceId="abc" + key=[0x64,...] is indistinguishable
        // from attestedDeviceId="abcd" + key=[...] at the byte level.
        // Sign: attestedDeviceId + ":" + attestedEncryptionKey
        val message = attestedDeviceId.toByteArray(Charsets.UTF_8) +
            ":".toByteArray(Charsets.UTF_8) +
            attestedEncryptionKey
        val signature = cryptoManager.signEd25519(message, signerSeed)

        return KeyAttestation(
            signerDeviceId = signerDeviceId,
            attestedDeviceId = attestedDeviceId,
            attestedEncryptionKey = Base64.getEncoder().encodeToString(attestedEncryptionKey),
            signature = Base64.getEncoder().encodeToString(signature),
            createdAt = Instant.now().toString()
        )
    }

    override suspend fun verifyKeyAttestation(
        attestation: KeyAttestation,
        signerPublicKey: ByteArray
    ): Boolean {
        val attestedEncryptionKey = Base64.getDecoder().decode(attestation.attestedEncryptionKey)
        val signature = Base64.getDecoder().decode(attestation.signature)

        // SEC2-A-19: Reconstruct signed message with delimiter: attestedDeviceId + ":" + attestedEncryptionKey
        val message = attestation.attestedDeviceId.toByteArray(Charsets.UTF_8) +
            ":".toByteArray(Charsets.UTF_8) +
            attestedEncryptionKey

        return cryptoManager.verifyEd25519(message, signature, signerPublicKey)
    }

    // ─── DEK Management ─────────────────────────────────────────────────────────

    override suspend fun getDek(bucketId: String, epoch: Int): ByteArray? {
        val key = "${PREF_DEK_PREFIX}${bucketId}_$epoch"
        val encoded = encryptedPrefs.getString(key, null) ?: return null
        return Base64.getDecoder().decode(encoded)
    }

    override suspend fun storeDek(bucketId: String, epoch: Int, dek: ByteArray) {
        val key = "${PREF_DEK_PREFIX}${bucketId}_$epoch"

        // SEC2-A-06: Use a single editor with commit() for atomic DEK + epoch storage.
        // Two separate apply() calls risk partial writes if the process is killed between them.
        val editor = encryptedPrefs.edit()
            .putString(key, Base64.getEncoder().encodeToString(dek))

        // Update current epoch if this is newer
        val currentEpoch = getCurrentEpoch(bucketId)
        if (epoch > currentEpoch) {
            editor.putInt(PREF_CURRENT_EPOCH_PREFIX + bucketId, epoch)
        }

        // commit() is synchronous and returns success/failure, ensuring atomicity
        editor.commit()

        // Store epoch record in database
        keyEpochDao.insertEpoch(
            KeyEpochEntity(
                epoch = epoch,
                bucketId = bucketId,
                wrappedDek = "", // Not stored here -- managed separately
                createdAt = Instant.now().toString()
            )
        )
    }

    override suspend fun getCurrentEpoch(bucketId: String): Int {
        return encryptedPrefs.getInt(PREF_CURRENT_EPOCH_PREFIX + bucketId, 1)
    }

    override suspend fun fetchAndStoreWrappedDeks(bucketId: String) {
        var dek: ByteArray? = null
        try {
            val wrappedKey = apiService.getWrappedDek()
            val encryptionKeyPair = getEncryptionKeyPair()
            val deviceId = getDeviceId() ?: return

            dek = cryptoManager.unwrapDek(
                wrappedDek = wrappedKey.wrappedDek,
                devicePrivateKey = encryptionKeyPair.private,
                deviceId = deviceId,
                keyEpoch = wrappedKey.keyEpoch
            )
            storeDek(bucketId, wrappedKey.keyEpoch, dek)
        } catch (e: Exception) {
            // SEC-A-09: Only log stack traces in debug to avoid leaking key context
            android.util.Log.w("TinkKeyManager", "Failed to fetch wrapped DEKs, using cached")
            if (com.kidsync.app.BuildConfig.DEBUG) {
                android.util.Log.d("TinkKeyManager", "DEK fetch error details", e)
            }
        } finally {
            // SEC3-A-13: Zero DEK after storing
            dek?.zeroOut()
        }
    }

    /**
     * Create a recovery blob containing ALL epoch DEKs and the device seed.
     *
     * Blob format (JSON, then AES-256-GCM encrypted with recovery key):
     * ```json
     * {
     *   "seed": "<base64(32-byte device seed)>",
     *   "deks": {
     *     "1": "<base64(dek1)>",
     *     "2": "<base64(dek2)>",
     *     ...
     *   }
     * }
     * ```
     *
     * The encrypted output is: nonce (12 bytes) || ciphertext+tag
     * SEC5-A-06: AAD = "recovery:v1:{bucketId}" to bind the blob to a specific bucket
     * and prevent cross-bucket recovery blob substitution attacks.
     */
    override suspend fun wrapDekWithRecoveryKey(bucketId: String, recoveryKey: ByteArray) {
        val encoder = Base64.getEncoder()

        // Collect all epoch DEKs
        val epochs = getAvailableEpochs(bucketId)
        if (epochs.isEmpty()) {
            throw IllegalStateException("No DEKs available for bucket")
        }

        val deksMap = mutableMapOf<String, String>()
        val dekBytesList = mutableListOf<ByteArray>()
        for (epoch in epochs) {
            val dek = getDek(bucketId, epoch) ?: continue
            dekBytesList.add(dek)
            deksMap[epoch.toString()] = encoder.encodeToString(dek)
            // SEC3-A-15: Zero each DEK ByteArray after encoding to Base64.
            // Note: the Base64-encoded JVM String in deksMap cannot be zeroed due to JVM
            // String immutability. This is a systemic JVM limitation.
            dek.zeroOut()
        }

        if (deksMap.isEmpty()) {
            throw IllegalStateException("No DEKs could be read for any epoch")
        }

        // Include device seed
        val seed = getSeed()

        // SEC-A-12: Build JSON blob using JSONObject instead of string concatenation
        val blobObj = org.json.JSONObject().apply {
            put("seed", encoder.encodeToString(seed))
            put("deks", org.json.JSONObject(deksMap as Map<*, *>))
        }
        val blobJson = blobObj.toString()

        // Encrypt with AES-256-GCM using recovery key
        val nonce = ByteArray(12)
        java.security.SecureRandom().nextBytes(nonce)

        try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = javax.crypto.spec.SecretKeySpec(recoveryKey, "AES")
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            // SEC5-A-06: Include format prefix and bucketId in AAD to prevent
            // cross-bucket and cross-payload-type substitution attacks
            cipher.updateAAD(buildRecoveryAad(bucketId).toByteArray())
            val encrypted = cipher.doFinal(blobJson.toByteArray(Charsets.UTF_8))

            val result = ByteArray(nonce.size + encrypted.size)
            System.arraycopy(nonce, 0, result, 0, nonce.size)
            System.arraycopy(encrypted, 0, result, nonce.size, encrypted.size)

            val encoded = encoder.encodeToString(result)

            // Upload encrypted recovery blob to server
            apiService.uploadRecoveryBlob(
                com.kidsync.app.data.remote.dto.RecoveryBlobRequest(encryptedBlob = encoded)
            )
        } finally {
            // SEC-A-02: Zero sensitive key material
            recoveryKey.zeroOut()
            nonce.zeroOut()
        }
    }

    /**
     * Restore all epoch DEKs and device seed from a recovery blob.
     *
     * Decrypts the blob, parses the JSON, restores the seed, then iterates
     * over all DEK epochs and stores each one. Sets current epoch to the highest.
     */
    override suspend fun unwrapDekWithRecoveryKey(bucketId: String, recoveryKey: ByteArray) {
        val decoder = Base64.getDecoder()

        // Download from server
        val response = apiService.getRecoveryBlob()
        val data = decoder.decode(response.encryptedBlob)

        val nonce = data.sliceArray(0 until 12)
        val encrypted = data.sliceArray(12 until data.size)

        var decrypted: ByteArray? = null
        var seed: ByteArray? = null
        val dekBytesList = mutableListOf<ByteArray>()
        try {
            // SEC5-A-06: Try new AAD format first, fall back to legacy for backward compatibility
            decrypted = try {
                decryptRecoveryBlob(recoveryKey, nonce, encrypted, buildRecoveryAad(bucketId))
            } catch (_: javax.crypto.AEADBadTagException) {
                // Backward compatibility: try legacy AAD format "recovery"
                decryptRecoveryBlob(recoveryKey, nonce, encrypted, LEGACY_RECOVERY_AAD)
            }

            // Parse JSON blob
            val blobJson = org.json.JSONObject(String(decrypted, Charsets.UTF_8))

            // Restore device seed
            val seedBase64 = blobJson.getString("seed")
            seed = decoder.decode(seedBase64)
            storeSeed(seed)

            // Restore all epoch DEKs
            val deksObj = blobJson.getJSONObject("deks")
            var maxEpoch = 0
            for (epochStr in deksObj.keys()) {
                val epoch = epochStr.toInt()
                val dekBytes = decoder.decode(deksObj.getString(epochStr))
                dekBytesList.add(dekBytes)
                storeDek(bucketId, epoch, dekBytes)
                if (epoch > maxEpoch) maxEpoch = epoch
            }
        } finally {
            // SEC3-A-14: Zero all sensitive byte arrays after use
            recoveryKey.zeroOut()
            decrypted?.zeroOut()
            seed?.zeroOut()
            dekBytesList.forEach { it.zeroOut() }
        }
    }

    override suspend fun rotateKey(bucketId: String, newEpoch: Int, excludeDeviceId: String?) {
        // SEC2-A-04: Save previous epoch so we can rollback on partial failure
        val previousEpoch = getCurrentEpoch(bucketId)

        // Generate new DEK
        val newDek = cryptoManager.generateDek()

        try {
            // Store locally
            storeDek(bucketId, newEpoch, newDek)

            // Wrap for all active devices in this bucket
            val response = apiService.getBucketDevices(bucketId)

            for (device in response.devices) {
                if (excludeDeviceId != null && device.deviceId == excludeDeviceId) continue

                val publicKey = cryptoManager.decodePublicKey(device.encryptionKey)
                val wrapped = cryptoManager.wrapDek(
                    dek = newDek,
                    recipientPublicKey = publicKey,
                    deviceId = device.deviceId,
                    keyEpoch = newEpoch
                )

                apiService.uploadWrappedKey(
                    UploadWrappedKeyRequest(
                        targetDevice = device.deviceId,
                        wrappedDek = wrapped,
                        keyEpoch = newEpoch
                    )
                )
            }
        } catch (e: Exception) {
            // SEC2-A-04: Rollback epoch to previous value on partial failure.
            // The DEK entry in encrypted prefs remains (orphaned), but the epoch
            // pointer is reverted so the system continues using the previous DEK.
            encryptedPrefs.edit()
                .putInt(PREF_CURRENT_EPOCH_PREFIX + bucketId, previousEpoch)
                .commit()
            throw e
        } finally {
            // SEC2-A-11: Zero DEK after use
            newDek.zeroOut()
        }
    }

    override suspend fun getAvailableEpochs(bucketId: String): List<Int> {
        return keyEpochDao.getEpochsForBucket(bucketId).map { it.epoch }
    }

    // ─── Seed Management ─────────────────────────────────────────────────────────

    override fun generateSeed(): ByteArray {
        val seed = ByteArray(32)
        java.security.SecureRandom().nextBytes(seed)
        return seed
    }

    override suspend fun storeSeed(seed: ByteArray) {
        // Derive the public key from the seed
        val (publicKey, _) = deriveSigningKeyPair(seed)

        // SEC3-A-08: Use a single SharedPreferences.Editor with commit() for atomic
        // seed + public key writes. Two separate apply() calls risk partial writes
        // if the process is killed between them, leaving inconsistent state.
        encryptedPrefs.edit()
            .putString(PREF_SIGNING_SEED, Base64.getEncoder().encodeToString(seed))
            .putString(PREF_SIGNING_PUBLIC_KEY, Base64.getEncoder().encodeToString(publicKey))
            .commit()
    }

    override suspend fun getSeed(): ByteArray {
        val seedBase64 = encryptedPrefs.getString(PREF_SIGNING_SEED, null)
            ?: throw IllegalStateException("No seed stored")
        return Base64.getDecoder().decode(seedBase64)
    }

    override fun deriveSigningKeyPair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val privateParams = org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(seed, 0)
        val publicKey = privateParams.generatePublicKey().encoded
        return Pair(publicKey, seed)
    }

    // SEC4-A-06: The returned x25519Private ByteArray contains sensitive key material.
    // Callers MUST zero it in a finally block after use: `x25519Private.zeroOut()`.
    // We cannot zero it here because the caller needs the value.
    override fun deriveEncryptionKeyPair(seed: ByteArray): Pair<ByteArray, ByteArray> {
        val x25519Private = cryptoManager.ed25519PrivateToX25519(seed)
        // Derive public from Ed25519 public -> X25519 public
        val ed25519Public = deriveSigningKeyPair(seed).first
        val x25519Public = cryptoManager.ed25519PublicToX25519(ed25519Public)
        return Pair(x25519Public, x25519Private)
    }

    override fun encodePublicKey(publicKey: ByteArray): String {
        return Base64.getEncoder().encodeToString(publicKey)
    }

    override suspend fun hasExistingKeys(): Boolean {
        return encryptedPrefs.getString(PREF_SIGNING_SEED, null) != null
    }

    // ─── Private Helpers ────────────────────────────────────────────────────────

    /**
     * Build X.509 SubjectPublicKeyInfo encoding for an X25519 public key.
     * ASN.1: SEQUENCE { SEQUENCE { OID 1.3.101.110 }, BIT STRING { publicKeyBytes } }
     */
    private fun buildX25519PublicKeyEncoding(rawPublicKey: ByteArray): ByteArray {
        // X25519 OID: 1.3.101.110 = 06 03 2b 65 6e
        // AlgorithmIdentifier: 30 05 06 03 2b 65 6e
        // BIT STRING wrapper: 03 21 00 + 32 bytes
        // SubjectPublicKeyInfo: 30 2a + AlgorithmIdentifier + BIT STRING
        val algorithmIdentifier = byteArrayOf(
            0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x6e
        )
        val bitString = byteArrayOf(0x03, 0x21, 0x00) + rawPublicKey
        val totalLength = algorithmIdentifier.size + bitString.size
        return byteArrayOf(0x30, totalLength.toByte()) + algorithmIdentifier + bitString
    }

    /**
     * SEC5-A-06: Decrypt recovery blob with the given AAD string.
     * Used to support both new and legacy AAD formats.
     */
    private fun decryptRecoveryBlob(
        recoveryKey: ByteArray,
        nonce: ByteArray,
        encrypted: ByteArray,
        aad: String
    ): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = javax.crypto.spec.SecretKeySpec(recoveryKey, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD(aad.toByteArray())
        return cipher.doFinal(encrypted)
    }

    /**
     * Build PKCS#8 PrivateKeyInfo encoding for an X25519 private key.
     * ASN.1: SEQUENCE { INTEGER 0, SEQUENCE { OID 1.3.101.110 }, OCTET STRING { OCTET STRING { privateKeyBytes } } }
     */
    private fun buildX25519PrivateKeyEncoding(rawPrivateKey: ByteArray): ByteArray {
        // Version: 02 01 00
        val version = byteArrayOf(0x02, 0x01, 0x00)
        // AlgorithmIdentifier: 30 05 06 03 2b 65 6e
        val algorithmIdentifier = byteArrayOf(
            0x30, 0x05,
            0x06, 0x03, 0x2b, 0x65, 0x6e
        )
        // Private key: OCTET STRING { OCTET STRING { 32 bytes } }
        val innerOctetString = byteArrayOf(0x04, 0x20) + rawPrivateKey
        val outerOctetString = byteArrayOf(0x04, (innerOctetString.size).toByte()) + innerOctetString
        val totalLength = version.size + algorithmIdentifier.size + outerOctetString.size
        return byteArrayOf(0x30, totalLength.toByte()) + version + algorithmIdentifier + outerOctetString
    }
}
