package com.kidsync.app.data.remote.dto

import kotlinx.serialization.Serializable

// ---- Auth ----

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String
)

@Serializable
data class RegisterResponse(
    val userId: String,
    val deviceId: String,
    val token: String,
    val refreshToken: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val totpCode: String? = null
)

@Serializable
data class LoginResponse(
    val userId: String,
    val token: String,
    val refreshToken: String
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class RefreshResponse(
    val token: String,
    val refreshToken: String
)

@Serializable
data class TotpSetupResponse(
    val secret: String,
    val qrCodeUri: String
)

@Serializable
data class TotpVerifyRequest(
    val code: String
)

@Serializable
data class TotpVerifyResponse(
    val verified: Boolean
)

// ---- Families ----

@Serializable
data class CreateFamilyRequest(
    val name: String,
    val solo: Boolean = false
)

@Serializable
data class CreateFamilyResponse(
    val familyId: String,
    val isSolo: Boolean = false
)

@Serializable
data class InviteResponse(
    val inviteToken: String,
    val expiresAt: String
)

@Serializable
data class JoinFamilyRequest(
    val inviteToken: String,
    val devicePublicKey: String
)

@Serializable
data class JoinFamilyResponse(
    val familyId: String,
    val members: List<FamilyMemberDto>
)

@Serializable
data class MembersResponse(
    val members: List<FamilyMemberDto>
)

@Serializable
data class FamilyMemberDto(
    val userId: String,
    val displayName: String,
    val role: String,
    val publicKey: String,
    val devices: List<DeviceSummaryDto>
)

@Serializable
data class DeviceSummaryDto(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val createdAt: String,
    val revokedAt: String? = null
)

// ---- Devices ----

@Serializable
data class RegisterDeviceRequest(
    val deviceName: String,
    val publicKey: String
)

@Serializable
data class RegisterDeviceResponse(
    val deviceId: String
)

@Serializable
data class DeviceListResponse(
    val devices: List<DeviceDto>
)

@Serializable
data class DeviceDto(
    val deviceId: String,
    val deviceName: String? = null,
    val publicKey: String,
    val createdAt: String? = null,
    val revokedAt: String? = null,
    val status: String? = null
)

// ---- Sync ----

@Serializable
data class UploadOpsRequest(
    val ops: List<OpInputDto>
)

@Serializable
data class OpInputDto(
    val deviceSequence: Long,
    val entityType: String,
    val entityId: String,
    val operation: String,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val keyEpoch: Int,
    val clientTimestamp: String,
    val protocolVersion: Int = 1,
    val signature: String = "",
    val transitionTo: String? = null,
    val localId: String? = null
)

@Serializable
data class UploadOpsResponse(
    val accepted: List<AcceptedOpDto>,
    val rejected: List<RejectedOpDto> = emptyList()
)

@Serializable
data class AcceptedOpDto(
    val deviceSequence: Long,
    val globalSequence: Long,
    val serverTimestamp: String
)

@Serializable
data class RejectedOpDto(
    val deviceSequence: Long,
    val error: String,
    val code: String? = null
)

@Serializable
data class PullOpsResponse(
    val ops: List<OpOutputDto>,
    val hasMore: Boolean,
    val latestSequence: Long
)

@Serializable
data class OpOutputDto(
    val globalSequence: Long,
    val deviceId: String,
    val deviceSequence: Long,
    val entityType: String? = null,
    val entityId: String? = null,
    val operation: String? = null,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String? = null,
    val keyEpoch: Int,
    val clientTimestamp: String? = null,
    val serverTimestamp: String
)

@Serializable
data class CheckpointResponse(
    val startSequence: Long,
    val endSequence: Long,
    val hash: String,
    val timestamp: String
)

@Serializable
data class UploadSnapshotResponse(
    val snapshotId: String,
    val sequence: Long
)

@Serializable
data class LatestSnapshotResponse(
    val snapshotId: String,
    val sequence: Long,
    val downloadUrl: String,
    val createdAt: String
)

// ---- Handshake ----

@Serializable
data class HandshakeRequest(
    val deviceId: String,
    val lastKnownSequence: Long,
    val protocolVersion: Int = 1
)

@Serializable
data class HandshakeResponse(
    val serverSequence: Long,
    val currentKeyEpoch: Int,
    val protocolVersion: Int,
    val serverTime: String
)

// ---- Blobs ----

@Serializable
data class UploadBlobResponse(
    val blobId: String,
    val sizeBytes: Long,
    val sha256Hash: String
)

// ---- Push ----

@Serializable
data class RegisterPushRequest(
    val token: String,
    val platform: String
)

// ---- Wrapped Keys ----

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

@Serializable
data class WrappedDeksResponse(
    val wrappedDeks: List<WrappedDekItem>
)

@Serializable
data class WrappedDekItem(
    val epoch: Int,
    val wrappedDek: String
)

// ---- Recovery ----

@Serializable
data class UploadRecoveryBlobRequest(
    val encryptedRecoveryBlob: String
)

@Serializable
data class RecoveryBlobResponse(
    val encryptedRecoveryBlob: String,
    val epoch: Int = 1,
    val wrappedDek: String = ""
)

// ---- Error ----

@Serializable
data class ErrorResponse(
    val error: String,
    val message: String,
    val details: Map<String, String>? = null
)
