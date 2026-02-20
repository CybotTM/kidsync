package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "family_members",
    foreignKeys = [
        ForeignKey(
            entity = FamilyEntity::class,
            parentColumns = ["familyId"],
            childColumns = ["familyId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("familyId")]
)
data class FamilyMemberEntity(
    @PrimaryKey
    val id: UUID,
    val familyId: UUID,
    val userId: UUID,
    val displayName: String,
    val role: String,
    val joinedAt: String
)
