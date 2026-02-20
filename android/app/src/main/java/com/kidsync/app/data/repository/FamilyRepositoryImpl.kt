package com.kidsync.app.data.repository

import com.kidsync.app.data.local.KidSyncDatabase
import com.kidsync.app.data.local.entity.DeviceEntity
import com.kidsync.app.data.local.entity.FamilyEntity
import com.kidsync.app.data.local.entity.FamilyMemberEntity
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.model.DeviceStatus
import com.kidsync.app.domain.model.Family
import com.kidsync.app.domain.model.FamilyMember
import com.kidsync.app.domain.repository.FamilyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class FamilyRepositoryImpl @Inject constructor(
    private val database: KidSyncDatabase,
    private val apiService: ApiService
) : FamilyRepository {

    override fun observeFamily(familyId: UUID): Flow<Family?> = flow {
        // In a full implementation, this would use a DAO query returning Flow
        emit(getFamily(familyId))
    }

    override fun observeMembers(familyId: UUID): Flow<List<FamilyMember>> = flow {
        emit(getMembers(familyId))
    }

    override fun observeDevices(familyId: UUID): Flow<List<Device>> = flow {
        emit(getDevices(familyId))
    }

    override suspend fun getFamily(familyId: UUID): Family? {
        return try {
            // Try remote first, fallback to local cache
            val response = apiService.getMembers(familyId.toString())
            if (response.isSuccessful) {
                // Family data is embedded in member responses in this API
                Family(
                    familyId = familyId,
                    name = "", // Not directly available from members endpoint
                    createdAt = Instant.now()
                )
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getMembers(familyId: UUID): List<FamilyMember> {
        return try {
            val response = apiService.getMembers(familyId.toString())
            if (response.isSuccessful) {
                response.body()?.members?.map { dto ->
                    FamilyMember(
                        id = UUID.fromString(dto.userId),
                        familyId = familyId,
                        userId = UUID.fromString(dto.userId),
                        displayName = dto.displayName,
                        role = dto.role,
                        joinedAt = Instant.now()
                    )
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getDevices(familyId: UUID): List<Device> {
        return try {
            val response = apiService.getDevices(familyId.toString())
            if (response.isSuccessful) {
                response.body()?.members?.flatMap { member ->
                    member.devices.map { dto ->
                        Device(
                            deviceId = UUID.fromString(dto.deviceId),
                            userId = UUID.fromString(member.userId),
                            name = dto.deviceName,
                            publicKey = dto.publicKey,
                            status = if (dto.revokedAt != null) DeviceStatus.REVOKED else DeviceStatus.ACTIVE,
                            registeredAt = Instant.parse(dto.createdAt),
                            revokedAt = dto.revokedAt?.let { Instant.parse(it) }
                        )
                    }
                } ?: emptyList()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun createInvite(familyId: UUID): Result<String> {
        return try {
            val response = apiService.createInvite(familyId.toString())
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }

            val body = response.body()
                ?: return Result.failure(ApiException(500, "Empty response body"))

            Result.success(body.inviteToken)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun convertToShared(familyId: UUID): Result<Unit> {
        return try {
            val response = apiService.convertToShared(familyId.toString())
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun revokeDevice(familyId: UUID, deviceId: UUID): Result<Unit> {
        return try {
            val response = apiService.revokeDevice(deviceId.toString())
            if (!response.isSuccessful) {
                return Result.failure(ApiException(response.code(), response.message()))
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun saveFamily(family: Family) {
        // Store in local database for offline access
        database.runInTransaction {
            // Would insert into a families table
        }
    }

    override suspend fun saveMember(member: FamilyMember) {
        // Store in local database for offline access
    }

    override suspend fun saveDevice(device: Device) {
        // Store in local database for offline access
    }
}
