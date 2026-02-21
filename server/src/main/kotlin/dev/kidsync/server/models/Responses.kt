package dev.kidsync.server.models

import kotlinx.serialization.Serializable

// ---- Health ----

@Serializable
data class HealthResponse(
    val status: String,
    val version: String? = null,
    val uptime: Long? = null,
)

// ---- Error ----

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val details: kotlinx.serialization.json.JsonObject? = null,
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
    val expiresIn: Int,
)

// ---- Buckets ----

@Serializable
data class BucketResponse(
    val bucketId: String,
)

@Serializable
data class InviteResponse(
    val expiresAt: String,
)

@Serializable
data class JoinResponse(
    val bucketId: String,
    val deviceId: String,
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val signingKey: String,
    val encryptionKey: String,
    val grantedAt: String,
)

@Serializable
data class DeviceListResponse(
    val devices: List<DeviceInfo>,
)

// ---- Sync ----

@Serializable
data class OpResponse(
    val globalSequence: Long,
    val deviceId: String,
    val encryptedPayload: String,
    val prevHash: String,
    val currentHash: String,
    val keyEpoch: Int,
    val serverTimestamp: String,
)

@Serializable
data class AcceptedOp(
    val index: Int,
    val globalSequence: Long,
    val serverTimestamp: String,
)

@Serializable
data class RejectedOp(
    val index: Int,
    val error: String,
    val message: String,
)

@Serializable
data class OpsBatchResponse(
    val accepted: List<AcceptedOp>,
    val rejected: List<RejectedOp>? = null,
)

@Serializable
data class PullOpsResponse(
    val ops: List<OpResponse>,
    val hasMore: Boolean,
    val latestSequence: Long,
)

// ---- Keys ----

@Serializable
data class WrappedKeyResponse(
    val wrappedDek: String,
    val keyEpoch: Int,
    val wrappedBy: String,
)

@Serializable
data class KeyAttestationResponse(
    val signerDevice: String,
    val attestedDevice: String,
    val attestedKey: String,
    val signature: String,
    val createdAt: String,
)

@Serializable
data class AttestationListResponse(
    val attestations: List<KeyAttestationResponse>,
)

// ---- Snapshots ----

@Serializable
data class UploadSnapshotResponse(
    val snapshotId: String,
    val atSequence: Long,
)

@Serializable
data class SnapshotResponse(
    val snapshotId: String,
    val deviceId: String,
    val atSequence: Long,
    val keyEpoch: Int,
    val sizeBytes: Long,
    val sha256: String,
    val signature: String,
    val createdAt: String,
)

// ---- Checkpoints ----

@Serializable
data class CheckpointData(
    val startSequence: Long,
    val endSequence: Long,
    val hash: String,
    val timestamp: String,
    val opCount: Int,
)

@Serializable
data class CheckpointResponse(
    val checkpoint: CheckpointData,
    val latestSequence: Long,
    val nextCheckpointAt: Long,
)

// ---- Blobs ----

@Serializable
data class BlobResponse(
    val blobId: String,
    val sizeBytes: Long,
    val sha256: String,
    val uploadedAt: String,
)

// ---- Recovery ----

@Serializable
data class RecoveryBlobResponse(
    val encryptedBlob: String,
    val createdAt: String,
)
