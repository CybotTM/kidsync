package dev.kidsync.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// ---- Users ----

object Users : Table("users") {
    val id = varchar("id", 36)
    val email = varchar("email", 254).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val totpSecret = varchar("totp_secret", 255).nullable()
    val totpEnabled = bool("totp_enabled").default(false)
    val displayName = varchar("display_name", 100).default("")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ---- Devices ----

object Devices : Table("devices") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(Users.id)
    val publicKey = text("public_key")
    val deviceName = varchar("device_name", 100)
    val createdAt = datetime("created_at")
    val revokedAt = datetime("revoked_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

// ---- Families ----

object Families : Table("families") {
    val id = varchar("id", 36)
    val name = varchar("name", 100)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ---- Family Members ----

object FamilyMembers : Table("family_members") {
    val userId = varchar("user_id", 36).references(Users.id)
    val familyId = varchar("family_id", 36).references(Families.id)
    val role = varchar("role", 20) // ADMIN or MEMBER
    val joinedAt = datetime("joined_at")

    override val primaryKey = PrimaryKey(userId, familyId)
}

// ---- Op Log ----

object OpLog : Table("op_log") {
    val globalSequence = long("global_sequence").autoIncrement()
    val familyId = varchar("family_id", 36).references(Families.id)
    val deviceId = varchar("device_id", 36).references(Devices.id)
    val deviceSequence = integer("device_sequence").nullable()
    val entityType = varchar("entity_type", 50).nullable()
    val entityId = varchar("entity_id", 36).nullable()
    val operation = varchar("operation", 10).nullable()
    val devicePrevHash = varchar("device_prev_hash", 64)
    val currentHash = varchar("current_hash", 64).nullable()
    val encryptedPayload = text("encrypted_payload")
    val keyEpoch = integer("key_epoch")
    val clientTimestamp = varchar("client_timestamp", 50).nullable()
    val serverTimestamp = varchar("server_timestamp", 50)
    val protocolVersion = integer("protocol_version").default(1)
    val transitionTo = varchar("transition_to", 20).nullable()

    override val primaryKey = PrimaryKey(globalSequence)
}

// ---- Blobs ----

object Blobs : Table("blobs") {
    val id = varchar("id", 36)
    val familyId = varchar("family_id", 36).references(Families.id)
    val filePath = varchar("file_path", 500)
    val sizeBytes = long("size_bytes")
    val sha256Hash = varchar("sha256_hash", 64)
    val uploadedBy = varchar("uploaded_by", 36).references(Users.id)
    val uploadedAt = datetime("uploaded_at")
    val deletedAt = datetime("deleted_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

// ---- Push Tokens ----

object PushTokens : Table("push_tokens") {
    val deviceId = varchar("device_id", 36).references(Devices.id)
    val token = text("token")
    val platform = varchar("platform", 10) // FCM or APNS
    val updatedAt = datetime("updated_at")

    override val primaryKey = PrimaryKey(deviceId)
}

// ---- Invites ----

object Invites : Table("invites") {
    val id = varchar("id", 36)
    val familyId = varchar("family_id", 36).references(Families.id)
    val createdBy = varchar("created_by", 36).references(Users.id)
    val token = varchar("token", 100).uniqueIndex()
    val expiresAt = datetime("expires_at")
    val usedAt = datetime("used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

// ---- Refresh Tokens ----

object RefreshTokens : Table("refresh_tokens") {
    val id = varchar("id", 36)
    val userId = varchar("user_id", 36).references(Users.id)
    val tokenHash = varchar("token_hash", 64).index()
    val expiresAt = datetime("expires_at")
    val revokedAt = datetime("revoked_at").nullable()
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)
}

// ---- Snapshots ----

object Snapshots : Table("snapshots") {
    val id = varchar("id", 36)
    val familyId = varchar("family_id", 36).references(Families.id)
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

// ---- Checkpoints ----

object Checkpoints : Table("checkpoints") {
    val id = integer("id").autoIncrement()
    val familyId = varchar("family_id", 36).references(Families.id)
    val startSequence = long("start_sequence")
    val endSequence = long("end_sequence")
    val hash = varchar("hash", 64)
    val timestamp = datetime("timestamp")
    val opCount = integer("op_count")

    override val primaryKey = PrimaryKey(id)
}

// ---- Override States (server-side tracking) ----

object OverrideStates : Table("override_states") {
    val entityId = varchar("entity_id", 36)
    val familyId = varchar("family_id", 36).references(Families.id)
    val currentState = varchar("current_state", 20)
    val proposerUserId = varchar("proposer_user_id", 36)

    override val primaryKey = PrimaryKey(entityId)
}

// ---- Wrapped Keys ----

object WrappedKeys : Table("wrapped_keys") {
    val id = integer("id").autoIncrement()
    val targetDeviceId = varchar("target_device_id", 36).references(Devices.id)
    val wrappedDek = text("wrapped_dek")
    val keyEpoch = integer("key_epoch")
    val wrappedByUserId = varchar("wrapped_by_user_id", 36).references(Users.id)
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(targetDeviceId, keyEpoch)
    }
}

// ---- Recovery Blobs ----

object RecoveryBlobs : Table("recovery_blobs") {
    val userId = varchar("user_id", 36).references(Users.id)
    val encryptedRecoveryBlob = text("encrypted_recovery_blob")
    val createdAt = datetime("created_at")

    override val primaryKey = PrimaryKey(userId)
}
