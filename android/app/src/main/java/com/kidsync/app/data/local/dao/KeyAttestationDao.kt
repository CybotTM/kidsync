package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.KeyAttestationEntity

@Dao
interface KeyAttestationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttestation(attestation: KeyAttestationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttestations(attestations: List<KeyAttestationEntity>)

    @Query("SELECT * FROM key_attestations WHERE attestedDeviceId = :deviceId")
    suspend fun getAttestationsForDevice(deviceId: String): List<KeyAttestationEntity>

    @Query("SELECT * FROM key_attestations WHERE signerDeviceId = :deviceId")
    suspend fun getAttestationsByDevice(deviceId: String): List<KeyAttestationEntity>

    @Query(
        """
        SELECT * FROM key_attestations
        WHERE signerDeviceId = :signerDeviceId AND attestedDeviceId = :attestedDeviceId
        """
    )
    suspend fun getAttestation(
        signerDeviceId: String,
        attestedDeviceId: String
    ): KeyAttestationEntity?

    @Query("DELETE FROM key_attestations WHERE attestedDeviceId = :deviceId")
    suspend fun deleteAttestationsForDevice(deviceId: String)

    @Query("DELETE FROM key_attestations")
    suspend fun deleteAll()
}
