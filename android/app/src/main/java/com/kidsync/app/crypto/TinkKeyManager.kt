package com.kidsync.app.crypto

import android.content.SharedPreferences
import com.kidsync.app.data.local.dao.KeyEpochDao
import com.kidsync.app.data.local.entity.KeyEpochEntity
import com.kidsync.app.data.remote.api.ApiService
import java.security.KeyPair
import java.util.Base64
import java.util.UUID
import javax.inject.Inject

/**
 * KeyManager implementation using Android Keystore-backed EncryptedSharedPreferences
 * for secure key storage.
 */
class TinkKeyManager @Inject constructor(
    private val encryptedPrefs: SharedPreferences,
    private val keyEpochDao: KeyEpochDao,
    private val cryptoManager: CryptoManager,
    private val apiService: ApiService
) : KeyManager {

    companion object {
        private const val PREF_DEVICE_ID = "device_id"
        private const val PREF_PRIVATE_KEY_PREFIX = "private_key_"
        private const val PREF_PUBLIC_KEY_PREFIX = "public_key_"
        private const val PREF_DEK_PREFIX = "dek_"
        private const val PREF_CURRENT_EPOCH_PREFIX = "current_epoch_"
    }

    override suspend fun storeDeviceKeyPair(deviceId: UUID, keyPair: KeyPair) {
        encryptedPrefs.edit()
            .putString(
                PREF_PRIVATE_KEY_PREFIX + deviceId,
                Base64.getEncoder().encodeToString(keyPair.private.encoded)
            )
            .putString(
                PREF_PUBLIC_KEY_PREFIX + deviceId,
                Base64.getEncoder().encodeToString(keyPair.public.encoded)
            )
            .apply()
    }

    override suspend fun getDeviceKeyPair(deviceId: UUID): KeyPair? {
        val privateKeyBase64 = encryptedPrefs.getString(PREF_PRIVATE_KEY_PREFIX + deviceId, null)
            ?: return null
        val publicKeyBase64 = encryptedPrefs.getString(PREF_PUBLIC_KEY_PREFIX + deviceId, null)
            ?: return null

        return try {
            val privateKeyBytes = Base64.getDecoder().decode(privateKeyBase64)
            val publicKeyBytes = Base64.getDecoder().decode(publicKeyBase64)

            val keyFactory = java.security.KeyFactory.getInstance("X25519")
            val privateKey = keyFactory.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes))
            val publicKey = keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyBytes))

            KeyPair(publicKey, privateKey)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getOrCreateDeviceId(): UUID {
        val existing = encryptedPrefs.getString(PREF_DEVICE_ID, null)
        if (existing != null) return UUID.fromString(existing)

        val newId = UUID.randomUUID()
        encryptedPrefs.edit().putString(PREF_DEVICE_ID, newId.toString()).apply()
        return newId
    }

    override suspend fun getDek(familyId: UUID, epoch: Int): ByteArray? {
        val key = "${PREF_DEK_PREFIX}${familyId}_$epoch"
        val encoded = encryptedPrefs.getString(key, null) ?: return null
        return Base64.getDecoder().decode(encoded)
    }

    override suspend fun storeDek(familyId: UUID, epoch: Int, dek: ByteArray) {
        val key = "${PREF_DEK_PREFIX}${familyId}_$epoch"
        encryptedPrefs.edit()
            .putString(key, Base64.getEncoder().encodeToString(dek))
            .apply()

        // Update current epoch if this is newer
        val currentEpoch = getCurrentEpoch(familyId)
        if (epoch > currentEpoch) {
            encryptedPrefs.edit()
                .putInt(PREF_CURRENT_EPOCH_PREFIX + familyId, epoch)
                .apply()
        }

        // Store epoch record in database
        keyEpochDao.insertEpoch(
            KeyEpochEntity(
                epoch = epoch,
                familyId = familyId,
                wrappedDek = "", // Not stored here - managed separately
                createdAt = java.time.Instant.now().toString()
            )
        )
    }

    override suspend fun getCurrentEpoch(familyId: UUID): Int {
        return encryptedPrefs.getInt(PREF_CURRENT_EPOCH_PREFIX + familyId.toString(), 1)
    }

    override suspend fun fetchAndStoreWrappedDeks(familyId: UUID, deviceId: UUID) {
        try {
            val response = apiService.getWrappedDeks(familyId.toString())
            if (response.isSuccessful) {
                val body = response.body() ?: return
                val keyPair = getDeviceKeyPair(deviceId) ?: return

                for (wrappedDekResponse in body.wrappedDeks) {
                    val dek = cryptoManager.unwrapDek(
                        wrappedDek = wrappedDekResponse.wrappedDek,
                        devicePrivateKey = keyPair.private,
                        deviceId = deviceId.toString(),
                        keyEpoch = wrappedDekResponse.epoch
                    )
                    storeDek(familyId, wrappedDekResponse.epoch, dek)
                }
            }
        } catch (e: Exception) {
            // Log error but don't fail - DEKs might already be cached
        }
    }

    override suspend fun wrapDekWithRecoveryKey(familyId: UUID, recoveryKey: ByteArray) {
        val currentEpoch = getCurrentEpoch(familyId)
        val dek = getDek(familyId, currentEpoch)
            ?: throw IllegalStateException("No DEK for current epoch")

        // Use AES-256-GCM to wrap with recovery key
        val nonce = ByteArray(12)
        java.security.SecureRandom().nextBytes(nonce)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = javax.crypto.spec.SecretKeySpec(recoveryKey, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD("recovery-wrap".toByteArray())
        val wrapped = cipher.doFinal(dek)

        val result = ByteArray(nonce.size + wrapped.size)
        System.arraycopy(nonce, 0, result, 0, nonce.size)
        System.arraycopy(wrapped, 0, result, nonce.size, wrapped.size)

        val encoded = Base64.getEncoder().encodeToString(result)

        // Upload to server
        apiService.uploadRecoveryDek(
            familyId.toString(),
            mapOf(
                "epoch" to currentEpoch,
                "wrappedDek" to encoded
            )
        )
    }

    override suspend fun unwrapDekWithRecoveryKey(familyId: UUID, recoveryKey: ByteArray) {
        // Download from server
        val response = apiService.getRecoveryDek(familyId.toString())
        if (!response.isSuccessful) throw IllegalStateException("Failed to fetch recovery DEK")

        val body = response.body() ?: throw IllegalStateException("Empty recovery response")
        val data = Base64.getDecoder().decode(body.wrappedDek)

        val nonce = data.sliceArray(0 until 12)
        val wrapped = data.sliceArray(12 until data.size)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = javax.crypto.spec.SecretKeySpec(recoveryKey, "AES")
        val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, nonce)
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        cipher.updateAAD("recovery-wrap".toByteArray())
        val dek = cipher.doFinal(wrapped)

        storeDek(familyId, body.epoch, dek)
    }

    override suspend fun rotateKey(familyId: UUID, newEpoch: Int, excludeDeviceId: UUID?) {
        // Generate new DEK
        val newDek = cryptoManager.generateDek()

        // Store locally
        storeDek(familyId, newEpoch, newDek)

        // Wrap for all active devices (fetched from server)
        val devicesResponse = apiService.getDevices(familyId.toString())
        if (devicesResponse.isSuccessful) {
            val devices = devicesResponse.body()?.devices ?: return

            for (device in devices) {
                if (device.status != "ACTIVE") continue
                if (excludeDeviceId != null && device.deviceId == excludeDeviceId.toString()) continue

                val publicKey = cryptoManager.decodePublicKey(device.publicKey)
                val wrapped = cryptoManager.wrapDek(
                    dek = newDek,
                    recipientPublicKey = publicKey,
                    deviceId = device.deviceId,
                    keyEpoch = newEpoch
                )

                apiService.uploadWrappedDek(
                    familyId.toString(),
                    mapOf(
                        "deviceId" to device.deviceId,
                        "epoch" to newEpoch,
                        "wrappedDek" to wrapped
                    )
                )
            }
        }
    }

    override suspend fun getAvailableEpochs(familyId: UUID): List<Int> {
        return keyEpochDao.getEpochsForFamily(familyId).map { it.epoch }
    }
}
