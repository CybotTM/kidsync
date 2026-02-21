package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local storage for key cross-signatures (attestations).
 *
 * When Device A attests Device B's encryption key, the attestation is:
 *   signature = Ed25519_sign(attestedDeviceId || attestedEncryptionKey, signerPrivateKey)
 *
 * This allows any device to verify that an encryption key hasn't been
 * substituted by a compromised server.
 */
@Entity(
    tableName = "key_attestations",
    indices = [
        Index("signerDeviceId"),
        Index("attestedDeviceId"),
        Index(value = ["signerDeviceId", "attestedDeviceId"], unique = true)
    ]
)
data class KeyAttestationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val signerDeviceId: String,
    val attestedDeviceId: String,
    val attestedEncryptionKey: String,
    val signature: String,
    val createdAt: String
)
