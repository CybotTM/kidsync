package dev.kidsync.server.services

import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.time.ZoneOffset

class KeyService(private val config: AppConfig) {

    suspend fun uploadWrappedKey(userId: String, request: UploadWrappedKeyRequest) {
        dbQuery {
            // Verify target device exists
            val targetDevice = Devices.selectAll().where { Devices.id eq request.targetDeviceId }
                .firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Target device not found")

            // Verify both users are in the same family
            val callerFamilies = FamilyMembers.selectAll()
                .where { FamilyMembers.userId eq userId }
                .map { it[FamilyMembers.familyId] }
                .toSet()

            val targetUserFamilies = FamilyMembers.selectAll()
                .where { FamilyMembers.userId eq targetDevice[Devices.userId] }
                .map { it[FamilyMembers.familyId] }
                .toSet()

            if (callerFamilies.intersect(targetUserFamilies).isEmpty()) {
                throw ApiException(403, "FORBIDDEN", "Not in the same family as the target device")
            }

            // Upsert: replace existing wrapped key for this device + epoch
            WrappedKeys.deleteWhere {
                (targetDeviceId eq request.targetDeviceId) and (keyEpoch eq request.keyEpoch)
            }

            WrappedKeys.insert {
                it[targetDeviceId] = request.targetDeviceId
                it[wrappedDek] = request.wrappedDek
                it[keyEpoch] = request.keyEpoch
                it[wrappedByUserId] = userId
                it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    suspend fun getWrappedKey(userId: String, deviceId: String, keyEpoch: Int?): WrappedKeyResponse {
        val result = dbQuery {
            // Only the device owner can get their wrapped key
            val device = Devices.selectAll().where { Devices.id eq deviceId }.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "Device not found")

            if (device[Devices.userId] != userId) {
                throw ApiException(403, "FORBIDDEN", "Can only retrieve your own device's wrapped key")
            }

            val query = if (keyEpoch != null) {
                WrappedKeys.selectAll().where {
                    (WrappedKeys.targetDeviceId eq deviceId) and (WrappedKeys.keyEpoch eq keyEpoch)
                }
            } else {
                WrappedKeys.selectAll()
                    .where { WrappedKeys.targetDeviceId eq deviceId }
                    .orderBy(WrappedKeys.keyEpoch, SortOrder.DESC)
                    .limit(1)
            }

            query.firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "No wrapped key found for this device")
        }

        return WrappedKeyResponse(
            wrappedDek = result[WrappedKeys.wrappedDek],
            keyEpoch = result[WrappedKeys.keyEpoch],
            wrappedBy = result[WrappedKeys.wrappedByUserId],
        )
    }

    suspend fun uploadRecoveryBlob(userId: String, request: UploadRecoveryBlobRequest) {
        dbQuery {
            // Upsert: replace existing recovery blob
            RecoveryBlobs.deleteWhere { RecoveryBlobs.userId eq userId }

            RecoveryBlobs.insert {
                it[RecoveryBlobs.userId] = userId
                it[encryptedRecoveryBlob] = request.encryptedRecoveryBlob
                it[createdAt] = LocalDateTime.now(ZoneOffset.UTC)
            }
        }
    }

    suspend fun getRecoveryBlob(userId: String): RecoveryBlobResponse {
        val result = dbQuery {
            RecoveryBlobs.selectAll().where { RecoveryBlobs.userId eq userId }
                .firstOrNull()
                ?: throw ApiException(404, "NOT_FOUND", "No recovery blob found")
        }

        return RecoveryBlobResponse(
            encryptedRecoveryBlob = result[RecoveryBlobs.encryptedRecoveryBlob],
        )
    }
}
