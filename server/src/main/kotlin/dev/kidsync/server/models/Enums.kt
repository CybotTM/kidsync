package dev.kidsync.server.models

import kotlinx.serialization.Serializable

@Serializable
enum class FamilyRole {
    ADMIN, MEMBER
}

@Serializable
enum class EntityType {
    CustodySchedule, ScheduleOverride, Expense, ExpenseStatus
}

@Serializable
enum class OperationType {
    CREATE, UPDATE, DELETE
}

@Serializable
enum class OverrideStatus {
    PROPOSED, APPROVED, DECLINED, CANCELLED, SUPERSEDED, EXPIRED;

    fun isTerminal(): Boolean = this in setOf(DECLINED, CANCELLED, SUPERSEDED, EXPIRED)
}

@Serializable
enum class PushPlatform {
    FCM, APNS
}
