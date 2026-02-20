package com.kidsync.app.data.local.converter

import androidx.room.TypeConverter
import java.util.UUID

/**
 * Room type converters for UUID.
 *
 * Dates and times are stored as ISO 8601 strings (String columns)
 * to preserve canonical formatting for hash chain reproducibility.
 * UUID is converted to/from String for Room compatibility.
 */
class Converters {

    @TypeConverter
    fun fromUUID(uuid: UUID?): String? {
        return uuid?.toString()
    }

    @TypeConverter
    fun toUUID(value: String?): UUID? {
        return value?.let { UUID.fromString(it) }
    }
}
