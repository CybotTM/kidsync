package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "expenses",
    indices = [Index("childId"), Index("paidByUserId")]
)
data class ExpenseEntity(
    @PrimaryKey
    val expenseId: UUID,
    val childId: UUID,
    val paidByUserId: UUID,
    val amountCents: Int,
    val currencyCode: String,
    val category: String,
    val description: String,
    val incurredAt: String,
    val payerResponsibilityRatio: Double,
    val receiptBlobId: UUID? = null,
    val receiptDecryptionKey: String? = null,
    val clientTimestamp: String? = null
)
