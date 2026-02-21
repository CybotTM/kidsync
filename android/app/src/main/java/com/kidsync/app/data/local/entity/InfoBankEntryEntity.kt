package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "info_bank_entries",
    indices = [Index("childId"), Index("category")]
)
data class InfoBankEntryEntity(
    @PrimaryKey
    val entryId: UUID,
    val childId: UUID,
    val category: String,
    val title: String? = null,
    val content: String? = null,
    val notes: String? = null,
    val clientTimestamp: String? = null,
    val updatedTimestamp: String? = null,
    val deleted: Boolean = false
)
