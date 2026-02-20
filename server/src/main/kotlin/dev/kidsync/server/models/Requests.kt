package dev.kidsync.server.models

import kotlinx.serialization.Serializable

// ---- Auth ----

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    val totpCode: String? = null,
)

@Serializable
data class TotpVerifyRequest(
    val code: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

// ---- Families ----

@Serializable
data class CreateFamilyRequest(
    val name: String,
)

@Serializable
data class JoinFamilyRequest(
    val inviteToken: String,
    val devicePublicKey: String,
)

// ---- Devices ----

@Serializable
data class RegisterDeviceRequest(
    val deviceName: String,
    val publicKey: String,
)

// ---- Sync ----

@Serializable
data class UploadOpsRequest(
    val ops: List<OpInput>,
)

@Serializable
data class OpInput(
    val localId: String,
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
    val transitionTo: String? = null,
)

// ---- Sync Handshake ----

@Serializable
data class HandshakeRequest(
    val protocolVersion: Int,
    val appVersion: String,
    val deviceId: String,
)

// ---- Blobs ----
// (multipart, no request body DTO needed)

// ---- Push ----

@Serializable
data class RegisterPushRequest(
    val token: String,
    val platform: String,
)

// ---- Keys ----

@Serializable
data class UploadWrappedKeyRequest(
    val targetDeviceId: String,
    val wrappedDek: String,
    val keyEpoch: Int,
)

@Serializable
data class UploadRecoveryBlobRequest(
    val encryptedRecoveryBlob: String,
)

// ---- Snapshot metadata (multipart JSON part) ----

@Serializable
data class SnapshotMetadata(
    val deviceId: String,
    val atSequence: Long,
    val keyEpoch: Int,
    val sizeBytes: Long,
    val sha256: String,
    val signature: String,
    val createdAt: String,
)
