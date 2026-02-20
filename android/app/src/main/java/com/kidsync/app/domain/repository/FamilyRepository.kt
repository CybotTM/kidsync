package com.kidsync.app.domain.repository

import com.kidsync.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

interface FamilyRepository {
    fun observeFamily(familyId: UUID): Flow<Family?>
    fun observeMembers(familyId: UUID): Flow<List<FamilyMember>>
    fun observeDevices(familyId: UUID): Flow<List<Device>>

    suspend fun getFamily(familyId: UUID): Family?
    suspend fun getMembers(familyId: UUID): List<FamilyMember>
    suspend fun getDevices(familyId: UUID): List<Device>

    suspend fun createInvite(familyId: UUID): Result<String>
    suspend fun revokeDevice(familyId: UUID, deviceId: UUID): Result<Unit>

    suspend fun saveFamily(family: Family)
    suspend fun saveMember(member: FamilyMember)
    suspend fun saveDevice(device: Device)
}
