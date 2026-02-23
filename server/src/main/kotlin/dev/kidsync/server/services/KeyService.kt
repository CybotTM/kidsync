package dev.kidsync.server.services

import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.ValidationUtil
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class KeyService {

    private val logger = LoggerFactory.getLogger(KeyService::class.java)

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    // ---- Wrapped Keys ----
    // SEC3-S-17: DEFERRED - Key rotation mechanism. When a device is revoked, remaining
    // devices should rotate to a new epoch and re-wrap the DEK. Requires protocol design:
    // epoch semantics, backward compatibility for offline devices, and rotation triggers.
    // Tracked as future protocol-level work.

    /**
     * Upload a wrapped DEK for a target device.
     * SEC-S-07: The caller must share at least one active bucket with the target device.
     */
    suspend fun uploadWrappedKey(callerDeviceId: String, request: WrappedKeyRequest) {
        // SEC6-S-19: Minimum length for wrappedDek (44 chars = minimum base64 for a wrapped key)
        if (request.wrappedDek.length < 44) {
            throw ApiException(400, "INVALID_REQUEST", "wrappedDek is too short")
        }
        if (request.wrappedDek.length > 8192) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "Wrapped key exceeds maximum size of 8KB")
        }

        // SEC3-S-23: Validate keyEpoch >= 1
        if (request.keyEpoch < 1) {
            throw ApiException(400, "INVALID_REQUEST", "keyEpoch must be >= 1")
        }

        dbQuery {
            // Verify target device exists
            Devices.selectAll().where { Devices.id eq request.targetDevice }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Target device not found")

            // SEC-S-07: Verify caller and target share at least one active bucket
            val callerBuckets = BucketAccess.selectAll()
                .where { (BucketAccess.deviceId eq callerDeviceId) and BucketAccess.revokedAt.isNull() }
                .map { it[BucketAccess.bucketId] }

            val sharedBucket = BucketAccess.selectAll()
                .where {
                    (BucketAccess.deviceId eq request.targetDevice) and
                        BucketAccess.revokedAt.isNull() and
                        (BucketAccess.bucketId inList callerBuckets)
                }
                .firstOrNull()

            if (sharedBucket == null) {
                throw ApiException(403, "BUCKET_ACCESS_DENIED", "Devices must share a bucket")
            }

            // SEC3-S-22: wrappedDek max length is validated above (8192 bytes).
            // Note: base64 format validation is intentionally not enforced here because
            // wrappedDek is opaque encrypted data whose encoding is determined by the client.

            // SEC2-S-23: Check if a wrapped key already exists for this target + epoch.
            // Return 409 Conflict instead of silently overwriting, so clients are aware
            // of potential key epoch collisions and can handle them explicitly.
            val existing = WrappedKeys.selectAll().where {
                (WrappedKeys.targetDevice eq request.targetDevice) and
                    (WrappedKeys.keyEpoch eq request.keyEpoch)
            }.firstOrNull()

            if (existing != null) {
                throw ApiException(
                    409,
                    "KEY_ALREADY_EXISTS",
                    "A wrapped key already exists for this target device and epoch"
                )
            }

            WrappedKeys.insert {
                it[targetDevice] = request.targetDevice
                it[wrappedDek] = request.wrappedDek
                it[keyEpoch] = request.keyEpoch
                it[wrappedBy] = callerDeviceId
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
        )
    }

    // ---- Key Attestations ----

    /**
     * Upload a key cross-signature (attestation).
     * The signer device attests that the attested device owns the specified encryption key.
     *
     * SEC2-S-19: The attestation signature is NOT verified server-side. This is by design:
     * the server operates in a zero-knowledge model where it stores but does not interpret
     * cryptographic material. Clients are responsible for verifying attestation signatures
     * when consuming them. The server merely provides authenticated storage and relay.
     */
    suspend fun uploadAttestation(signerDeviceId: String, request: KeyAttestationRequest) {
        if (request.signature.length > 4096) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "Attestation signature exceeds maximum size of 4KB")
        }

        dbQuery {
            // Verify attested device exists
            Devices.selectAll().where { Devices.id eq request.attestedDevice }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Attested device not found")

            // SEC3-S-22: Validate attestedKey max length (checked after device existence for
            // backward compatibility - nonexistent device should still return 404)
            if (request.attestedKey.length > 8192) {
                throw ApiException(413, "PAYLOAD_TOO_LARGE", "attestedKey exceeds maximum size")
            }

            // SEC3-S-07: Verify signer and attested device share at least one active bucket
            val signerBuckets = BucketAccess.selectAll()
                .where { (BucketAccess.deviceId eq signerDeviceId) and BucketAccess.revokedAt.isNull() }
                .map { it[BucketAccess.bucketId] }

            val sharedBucket = BucketAccess.selectAll()
                .where {
                    (BucketAccess.deviceId eq request.attestedDevice) and
                        BucketAccess.revokedAt.isNull() and
                        (BucketAccess.bucketId inList signerBuckets)
                }
                .firstOrNull()

            if (sharedBucket == null) {
                throw ApiException(403, "BUCKET_ACCESS_DENIED", "Devices must share a bucket to create attestations")
            }

            // Check if attestation already exists for this signer+attested pair
            val existing = KeyAttestations.selectAll().where {
                (KeyAttestations.signerDevice eq signerDeviceId) and
                    (KeyAttestations.attestedDevice eq request.attestedDevice)
            }.firstOrNull()

            if (existing != null) {
                throw ApiException(409, "INVALID_REQUEST", "Attestation already exists for this signer-attested pair")
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
     * SEC4-S-03: The caller must share at least one active bucket with the attested device.
     */
    suspend fun getAttestations(callerDeviceId: String, attestedDeviceId: String): AttestationListResponse {
        return dbQuery {
            // SEC4-S-03: Verify caller shares at least one active bucket with the attested device
            val callerBuckets = BucketAccess.selectAll()
                .where { (BucketAccess.deviceId eq callerDeviceId) and BucketAccess.revokedAt.isNull() }
                .map { it[BucketAccess.bucketId] }

            val sharedBucket = BucketAccess.selectAll()
                .where {
                    (BucketAccess.deviceId eq attestedDeviceId) and
                        BucketAccess.revokedAt.isNull() and
                        (BucketAccess.bucketId inList callerBuckets)
                }
                .firstOrNull()

            if (sharedBucket == null) {
                throw ApiException(403, "BUCKET_ACCESS_DENIED", "Devices must share a bucket to query attestations")
            }

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
     * SEC3-S-12: Tracks version counter for overwrites and logs changes.
     * SEC4-S-14: Rate limits overwrites to max 1 per hour per device.
     * Full re-authentication would require protocol changes, so rate limiting
     * is the pragmatic mitigation.
     */
    suspend fun uploadRecoveryBlob(deviceId: String, request: RecoveryBlobRequest) {
        // SEC6-S-02: Validate by byte count, not character count (multi-byte chars inflate size)
        if (request.encryptedBlob.toByteArray(Charsets.UTF_8).size > 1_048_576) {
            throw ApiException(413, "PAYLOAD_TOO_LARGE", "Recovery blob exceeds maximum size of 1MB")
        }

        dbQuery {
            // SEC3-S-12: Check existing version before replacing
            val existing = RecoveryBlobs.selectAll()
                .where { RecoveryBlobs.deviceId eq deviceId }
                .firstOrNull()

            val newVersion = if (existing != null) {
                val oldVersion = existing[RecoveryBlobs.version]

                // SEC5-S-06: Rate limit recovery blob overwrites using DB timestamp (survives restarts)
                val existingCreatedAt = existing[RecoveryBlobs.createdAt]
                val now = LocalDateTime.now(ZoneOffset.UTC)
                val age = Duration.between(existingCreatedAt, now)
                if (age.toHours() < 1) {
                    logger.warn("Recovery blob overwrite rate limited: device={}", deviceId)
                    throw ApiException(429, "RATE_LIMITED", "Recovery blob can only be overwritten once per hour")
                }

                RecoveryBlobs.deleteWhere { RecoveryBlobs.deviceId eq deviceId }
                logger.warn("Recovery blob overwritten: device={} oldVersion={} newVersion={}", deviceId, oldVersion, oldVersion + 1)
                oldVersion + 1
            } else {
                1
            }

            RecoveryBlobs.insert {
                it[RecoveryBlobs.deviceId] = deviceId
                it[encryptedBlob] = request.encryptedBlob
                it[version] = newVersion
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
