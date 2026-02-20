package com.kidsync.app.data.remote.api

import com.kidsync.app.data.remote.dto.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit interface matching the Co-Parenting Sync Server OpenAPI spec.
 *
 * All endpoints except /auth/register, /auth/login, and /auth/refresh
 * require a valid JWT Bearer token (added by AuthInterceptor).
 * Clients must send X-Protocol-Version: 1 on every request.
 */
interface ApiService {

    // ---- Auth ----

    @POST("auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("auth/refresh")
    suspend fun refreshToken(@Body request: RefreshRequest): Response<RefreshResponse>

    @POST("auth/totp/setup")
    suspend fun totpSetup(): Response<TotpSetupResponse>

    @POST("auth/totp/verify")
    suspend fun totpVerify(@Body request: TotpVerifyRequest): Response<TotpVerifyResponse>

    // ---- Families ----

    @POST("families")
    suspend fun createFamily(@Body request: CreateFamilyRequest): Response<CreateFamilyResponse>

    @POST("families/{familyId}/invite")
    suspend fun createInvite(@Path("familyId") familyId: String): Response<InviteResponse>

    @POST("families/{familyId}/join")
    suspend fun joinFamily(
        @Path("familyId") familyId: String,
        @Body request: JoinFamilyRequest
    ): Response<JoinFamilyResponse>

    @GET("families/{familyId}/members")
    suspend fun getMembers(@Path("familyId") familyId: String): Response<MembersResponse>

    // ---- Devices ----

    @POST("devices")
    suspend fun registerDevice(@Body request: RegisterDeviceRequest): Response<RegisterDeviceResponse>

    @GET("devices")
    suspend fun listDevices(): Response<DeviceListResponse>

    @DELETE("devices/{deviceId}")
    suspend fun revokeDevice(@Path("deviceId") deviceId: String): Response<Unit>

    /**
     * Get devices for a family. Uses the family members endpoint to extract devices.
     */
    @GET("families/{familyId}/members")
    suspend fun getDevices(@Path("familyId") familyId: String): Response<MembersResponse>

    // ---- Sync ----

    @POST("sync/ops")
    suspend fun uploadOps(@Body request: UploadOpsRequest): Response<UploadOpsResponse>

    @GET("sync/ops")
    suspend fun pullOps(
        @Query("since") since: Long,
        @Query("limit") limit: Int = 100
    ): Response<PullOpsResponse>

    @Multipart
    @POST("sync/snapshot")
    suspend fun uploadSnapshot(
        @Part("metadata") metadata: RequestBody,
        @Part snapshot: MultipartBody.Part
    ): Response<UploadSnapshotResponse>

    @GET("sync/snapshot/latest")
    suspend fun getLatestSnapshot(): Response<LatestSnapshotResponse>

    @GET("sync/checkpoint")
    suspend fun getCheckpoint(): Response<CheckpointResponse>

    // ---- Blobs ----

    @Multipart
    @POST("blobs")
    suspend fun uploadBlob(@Part file: MultipartBody.Part): Response<UploadBlobResponse>

    @GET("blobs/{blobId}")
    @Streaming
    suspend fun downloadBlob(@Path("blobId") blobId: String): Response<ResponseBody>

    @DELETE("blobs/{blobId}")
    suspend fun deleteBlob(@Path("blobId") blobId: String): Response<Unit>

    // ---- Push ----

    @POST("push/register")
    suspend fun registerPushToken(@Body request: RegisterPushRequest): Response<Unit>

    // ---- Wrapped Keys ----

    @POST("keys/wrapped")
    suspend fun uploadWrappedDek(
        @Body request: UploadWrappedKeyRequest
    ): Response<Unit>

    /**
     * Convenience overload accepting a map for the TinkKeyManager upload flow.
     */
    @POST("keys/wrapped")
    suspend fun uploadWrappedDek(
        @Path("familyId") familyId: String,
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>

    @GET("keys/wrapped/{deviceId}")
    suspend fun getWrappedKey(
        @Path("deviceId") deviceId: String,
        @Query("keyEpoch") keyEpoch: Int? = null
    ): Response<WrappedKeyResponse>

    /**
     * Get all wrapped DEKs for the current device within a family.
     */
    @GET("keys/wrapped")
    suspend fun getWrappedDeks(
        @Query("familyId") familyId: String
    ): Response<WrappedDeksResponse>

    // ---- Recovery ----

    @POST("keys/recovery")
    suspend fun uploadRecoveryDek(
        @Body request: UploadRecoveryBlobRequest
    ): Response<Unit>

    /**
     * Convenience overload accepting a map for the TinkKeyManager upload flow.
     */
    @POST("keys/recovery")
    suspend fun uploadRecoveryDek(
        @Path("familyId") familyId: String,
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): Response<Unit>

    @GET("keys/recovery")
    suspend fun getRecoveryDek(
        @Query("familyId") familyId: String
    ): Response<RecoveryBlobResponse>
}
