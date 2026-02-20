package dev.kidsync.server.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ---- Error ----

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val details: JsonObject? = null,
)

// ---- Auth ----

@Serializable
data class RegisterResponse(
    val userId: String,
    val deviceId: String,
    val token: String,
    val refreshToken: String,
)

@Serializable
data class LoginResponse(
    val userId: String,
    val token: String,
    val refreshToken: String,
)

@Serializable
data class TotpSetupResponse(
    val secret: String,
    val qrCodeUri: String,
)

@Serializable
data class TotpVerifyResponse(
    val verified: Boolean = true,
)

@Serializable
data class RefreshResponse(
    val token: String,
    val refreshToken: String,
)

// ---- Families ----

@Serializable
data class CreateFamilyResponse(
    val familyId: String,
)

@Serializable
data class InviteResponse(
    val inviteToken: String,
    val expiresAt: String,
)

@Serializable
data class JoinFamilyResponse(
    val familyId: String,
    val members: List<FamilyMemberDto>,
)

@Serializable
data class MembersResponse(
    val members: List<FamilyMemberDto>,
)

@Serializable
data class FamilyMemberDto(
    val userId: String,
    val displayName: String,
    val role: String,
    val publicKey: String,
    val devices: List<DeviceSummaryDto>,
)

@Serializable
data class DeviceSummaryDto(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val createdAt: String,
    val revokedAt: String? = null,
)

// ---- Devices ----

@Serializable
data class RegisterDeviceResponse(
    val deviceId: String,
)

@Serializable
data class DeviceListResponse(
    val devices: List<DeviceDto>,
)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val createdAt: String,
    val revokedAt: String? = null,
)

// ---- Sync ----

@Serializable
data class UploadOpsResponse(
    val assignedSequences: List<AssignedSequence>,
)

@Serializable
data class AssignedSequence(
    val localId: String,
    val globalSequence: Long,
    val serverTimestamp: String,
)

@Serializable
data class PullOpsResponse(
    val ops: List<OpOutput>,
    val hasMore: Boolean,
    val latestSequence: Long,
)

@Serializable
data class OpOutput(
    val globalSequence: Long,
    val deviceId: String,
    val deviceSequence: Int? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val operation: String? = null,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String? = null,
    val keyEpoch: Int,
    val clientTimestamp: String? = null,
    val serverTimestamp: String,
    val transitionTo: String? = null,
)

@Serializable
data class UploadSnapshotResponse(
    val snapshotId: String,
    val sequence: Long,
)

@Serializable
data class LatestSnapshotResponse(
    val snapshotId: String,
    val deviceId: String? = null,
    val atSequence: Long? = null,
    val sequence: Long,
    val keyEpoch: Int? = null,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
    val signature: String? = null,
    val createdAt: String,
    val downloadUrl: String,
    val downloadUrlExpiresAt: String? = null,
)

@Serializable
data class CheckpointResponse(
    val checkpoint: CheckpointDto? = null,
    val latestSequence: Long,
    val nextCheckpointAt: Long,
)

@Serializable
data class CheckpointDto(
    val startSequence: Long,
    val endSequence: Long,
    val hash: String,
    val timestamp: String,
    val opCount: Int,
)

// ---- Handshake ----

@Serializable
data class HandshakeResponse(
    val ok: Boolean,
    val serverVersion: String? = null,
    val protocolVersion: Int? = null,
    val minProtocolVersion: Int? = null,
    val maxProtocolVersion: Int? = null,
    val latestSequence: Long? = null,
    val serverTime: String? = null,
    val error: String? = null,
    val message: String? = null,
    val upgradeUrl: String? = null,
)

// ---- Blobs ----

@Serializable
data class UploadBlobResponse(
    val blobId: String,
    val sizeBytes: Long,
    val sha256Hash: String,
)

// ---- Keys ----

@Serializable
data class WrappedKeyResponse(
    val wrappedDek: String,
    val keyEpoch: Int,
    val wrappedBy: String,
)

@Serializable
data class RecoveryBlobResponse(
    val encryptedRecoveryBlob: String,
)
