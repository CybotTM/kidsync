package dev.kidsync.server.models

import kotlinx.serialization.Serializable

@Serializable
enum class Platform {
    FCM, APNS;

    companion object {
        fun isValid(value: String): Boolean =
            entries.any { it.name == value }
    }
}
