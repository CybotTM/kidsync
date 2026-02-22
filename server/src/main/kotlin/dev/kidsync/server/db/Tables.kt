package dev.kidsync.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// ---- Devices ----

object Devices : Table("devices") {
    val id = varchar("id", 36)
    val signingKey = text("signing_key").uniqueIndex()
    val encryptionKey = text("encryption_key")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ---- Buckets ----

object Buckets : Table("buckets") {
    val id = varchar("id", 36)
    val createdBy = varchar("created_by", 36).references(Devices.id)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ---- Bucket Access ----

object BucketAccess : Table("bucket_access") {
    val id = integer("id").autoIncrement()
    val bucketId = varchar("bucket_id", 36).references(Buckets.id)
    val deviceId = varchar("device_id", 36).references(Devices.id)
    val grantedAt = datetime("granted_at")
    val revokedAt = datetime("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

// ---- Ops ----

object Ops : Table("ops") {
    val sequence = long("sequence").autoIncrement()
    val bucketId = varchar("bucket_id", 36).references(Buckets.id)
    val deviceId = varchar("device_id", 36).references(Devices.id)
    val encryptedPayload = text("encrypted_payload")
    val prevHash = varchar("prev_hash", 64)
    val currentHash = varchar("current_hash", 64)
    val keyEpoch = integer("key_epoch")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(sequence)

    init {
        // SEC2-S-06: Unique constraint to prevent concurrent op uploads from the same device
        // with the same prevHash from both succeeding. The first insert wins; the second
        // will fail with a constraint violation and should be retried.
        uniqueIndex(deviceId, bucketId, prevHash)
    }
}

// ---- Blobs ----

object Blobs : Table("blobs") {
    val id = varchar("id", 36)
    val bucketId = varchar("bucket_id", 36).references(Buckets.id)
    val filePath = varchar("file_path", 500)
    val sizeBytes = long("size_bytes")
    val sha256Hash = varchar("sha256_hash", 64)
    val uploadedBy = varchar("uploaded_by", 36).references(Devices.id)
    val uploadedAt = datetime("uploaded_at")

    override val primaryKey = PrimaryKey(id)
}

// ---- Wrapped Keys ----

object WrappedKeys : Table("wrapped_keys") {
    val id = integer("id").autoIncrement()
    val targetDevice = varchar("target_device", 36).references(Devices.id)
    val wrappedDek = text("wrapped_dek")
    val keyEpoch = integer("key_epoch")
    val wrappedBy = varchar("wrapped_by", 36).references(Devices.id)
    val crossSignature = text("cross_signature").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(targetDevice, keyEpoch)
    }
}

// ---- Recovery Blobs ----
// SEC3-S-12: Includes version counter to track overwrites. In production, re-authentication
// should be required before allowing recovery blob overwrites.

object RecoveryBlobs : Table("recovery_blobs") {
    val deviceId = varchar("device_id", 36).references(Devices.id)
    val encryptedBlob = text("encrypted_blob")
    val version = integer("version").default(1)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(deviceId)
}

// ---- Push Tokens ----

object PushTokens : Table("push_tokens") {
    val deviceId = varchar("device_id", 36).references(Devices.id)
    val token = text("token")
    val platform = varchar("platform", 10)
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(deviceId)
}

// ---- Checkpoints ----

object Checkpoints : Table("checkpoints") {
    val id = integer("id").autoIncrement()
    val bucketId = varchar("bucket_id", 36).references(Buckets.id)
    val startSequence = long("start_sequence")
    val endSequence = long("end_sequence")
    val hash = varchar("hash", 64)
    val createdAt = datetime("created_at")
    val opCount = integer("op_count")

    override val primaryKey = PrimaryKey(id)
}

// ---- Snapshots ----

object Snapshots : Table("snapshots") {
    val id = varchar("id", 36)
    val bucketId = varchar("bucket_id", 36).references(Buckets.id)
    val deviceId = varchar("device_id", 36).references(Devices.id)
    val atSequence = long("at_sequence")
    val keyEpoch = integer("key_epoch")
    val sizeBytes = long("size_bytes")
    val sha256Hash = varchar("sha256_hash", 64)
    val signature = text("signature")
    val filePath = varchar("file_path", 500)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ---- Invite Tokens ----

object InviteTokens : Table("invite_tokens") {
    val tokenHash = varchar("token_hash", 64)
    val bucketId = varchar("bucket_id", 36).references(Buckets.id)
    val createdAt = datetime("created_at")
    val expiresAt = datetime("expires_at")
    val usedAt = datetime("used_at").nullable()

    override val primaryKey = PrimaryKey(tokenHash)
}

// ---- Sessions ----
// SEC3-S-01: Session tokens are stored as SHA-256 hashes, not plaintext.
// The raw token is returned to the client; only the hash is persisted.

// SEC4-S-16: TODO - The signingKey column is redundant here since it can be looked up
// via the Devices table using deviceId. Removing it would reduce storage and eliminate
// a potential data inconsistency if the device's signing key is rotated. This requires
// a DB migration and should be addressed when migration tooling is added.
object Sessions : Table("sessions") {
    val tokenHash = varchar("token_hash", 64)
    val deviceId = varchar("device_id", 36)
    val signingKey = text("signing_key")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)

    // SEC6-S-10: Index on deviceId for efficient session lookups and cleanup by device
    init { index(false, deviceId) }
}

// ---- Challenges ----

object Challenges : Table("challenges") {
    val nonce = varchar("nonce", 64)
    val signingKey = text("signing_key")
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(nonce)

    // SEC6-S-10: Index on signingKey for efficient challenge lookups by key
    init { index(false, signingKey) }
}

// ---- Key Attestations ----

object KeyAttestations : Table("key_attestations") {
    val id = integer("id").autoIncrement()
    val signerDevice = varchar("signer_device", 36).references(Devices.id)
    val attestedDevice = varchar("attested_device", 36).references(Devices.id)
    val attestedKey = text("attested_key")
    val signature = text("signature")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(signerDevice, attestedDevice)
    }
}
