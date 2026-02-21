package com.kidsync.app.data.repository

import android.content.SharedPreferences
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.data.local.dao.KeyAttestationDao
import com.kidsync.app.data.local.entity.BucketEntity
import com.kidsync.app.data.local.entity.KeyAttestationEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.InviteRequest
import com.kidsync.app.data.remote.dto.JoinBucketRequest
import com.kidsync.app.data.remote.dto.UploadAttestationRequest
import com.kidsync.app.data.remote.dto.WrappedKeyResponse
import com.kidsync.app.domain.model.Bucket
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.model.KeyAttestation
import com.kidsync.app.domain.repository.BucketRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject

/**
 * Repository for bucket operations in the zero-knowledge architecture.
 *
 * A bucket is an anonymous, opaque storage namespace. The server does not
 * know what a bucket represents (family, organization, etc.).
 */
class BucketRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val bucketDao: BucketDao,
    private val keyAttestationDao: KeyAttestationDao,
    private val cryptoManager: CryptoManager,
    private val encryptedPrefs: SharedPreferences
) : BucketRepository {

    companion object {
        private const val PREF_BUCKET_NAME_PREFIX = "bucket_name_"
        private const val PREF_ACCESSIBLE_BUCKETS = "accessible_bucket_ids"
        private const val WRAPPED_DEK_POLL_INTERVAL_MS = 2000L
        private const val WRAPPED_DEK_POLL_TIMEOUT_MS = 120_000L
    }

    override fun observeBuckets(): Flow<List<Bucket>> {
        return bucketDao.observeAllBuckets().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getBucket(bucketId: String): Bucket? {
        return bucketDao.getBucket(bucketId)?.toDomain()
    }

    override suspend fun createBucket(): Result<Bucket> {
        return try {
            val response = apiService.createBucket()
            val bucket = Bucket(
                bucketId = response.bucketId,
                createdByDeviceId = "", // Will be set from session
                createdAt = Instant.now()
            )
            bucketDao.insertBucket(bucket.toEntity())
            // Track in accessible buckets
            val existing = getAccessibleBuckets().toMutableSet()
            existing.add(bucket.bucketId)
            encryptedPrefs.edit()
                .putStringSet(PREF_ACCESSIBLE_BUCKETS, existing)
                .apply()
            Result.success(bucket)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteBucket(bucketId: String): Result<Unit> {
        return try {
            apiService.deleteBucket(bucketId)
            bucketDao.deleteBucket(bucketId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun createInvite(bucketId: String, inviteToken: String): Result<Unit> {
        return try {
            // Hash the token locally; server only stores the hash
            val tokenHash = cryptoManager.sha256Hex(inviteToken)
            val response = apiService.createInvite(
                bucketId,
                InviteRequest(tokenHash = tokenHash)
            )
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(ApiException(response.code(), response.message()))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun joinBucket(bucketId: String, inviteToken: String): Result<Unit> {
        return try {
            apiService.joinBucket(bucketId, JoinBucketRequest(inviteToken = inviteToken))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun leaveBucket(bucketId: String): Result<Unit> {
        return try {
            apiService.leaveBucket(bucketId)
            bucketDao.deleteBucket(bucketId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getBucketDevices(bucketId: String): Result<List<Device>> {
        return try {
            val devices = apiService.getBucketDevices(bucketId)
            val domainDevices = devices.map { dto ->
                Device(
                    deviceId = dto.deviceId,
                    signingKey = "", // Server does not return signing key in device list
                    encryptionKey = dto.encryptionKey,
                    createdAt = Instant.parse(dto.grantedAt)
                )
            }
            Result.success(domainDevices)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun observeBucketDevices(bucketId: String): Flow<List<Device>> = flow {
        val result = getBucketDevices(bucketId)
        emit(result.getOrDefault(emptyList()))
    }

    override suspend fun uploadKeyAttestation(attestation: KeyAttestation): Result<Unit> {
        return try {
            apiService.uploadAttestation(
                UploadAttestationRequest(
                    attestedDeviceId = attestation.attestedDeviceId,
                    attestedEncryptionKey = attestation.attestedEncryptionKey,
                    signature = attestation.signature
                )
            )

            // Cache locally
            keyAttestationDao.insertAttestation(
                KeyAttestationEntity(
                    signerDeviceId = attestation.signerDeviceId,
                    attestedDeviceId = attestation.attestedDeviceId,
                    attestedEncryptionKey = attestation.attestedEncryptionKey,
                    signature = attestation.signature,
                    createdAt = attestation.createdAt
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getKeyAttestations(deviceId: String): Result<List<KeyAttestation>> {
        return try {
            val remote = apiService.getAttestations(deviceId)
            val attestations = remote.map { dto ->
                KeyAttestation(
                    signerDeviceId = dto.signerDeviceId,
                    attestedDeviceId = dto.attestedDeviceId,
                    attestedEncryptionKey = dto.attestedKey,
                    signature = dto.signature,
                    createdAt = dto.createdAt
                )
            }

            // Cache locally
            keyAttestationDao.insertAttestations(
                attestations.map { attest ->
                    KeyAttestationEntity(
                        signerDeviceId = attest.signerDeviceId,
                        attestedDeviceId = attest.attestedDeviceId,
                        attestedEncryptionKey = attest.attestedEncryptionKey,
                        signature = attest.signature,
                        createdAt = attest.createdAt
                    )
                }
            )

            Result.success(attestations)
        } catch (e: Exception) {
            // Fallback to local cache
            try {
                val local = keyAttestationDao.getAttestationsForDevice(deviceId)
                val attestations = local.map { entity ->
                    KeyAttestation(
                        signerDeviceId = entity.signerDeviceId,
                        attestedDeviceId = entity.attestedDeviceId,
                        attestedEncryptionKey = entity.attestedEncryptionKey,
                        signature = entity.signature,
                        createdAt = entity.createdAt
                    )
                }
                Result.success(attestations)
            } catch (localError: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun saveBucket(bucket: Bucket) {
        bucketDao.insertBucket(bucket.toEntity())
    }

    override suspend fun saveDevice(device: Device) {
        // Store in local cache
        // The devices table now stores signing and encryption keys
    }

    override suspend fun storeLocalBucketName(bucketId: String, name: String) {
        encryptedPrefs.edit()
            .putString(PREF_BUCKET_NAME_PREFIX + bucketId, name)
            .apply()
        // Also track this bucket ID in the accessible list
        val existing = getAccessibleBuckets().toMutableSet()
        existing.add(bucketId)
        encryptedPrefs.edit()
            .putStringSet(PREF_ACCESSIBLE_BUCKETS, existing)
            .apply()
    }

    override suspend fun getLocalBucketName(bucketId: String): String? {
        return encryptedPrefs.getString(PREF_BUCKET_NAME_PREFIX + bucketId, null)
    }

    override suspend fun getAccessibleBuckets(): List<String> {
        return encryptedPrefs.getStringSet(PREF_ACCESSIBLE_BUCKETS, emptySet())
            ?.toList()
            ?: emptyList()
    }

    override suspend fun waitForWrappedDek(bucketId: String): WrappedKeyResponse {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < WRAPPED_DEK_POLL_TIMEOUT_MS) {
            try {
                return apiService.getWrappedDek()
            } catch (_: Exception) {
                // Not available yet, keep polling
            }
            delay(WRAPPED_DEK_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Timed out waiting for wrapped DEK")
    }

    // ── Mapping helpers ─────────────────────────────────────────────────────────

    private fun BucketEntity.toDomain(): Bucket {
        return Bucket(
            bucketId = bucketId,
            createdByDeviceId = createdByDeviceId,
            createdAt = Instant.parse(createdAt)
        )
    }

    private fun Bucket.toEntity(): BucketEntity {
        return BucketEntity(
            bucketId = bucketId,
            createdByDeviceId = createdByDeviceId,
            createdAt = createdAt.toString()
        )
    }
}
