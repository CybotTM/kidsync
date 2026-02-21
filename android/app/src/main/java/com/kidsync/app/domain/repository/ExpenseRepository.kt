package com.kidsync.app.domain.repository

import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import java.util.UUID

// SEC3-A-26: The DAO methods accept UUID parameters while Room entity primary keys are
// also typed as UUID. Room's parameterized query binding handles the UUID-to-String
// conversion automatically via TypeConverters (see Converters.kt), so the implicit
// conversion is safe. The UUID type provides type safety at the Kotlin layer while Room
// serializes it consistently as a String in SQLite. No data loss or ambiguity occurs.
interface ExpenseRepository {
    suspend fun getAllExpenses(): List<ExpenseEntity>
    suspend fun getExpenseById(id: UUID): ExpenseEntity?
    suspend fun getLatestStatusForExpense(expenseId: UUID): ExpenseStatusEntity?
    suspend fun getStatusHistoryForExpense(expenseId: UUID): List<ExpenseStatusEntity>
}
