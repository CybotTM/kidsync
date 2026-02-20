package dev.kidsync.server.util

object ValidationUtil {

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
    private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val SHA256_HEX_REGEX = Regex("^[0-9a-f]{64}$")

    fun isValidEmail(email: String): Boolean =
        email.length <= 254 && EMAIL_REGEX.matches(email)

    fun isStrongPassword(password: String): Boolean =
        password.length >= 12 && password.length <= 128

    fun isValidUUID(value: String): Boolean =
        UUID_REGEX.matches(value)

    fun isValidSha256Hex(value: String): Boolean =
        SHA256_HEX_REGEX.matches(value)

    val VALID_ENTITY_TYPES = setOf("CustodySchedule", "ScheduleOverride", "Expense", "ExpenseStatus")
    val VALID_OPERATIONS = setOf("CREATE", "UPDATE", "DELETE")
    val VALID_TRANSITION_STATES = setOf("APPROVED", "DECLINED", "CANCELLED", "SUPERSEDED", "EXPIRED")
}
