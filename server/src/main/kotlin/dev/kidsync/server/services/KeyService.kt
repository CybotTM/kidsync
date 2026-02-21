package dev.kidsync.server.services

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class KeyService {

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    // ---- Wrapped Keys ----

    /**
     * Upload a wrapped DEK for a target device.
     * The caller must be a device (authenticated). No family/bucket check --
     * the wrapping device is responsible for only wrapping for devices it trusts.
     */
    suspend fun uploadWrappedKey(callerDeviceId: String, request: WrappedKeyRequest) {
        if (request.wrappedDek.length > 8192) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "Wrapped key exceeds maximum size of 8KB")
        }

        dbQuery {
            // Verify target device exists
            Devices.selectAll().where { Devices.id eq request.targetDevice }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Target device not found")

            // Upsert: replace existing wrapped key for this device + epoch
            WrappedKeys.deleteWhere {
                (targetDevice eq request.targetDevice) and (keyEpoch eq request.keyEpoch)
            }

            WrappedKeys.insert {
                it[targetDevice] = request.targetDevice
                it[wrappedDek] = request.wrappedDek
                it[keyEpoch] = request.keyEpoch
                it[wrappedBy] = callerDeviceId
                it[crossSignature] = request.crossSignature
                it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    /**
     * Get a wrapped DEK for the authenticated device.
     * If epoch is specified, get that specific epoch; otherwise get the latest.
     */
    suspend fun getWrappedKey(deviceId: String, keyEpoch: Int?): WrappedKeyResponse {
        val result = dbQuery {
            val query = if (keyEpoch != null) {
                WrappedKeys.selectAll().where {
                    (WrappedKeys.targetDevice eq deviceId) and (WrappedKeys.keyEpoch eq keyEpoch)
                }
            } else {
                WrappedKeys.selectAll()
                    .where { WrappedKeys.targetDevice eq deviceId }
                    .orderBy(WrappedKeys.keyEpoch, SortOrder.DESC)
                    .limit(1)
            }

            query.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "No wrapped key found for this device")
        }

        return WrappedKeyResponse(
            wrappedDek = result[WrappedKeys.wrappedDek],
            keyEpoch = result[WrappedKeys.keyEpoch],
            wrappedBy = result[WrappedKeys.wrappedBy],
            crossSignature = result[WrappedKeys.crossSignature],
        )
    }

    // ---- Key Attestations ----

    /**
     * Upload a key cross-signature (attestation).
     * The signer device attests that the attested device owns the specified encryption key.
     */
    suspend fun uploadAttestation(signerDeviceId: String, request: KeyAttestationRequest) {
        if (request.signature.length > 4096) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "Attestation signature exceeds maximum size of 4KB")
        }

        dbQuery {
            // Verify attested device exists
            Devices.selectAll().where { Devices.id eq request.attestedDevice }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Attested device not found")

            // Check if attestation already exists for this signer+attested pair
            val existing = KeyAttestations.selectAll().where {
                (KeyAttestations.signerDevice eq signerDeviceId) and
                    (KeyAttestations.attestedDevice eq request.attestedDevice)
            }.firstOrNull()

            if (existing != null) {
                throw ApiException(409, "CONFLICT", "Attestation already exists for this signer-attested pair")
            }

            KeyAttestations.insert {
                it[signerDevice] = signerDeviceId
                it[attestedDevice] = request.attestedDevice
                it[attestedKey] = request.attestedKey
                it[signature] = request.signature
                it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    /**
     * Get all attestations for a given device (i.e., attestations where that device is the attested party).
     */
    suspend fun getAttestations(attestedDeviceId: String): AttestationListResponse {
        return dbQuery {
            val attestations = KeyAttestations.selectAll()
                .where { KeyAttestations.attestedDevice eq attestedDeviceId }
                .map { row ->
                    KeyAttestationResponse(
                        signerDevice = row[KeyAttestations.signerDevice],
                        attestedDevice = row[KeyAttestations.attestedDevice],
                        attestedKey = row[KeyAttestations.attestedKey],
                        signature = row[KeyAttestations.signature],
                        createdAt = row[KeyAttestations.createdAt]
                            .atOffset(ZoneOffset.UTC)
                            .format(isoFormatter),
                    )
                }

            AttestationListResponse(attestations = attestations)
        }
    }

    // ---- Recovery Blobs ----

    /**
     * Upload an encrypted recovery blob for the authenticated device.
     */
    suspend fun uploadRecoveryBlob(deviceId: String, request: RecoveryBlobRequest) {
        if (request.encryptedBlob.length > 1_048_576) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "Recovery blob exceeds maximum size of 1MB")
        }

        dbQuery {
            // Upsert: replace existing recovery blob
            RecoveryBlobs.deleteWhere { RecoveryBlobs.deviceId eq deviceId }

            RecoveryBlobs.insert {
                it[RecoveryBlobs.deviceId] = deviceId
                it[encryptedBlob] = request.encryptedBlob
                it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    /**
     * Download the encrypted recovery blob for the authenticated device.
     */
    suspend fun getRecoveryBlob(deviceId: String): RecoveryBlobResponse {
        val result = dbQuery {
            RecoveryBlobs.selectAll().where { RecoveryBlobs.deviceId eq deviceId }
                .firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "No recovery blob found")
        }

        return RecoveryBlobResponse(
            encryptedBlob = result[RecoveryBlobs.encryptedBlob],
            createdAt = result[RecoveryBlobs.createdAt]
                .atOffset(ZoneOffset.UTC)
                .format(isoFormatter),
        )
    }
}
