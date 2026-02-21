package dev.kidsync.server.util

object ValidationUtil {

    private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
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

    fun isValidPublicKey(value: String): Boolean =
        value.isNotBlank() && value.length <= 1024

    fun isValidPlatform(value: String): Boolean =
        value == "FCM" || value == "APNS"

    fun isNonBlankWithMaxLength(value: String, maxLength: Int): Boolean =
        value.isNotBlank() && value.length <= maxLength
}
