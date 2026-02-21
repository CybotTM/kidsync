package com.kidsync.app.domain.repository

import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity

interface ExpenseRepository {
    suspend fun getAllExpenses(): List<ExpenseEntity>
    suspend fun getExpenseById(id: String): ExpenseEntity?
    suspend fun getLatestStatusForExpense(expenseId: String): ExpenseStatusEntity?
    suspend fun getStatusHistoryForExpense(expenseId: String): List<ExpenseStatusEntity>
}
