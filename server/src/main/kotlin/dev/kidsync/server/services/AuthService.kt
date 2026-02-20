package dev.kidsync.server.services

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.kidsync.server.AppConfig
import dev.kidsync.server.db.*
import dev.kidsync.server.db.DatabaseFactory.dbQuery
import dev.kidsync.server.models.*
import dev.kidsync.server.util.HashUtil
import dev.kidsync.server.util.JwtUtil
import dev.kidsync.server.util.ValidationUtil
import dev.turingcomplete.kotlinonetimepassword.HmacAlgorithm
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordConfig
import dev.turingcomplete.kotlinonetimepassword.TimeBasedOneTimePasswordGenerator
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.*
import java.util.concurrent.TimeUnit

class AuthService(
    private val config: AppConfig,
    private val jwtUtil: JwtUtil,
) {

    suspend fun register(request: RegisterRequest): Result<RegisterResponse> {
        if (!ValidationUtil.isValidEmail(request.email)) {
            return Result.failure(ApiException(400, "INVALID_REQUEST", "Invalid email format"))
        }
        if (!ValidationUtil.isStrongPassword(request.password)) {
            return Result.failure(ApiException(400, "INVALID_REQUEST", "Password must be between 12 and 128 characters"))
        }

        return dbQuery {
            // Check for existing email
            val existing = Users.selectAll().where { Users.email eq request.email.lowercase() }.firstOrNull()
            if (existing != null) {
                return@dbQuery Result.failure(ApiException(409, "CONFLICT", "Email already registered"))
            }

            val userId = UUID.randomUUID().toString()
            val deviceId = UUID.randomUUID().toString()
            val now = LocalDateTime.now(ZoneOffset.UTC)
            val passwordHash = BCrypt.withDefaults().hashToString(12, request.password.toCharArray())

            Users.insert {
                it[id] = userId
                it[email] = request.email.lowercase()
                it[Users.passwordHash] = passwordHash
                it[displayName] = request.email.substringBefore("@")
                it[createdAt] = now
            }

            Devices.insert {
                it[id] = deviceId
                it[Devices.userId] = userId
                it[publicKey] = ""
                it[deviceName] = "Initial Device"
                it[createdAt] = now
            }

            val familyIds = getFamilyIdsForUser(userId)
            val accessToken = jwtUtil.generateAccessToken(userId, deviceId, familyIds)
            val refreshToken = jwtUtil.generateRefreshToken()
            storeRefreshToken(userId, refreshToken, now)

            Result.success(
                RegisterResponse(
                    userId = userId,
                    deviceId = deviceId,
                    token = accessToken,
                    refreshToken = refreshToken,
                )
            )
        }
    }

    suspend fun login(request: LoginRequest): Result<LoginResponse> {
        return dbQuery {
            val user = Users.selectAll().where { Users.email eq request.email.lowercase() }.firstOrNull()
                ?: return@dbQuery Result.failure(ApiException(401, "UNAUTHORIZED", "Invalid email or password"))

            val passwordHash = user[Users.passwordHash]
            val result = BCrypt.verifyer().verify(request.password.toCharArray(), passwordHash)
            if (!result.verified) {
                return@dbQuery Result.failure(ApiException(401, "UNAUTHORIZED", "Invalid email or password"))
            }

            val userId = user[Users.id]

            // Check TOTP
            if (user[Users.totpEnabled]) {
                val totpSecret = user[Users.totpSecret]
                if (totpSecret == null) {
                    return@dbQuery Result.failure(ApiException(500, "INTERNAL_ERROR", "TOTP is enabled but secret is missing"))
                }
                if (request.totpCode == null) {
                    return@dbQuery Result.failure(ApiException(400, "INVALID_REQUEST", "TOTP code is required"))
                }
                if (!verifyTotpCode(totpSecret, request.totpCode)) {
                    return@dbQuery Result.failure(ApiException(401, "UNAUTHORIZED", "Invalid TOTP code"))
                }
            }

            // Get a device for this user (first active one)
            val device = Devices.selectAll().where {
                (Devices.userId eq userId) and Devices.revokedAt.isNull()
            }.firstOrNull()
                ?: return@dbQuery Result.failure(ApiException(401, "UNAUTHORIZED", "No active device found"))

            val deviceId = device[Devices.id]
            val familyIds = getFamilyIdsForUser(userId)
            val accessToken = jwtUtil.generateAccessToken(userId, deviceId, familyIds)
            val refreshToken = jwtUtil.generateRefreshToken()
            val now = LocalDateTime.now(ZoneOffset.UTC)
            storeRefreshToken(userId, refreshToken, now)

            Result.success(
                LoginResponse(
                    userId = userId,
                    token = accessToken,
                    refreshToken = refreshToken,
                )
            )
        }
    }

    suspend fun totpSetup(userId: String, email: String): Result<TotpSetupResponse> {
        return dbQuery {
            val secret = generateTotpSecret()
            val base32Secret = Base32.encode(secret)

            // Store the secret (not yet enabled)
            Users.update({ Users.id eq userId }) {
                it[totpSecret] = base32Secret
            }

            val qrCodeUri = "otpauth://totp/KidSync:${email}?secret=${base32Secret}&issuer=KidSync&algorithm=SHA1&digits=6&period=30"

            Result.success(
                TotpSetupResponse(
                    secret = base32Secret,
                    qrCodeUri = qrCodeUri,
                )
            )
        }
    }

    suspend fun totpVerify(userId: String, code: String): Result<TotpVerifyResponse> {
        return dbQuery {
            val user = Users.selectAll().where { Users.id eq userId }.firstOrNull()
                ?: return@dbQuery Result.failure(ApiException(404, "NOT_FOUND", "User not found"))

            val secret = user[Users.totpSecret]
                ?: return@dbQuery Result.failure(ApiException(400, "INVALID_REQUEST", "TOTP not set up yet"))

            if (!verifyTotpCode(secret, code)) {
                return@dbQuery Result.failure(ApiException(400, "INVALID_REQUEST", "Invalid TOTP code"))
            }

            Users.update({ Users.id eq userId }) {
                it[totpEnabled] = true
            }

            Result.success(TotpVerifyResponse(verified = true))
        }
    }

    suspend fun refresh(request: RefreshRequest): Result<RefreshResponse> {
        return dbQuery {
            val tokenHash = HashUtil.sha256HexString(request.refreshToken)
            val now = LocalDateTime.now(ZoneOffset.UTC)

            val storedToken = RefreshTokens.selectAll().where {
                (RefreshTokens.tokenHash eq tokenHash) and
                    RefreshTokens.revokedAt.isNull() and
                    (RefreshTokens.expiresAt greater now)
            }.firstOrNull()
                ?: return@dbQuery Result.failure(ApiException(401, "UNAUTHORIZED", "Invalid or expired refresh token"))

            val userId = storedToken[RefreshTokens.userId]

            // Revoke old token
            RefreshTokens.update({ RefreshTokens.tokenHash eq tokenHash }) {
                it[revokedAt] = now
            }

            // Get active device
            val device = Devices.selectAll().where {
                (Devices.userId eq userId) and Devices.revokedAt.isNull()
            }.firstOrNull()
                ?: return@dbQuery Result.failure(ApiException(401, "UNAUTHORIZED", "No active device"))

            val deviceId = device[Devices.id]
            val familyIds = getFamilyIdsForUser(userId)
            val newAccessToken = jwtUtil.generateAccessToken(userId, deviceId, familyIds)
            val newRefreshToken = jwtUtil.generateRefreshToken()
            storeRefreshToken(userId, newRefreshToken, now)

            Result.success(
                RefreshResponse(
                    token = newAccessToken,
                    refreshToken = newRefreshToken,
                )
            )
        }
    }

    private fun getFamilyIdsForUser(userId: String): List<String> {
        return FamilyMembers.selectAll().where { FamilyMembers.userId eq userId }
            .map { it[FamilyMembers.familyId] }
    }

    private fun storeRefreshToken(userId: String, refreshToken: String, now: LocalDateTime) {
        val tokenHash = HashUtil.sha256HexString(refreshToken)
        RefreshTokens.insert {
            it[id] = UUID.randomUUID().toString()
            it[RefreshTokens.userId] = userId
            it[RefreshTokens.tokenHash] = tokenHash
            it[expiresAt] = now.plusDays(config.jwtRefreshExpirationDays)
            it[createdAt] = now
        }
    }

    private fun verifyTotpCode(base32Secret: String, code: String): Boolean {
        return try {
            val secretBytes = Base32.decode(base32Secret)
            val totpConfig = TimeBasedOneTimePasswordConfig(
                timeStep = 30,
                timeStepUnit = TimeUnit.SECONDS,
                codeDigits = 6,
                hmacAlgorithm = HmacAlgorithm.SHA1,
            )
            val generator = TimeBasedOneTimePasswordGenerator(secretBytes, totpConfig)
            val currentCode = generator.generate()
            currentCode == code
        } catch (e: Exception) {
            false
        }
    }

    private fun generateTotpSecret(): ByteArray {
        val random = java.security.SecureRandom()
        val bytes = ByteArray(20)
        random.nextBytes(bytes)
        return bytes
    }
}

/**
 * Simple Base32 encoder/decoder for TOTP secrets.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                sb.append(ALPHABET[index])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            sb.append(ALPHABET[index])
        }
        return sb.toString()
    }

    fun decode(base32: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        var buffer = 0
        var bitsLeft = 0
        for (c in base32.uppercase()) {
            if (c == '=') continue
            val value = ALPHABET.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bytes.add(((buffer shr (bitsLeft - 8)) and 0xFF).toByte())
                bitsLeft -= 8
            }
        }
        return bytes.toByteArray()
    }
}

class ApiException(
    val statusCode: Int,
    val errorCode: String,
    override val message: String,
) : Exception(message)
