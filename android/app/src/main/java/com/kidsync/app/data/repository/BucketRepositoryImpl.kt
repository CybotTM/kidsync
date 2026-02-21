package com.kidsync.app.data.repository

import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.data.local.dao.KeyAttestationDao
import com.kidsync.app.data.local.entity.BucketEntity
import com.kidsync.app.data.local.entity.DeviceEntity
import com.kidsync.app.data.local.entity.KeyAttestationEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.InviteRequest
import com.kidsync.app.data.remote.dto.JoinBucketRequest
import com.kidsync.app.data.remote.dto.UploadAttestationRequest
import com.kidsync.app.domain.model.Bucket
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.model.KeyAttestation
import com.kidsync.app.domain.repository.BucketRepository
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
    private val cryptoManager: CryptoManager
) : BucketRepository {

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
                    signingKey = dto.signingKey,
                    encryptionKey = dto.encryptionKey,
                    createdAt = Instant.parse(dto.createdAt)
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
                    attestedKey = attestation.attestedEncryptionKey,
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
                    signerDeviceId = dto.signerDevice,
                    attestedDeviceId = dto.attestedDevice,
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
