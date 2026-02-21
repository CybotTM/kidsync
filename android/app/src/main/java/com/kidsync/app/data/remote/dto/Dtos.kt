package com.kidsync.app.data.remote.dto

import kotlinx.serialization.Serializable

// ── Registration ────────────────────────────────────────────────────────────

@Serializable
data class RegisterRequest(
    val signingKey: String,
    val encryptionKey: String
)

@Serializable
data class RegisterResponse(
    val deviceId: String
)

// ── Challenge-Response Auth ─────────────────────────────────────────────────

@Serializable
data class ChallengeRequest(
    val signingKey: String
)

@Serializable
data class ChallengeResponse(
    val nonce: String,
    val expiresAt: String
)

@Serializable
data class VerifyRequest(
    val signingKey: String,
    val nonce: String,
    val signature: String,
    val timestamp: String
)

@Serializable
data class VerifyResponse(
    val sessionToken: String,
    val expiresIn: Long
)

// ── Buckets ─────────────────────────────────────────────────────────────────

@Serializable
data class BucketResponse(
    val bucketId: String
)

@Serializable
data class InviteRequest(
    val tokenHash: String
)

@Serializable
data class JoinBucketRequest(
    val inviteToken: String
)

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val signingKey: String,
    val encryptionKey: String,
    val createdAt: String
)

// ── Ops ─────────────────────────────────────────────────────────────────────

@Serializable
data class OpsBatchRequest(
    val ops: List<OpInputDto>
)

@Serializable
data class OpInputDto(
    val deviceId: String,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val prevHash: String,
    val currentHash: String
)

@Serializable
data class OpsBatchResponse(
    val accepted: List<AcceptedOpDto>,
    val rejected: List<RejectedOpDto> = emptyList()
)

@Serializable
data class AcceptedOpDto(
    val sequence: Long,
    val serverTimestamp: String
)

@Serializable
data class RejectedOpDto(
    val index: Int,
    val error: String,
    val code: String? = null
)

@Serializable
data class OpResponse(
    val sequence: Long,
    val deviceId: String,
    val encryptedPayload: String,
    val prevHash: String,
    val currentHash: String,
    val keyEpoch: Int,
    val createdAt: String
)

@Serializable
data class CheckpointResponse(
    val startSequence: Long,
    val endSequence: Long,
    val hash: String,
    val opCount: Int,
    val createdAt: String
)

// ── Blobs ───────────────────────────────────────────────────────────────────

@Serializable
data class UploadBlobResponse(
    val blobId: String,
    val sizeBytes: Long,
    val sha256Hash: String
)

// ── Snapshots ───────────────────────────────────────────────────────────────

@Serializable
data class UploadSnapshotResponse(
    val snapshotId: String,
    val atSequence: Long
)

@Serializable
data class LatestSnapshotResponse(
    val snapshotId: String,
    val atSequence: Long,
    val keyEpoch: Int,
    val sizeBytes: Long,
    val sha256Hash: String,
    val signature: String,
    val downloadUrl: String,
    val createdAt: String
)

// ── Wrapped Keys ────────────────────────────────────────────────────────────

@Serializable
data class UploadWrappedKeyRequest(
    val targetDeviceId: String,
    val wrappedDek: String,
    val keyEpoch: Int
)

@Serializable
data class WrappedKeyResponse(
    val wrappedDek: String,
    val keyEpoch: Int,
    val wrappedBy: String
)

// ── Key Attestations ────────────────────────────────────────────────────────

@Serializable
data class UploadAttestationRequest(
    val attestedDeviceId: String,
    val attestedKey: String,
    val signature: String
)

@Serializable
data class AttestationResponse(
    val id: Int,
    val signerDevice: String,
    val attestedDevice: String,
    val attestedKey: String,
    val signature: String,
    val createdAt: String
)

// ── Recovery ────────────────────────────────────────────────────────────────

@Serializable
data class RecoveryBlobResponse(
    val encryptedBlob: String,
    val createdAt: String
)

// ── Push ────────────────────────────────────────────────────────────────────

@Serializable
data class RegisterPushRequest(
    val token: String,
    val platform: String
)

// ── Error ───────────────────────────────────────────────────────────────────

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val details: Map<String, String>? = null
)
