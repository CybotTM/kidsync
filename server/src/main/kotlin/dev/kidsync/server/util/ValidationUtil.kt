package dev.kidsync.server.util

import dev.kidsync.server.services.ApiException
import io.ktor.server.routing.*

object ValidationUtil {

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")
    private val BASE64_REGEX = Regex("^[A-Za-z0-9+/=]+$")
    private val BASE64URL_REGEX = Regex("^[A-Za-z0-9_-]+={0,2}$")

    fun isValidUUID(value: String): Boolean =
        UUID_REGEX.matches(value)

    fun isValidSha256Hex(value: String): Boolean =
        SHA256_HEX_REGEX.matches(value)

    fun isValidBase64(value: String): Boolean =
        value.isNotBlank() && BASE64_REGEX.matches(value)

    fun isValidBase64Url(value: String): Boolean =
        value.isNotBlank() && BASE64URL_REGEX.matches(value)

    /**
     * SEC-S-09: Validate public key is valid base64 with correct decoded size.
     * Ed25519 keys are 32 bytes raw or 44 bytes in X.509 SubjectPublicKeyInfo encoding.
     * X25519 keys are 32 bytes raw or 44 bytes in X.509 SubjectPublicKeyInfo encoding.
     */
    fun isValidPublicKey(value: String): Boolean {
        if (value.isBlank() || value.length > 256) return false
        return try {
            val decoded = java.util.Base64.getDecoder().decode(value)
            decoded.size == 32 || decoded.size == 44
        } catch (_: Exception) {
            false
        }
    }

    fun isValidPlatform(value: String): Boolean =
        value == "FCM" || value == "APNS"

    fun isNonBlankWithMaxLength(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength

    /**
     * Extract a UUID path parameter, returning 400 INVALID_REQUEST if missing or malformed.
     */
    fun requireUuidPathParam(call: RoutingCall, paramName: String, label: String = paramName): String {
        val value = call.parameters[paramName]
            ?: throw ApiException(400, "INVALID_REQUEST", "Missing $label")
        if (!isValidUUID(value)) {
            throw ApiException(400, "INVALID_REQUEST", "Invalid UUID format for $label")
        }
        return value
    }
}
