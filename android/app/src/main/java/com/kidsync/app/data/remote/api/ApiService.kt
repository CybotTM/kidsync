package com.kidsync.app.data.remote.api

import com.kidsync.app.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface for the KidSync zero-knowledge server API.
 *
 * All endpoints except /register, /auth/*, and /health require a valid
 * session token (added by AuthInterceptor).
 *
 * The server stores only opaque encrypted blobs and has no visibility
 * into user identity, relationships, or data content.
 */
interface ApiService {

    // ── Registration ────────────────────────────────────────────────────────────

    @POST("register")
    suspend fun register(@Body request: RegisterRequest): RegisterResponse

    // ── Challenge-Response Auth ─────────────────────────────────────────────────

    @POST("auth/challenge")
    suspend fun requestChallenge(@Body request: ChallengeRequest): ChallengeResponse

    @POST("auth/verify")
    suspend fun verifyChallenge(@Body request: VerifyRequest): VerifyResponse

    // ── Buckets ─────────────────────────────────────────────────────────────────

    @POST("buckets")
    suspend fun createBucket(): BucketResponse

    @DELETE("buckets/{bucketId}")
    suspend fun deleteBucket(@Path("bucketId") bucketId: String)

    @POST("buckets/{bucketId}/invite")
    suspend fun createInvite(
        @Path("bucketId") bucketId: String,
        @Body request: InviteRequest
    ): Response<Unit>

    @POST("buckets/{bucketId}/join")
    suspend fun joinBucket(
        @Path("bucketId") bucketId: String,
        @Body request: JoinBucketRequest
    )

    @GET("buckets/{bucketId}/devices")
    suspend fun getBucketDevices(@Path("bucketId") bucketId: String): List<DeviceInfo>

    @DELETE("buckets/{bucketId}/devices/me")
    suspend fun leaveBucket(@Path("bucketId") bucketId: String)

    // ── Ops ─────────────────────────────────────────────────────────────────────

    @POST("buckets/{bucketId}/ops")
    suspend fun uploadOps(
        @Path("bucketId") bucketId: String,
        @Body request: OpsBatchRequest
    ): OpsBatchResponse

    @GET("buckets/{bucketId}/ops")
    suspend fun pullOps(
        @Path("bucketId") bucketId: String,
        @Query("since") since: Long
    ): List<OpResponse>

    @GET("buckets/{bucketId}/checkpoint")
    suspend fun getCheckpoint(@Path("bucketId") bucketId: String): CheckpointResponse

    // ── Blobs ───────────────────────────────────────────────────────────────────

    @Multipart
    @POST("buckets/{bucketId}/blobs")
    suspend fun uploadBlob(
        @Path("bucketId") bucketId: String,
        @Part file: MultipartBody.Part
    ): UploadBlobResponse

    @GET("buckets/{bucketId}/blobs/{blobId}")
    @Streaming
    suspend fun downloadBlob(
        @Path("bucketId") bucketId: String,
        @Path("blobId") blobId: String
    ): ResponseBody

    // ── Snapshots ───────────────────────────────────────────────────────────────

    @Multipart
    @POST("buckets/{bucketId}/snapshots")
    suspend fun uploadSnapshot(
        @Path("bucketId") bucketId: String,
        @Part("metadata") metadata: RequestBody,
        @Part snapshot: MultipartBody.Part
    ): UploadSnapshotResponse

    @GET("buckets/{bucketId}/snapshots/latest")
    suspend fun getLatestSnapshot(
        @Path("bucketId") bucketId: String
    ): LatestSnapshotResponse

    // ── Wrapped Keys ────────────────────────────────────────────────────────────

    @POST("keys/wrapped")
    suspend fun uploadWrappedKey(@Body request: UploadWrappedKeyRequest)

    @GET("keys/wrapped")
    suspend fun getWrappedDeks(
        @Query("epoch") epoch: Int? = null
    ): List<WrappedKeyResponse>

    // ── Key Attestations ────────────────────────────────────────────────────────

    @POST("keys/attestations")
    suspend fun uploadAttestation(@Body request: UploadAttestationRequest)

    @GET("keys/attestations/{deviceId}")
    suspend fun getAttestations(
        @Path("deviceId") deviceId: String
    ): List<AttestationResponse>

    // ── Recovery ────────────────────────────────────────────────────────────────

    @POST("recovery")
    suspend fun uploadRecoveryBlob(@Body encryptedBlob: String)

    @GET("recovery")
    suspend fun getRecoveryBlob(): RecoveryBlobResponse

    // ── Push ────────────────────────────────────────────────────────────────────

    @POST("push/token")
    suspend fun registerPushToken(@Body request: RegisterPushRequest)

    // ── Health ──────────────────────────────────────────────────────────────────

    @GET("health")
    suspend fun health(): Response<Unit>
}
