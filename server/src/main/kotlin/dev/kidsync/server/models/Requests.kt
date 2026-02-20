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
    val solo: Boolean = false,
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
    val deviceId: String,
    val deviceSequence: Int? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val operation: String? = null,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val devicePrevHash: String,
    val currentHash: String,
    val clientTimestamp: String? = null,
    val protocolVersion: Int = 1,
    val signature: String? = null,
    val transitionTo: String? = null,
    val localId: String? = null, // transient, not persisted
)

// ---- Sync Handshake ----

@Serializable
data class HandshakeRequest(
    val familyId: String,
    val deviceId: String,
    val protocolVersion: Int,
    val lastGlobalSequence: Long = 0,
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
