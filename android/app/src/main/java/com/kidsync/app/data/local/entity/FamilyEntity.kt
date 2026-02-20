package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "families")
data class FamilyEntity(
    @PrimaryKey
    val familyId: UUID,
    val name: String,
    val isSolo: Boolean = false,
    val createdAt: String
)
