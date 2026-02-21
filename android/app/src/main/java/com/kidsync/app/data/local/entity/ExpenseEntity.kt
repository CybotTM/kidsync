package com.kidsync.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "expenses",
    indices = [Index("childId"), Index("paidByDeviceId")]
)
data class ExpenseEntity(
    @PrimaryKey
    val expenseId: String,
    val childId: String,
    val paidByDeviceId: String,
    val amountCents: Int,
    val currencyCode: String,
    val category: String,
    val description: String,
    val incurredAt: String,
    val payerResponsibilityRatio: Double,
    val receiptBlobId: String? = null,
    val receiptDecryptionKey: String? = null,
    val clientTimestamp: String? = null
)
