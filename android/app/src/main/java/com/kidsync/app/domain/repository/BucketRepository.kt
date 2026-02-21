package com.kidsync.app.domain.repository

import com.kidsync.app.data.remote.dto.WrappedKeyResponse
import com.kidsync.app.domain.model.Bucket
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.model.KeyAttestation
import kotlinx.coroutines.flow.Flow

/**
 * Repository for bucket operations in the zero-knowledge architecture.
 *
 * A bucket is an anonymous, opaque storage namespace. The server does not
 * know what a bucket represents (family, organization, etc.).
 */
interface BucketRepository {
    fun observeBuckets(): Flow<List<Bucket>>

    suspend fun getBucket(bucketId: String): Bucket?
    suspend fun createBucket(): Result<Bucket>
    suspend fun deleteBucket(bucketId: String): Result<Unit>

    suspend fun createInvite(bucketId: String, inviteToken: String): Result<Unit>
    suspend fun joinBucket(bucketId: String, inviteToken: String): Result<Unit>
    suspend fun leaveBucket(bucketId: String): Result<Unit>

    suspend fun getBucketDevices(bucketId: String): Result<List<Device>>
    fun observeBucketDevices(bucketId: String): Flow<List<Device>>

    suspend fun uploadKeyAttestation(attestation: KeyAttestation): Result<Unit>
    suspend fun getKeyAttestations(deviceId: String): Result<List<KeyAttestation>>

    suspend fun saveBucket(bucket: Bucket)
    suspend fun saveDevice(device: Device)

    /**
     * Store a local-only human-readable name for a bucket.
     * This name is never sent to the server.
     */
    suspend fun storeLocalBucketName(bucketId: String, name: String)

    /**
     * Get the local human-readable name for a bucket.
     */
    suspend fun getLocalBucketName(bucketId: String): String?

    /**
     * Get the list of bucket IDs this device has access to.
     */
    suspend fun getAccessibleBuckets(): List<String>

    /**
     * Poll for a wrapped DEK for the current device in the given bucket.
     * Blocks until a wrapped key becomes available.
     */
    suspend fun waitForWrappedDek(bucketId: String): WrappedKeyResponse
}
