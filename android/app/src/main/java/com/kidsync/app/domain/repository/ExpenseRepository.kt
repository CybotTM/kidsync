package com.kidsync.app.domain.repository

import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import java.util.UUID

interface ExpenseRepository {
    suspend fun getAllExpenses(): List<ExpenseEntity>
    suspend fun getExpenseById(id: UUID): ExpenseEntity?
    suspend fun getLatestStatusForExpense(expenseId: UUID): ExpenseStatusEntity?
    suspend fun getStatusHistoryForExpense(expenseId: UUID): List<ExpenseStatusEntity>
}
