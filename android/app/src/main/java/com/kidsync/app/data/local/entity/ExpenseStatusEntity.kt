package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "expense_statuses",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["expenseId"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("expenseId")]
)
data class ExpenseStatusEntity(
    @PrimaryKey
    val id: UUID,
    val expenseId: UUID,
    val status: String,
    val responderId: UUID,
    val note: String? = null,
    val clientTimestamp: String
)
