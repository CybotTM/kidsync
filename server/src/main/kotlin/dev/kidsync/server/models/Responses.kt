package dev.kidsync.server.models

import kotlinx.serialization.Serializable

// ---- Error ----

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
)

// ---- Device Registration ----

@Serializable
data class RegisterResponse(
    val deviceId: String,
)

// ---- Auth ----

@Serializable
data class ChallengeResponse(
    val nonce: String,
    val expiresAt: String,
)

@Serializable
data class VerifyResponse(
    val sessionToken: String,
    val expiresIn: Long,
)

// ---- Buckets ----

@Serializable
data class BucketResponse(
    val bucketId: String,
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val encryptionKey: String,
    val grantedAt: String,
)

// ---- Sync ----

@Serializable
data class OpResponse(
    val sequence: Long,
    val bucketId: String,
    val deviceId: String,
    val encryptedPayload: String,
    val prevHash: String,
    val currentHash: String,
    val keyEpoch: Int,
    val createdAt: String,
)

@Serializable
data class OpsBatchResponse(
    val accepted: Int,
    val latestSequence: Long,
)

// ---- Keys ----

@Serializable
data class WrappedKeyResponse(
    val wrappedDek: String,
    val keyEpoch: Int,
    val wrappedBy: String,
    val crossSignature: String? = null,
)

@Serializable
data class KeyAttestationResponse(
    val signerDeviceId: String,
    val attestedDeviceId: String,
    val attestedKey: String,
    val signature: String,
    val createdAt: String,
)

// ---- Snapshots ----

@Serializable
data class SnapshotResponse(
    val id: String,
    val atSequence: Long,
    val keyEpoch: Int,
    val sizeBytes: Long,
    val sha256Hash: String,
    val signature: String,
    val createdAt: String,
)

// ---- Checkpoints ----

@Serializable
data class CheckpointResponse(
    val startSequence: Long,
    val endSequence: Long,
    val hash: String,
    val opCount: Int,
)

// ---- Blobs ----

@Serializable
data class BlobResponse(
    val id: String,
    val sizeBytes: Long,
    val sha256Hash: String,
    val uploadedAt: String,
)

// ---- Recovery ----

@Serializable
data class RecoveryBlobResponse(
    val encryptedBlob: String,
    val createdAt: String,
)
