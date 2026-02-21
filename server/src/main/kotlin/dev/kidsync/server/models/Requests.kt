package dev.kidsync.server.models

import kotlinx.serialization.Serializable

// ---- Device Registration ----

@Serializable
data class RegisterRequest(
    val signingKey: String,
    val encryptionKey: String,
)

// ---- Auth (Challenge-Response) ----

@Serializable
data class ChallengeRequest(
    val signingKey: String,
)

@Serializable
data class VerifyRequest(
    val signingKey: String,
    val nonce: String,
    val signature: String,
    val timestamp: String,
)

// ---- Buckets ----

@Serializable
class CreateBucketRequest

// ---- Invites ----

@Serializable
data class InviteRequest(
    val tokenHash: String,
)

@Serializable
data class JoinBucketRequest(
    val inviteToken: String,
)

// ---- Sync ----

@Serializable
data class OpInput(
    val deviceId: String,
    val keyEpoch: Int,
    val encryptedPayload: String,
    val prevHash: String,
    val currentHash: String,
)

@Serializable
data class OpsBatchRequest(
    val ops: List<OpInput>,
)

// ---- Keys ----

@Serializable
data class WrappedKeyRequest(
    val targetDevice: String,
    val wrappedDek: String,
    val keyEpoch: Int,
    val crossSignature: String? = null,
)

@Serializable
data class KeyAttestationRequest(
    val attestedDevice: String,
    val attestedKey: String,
    val signature: String,
)

// ---- Recovery ----

@Serializable
data class RecoveryBlobRequest(
    val encryptedBlob: String,
)

// ---- Push ----

@Serializable
data class PushTokenRequest(
    val token: String,
    val platform: String,
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
