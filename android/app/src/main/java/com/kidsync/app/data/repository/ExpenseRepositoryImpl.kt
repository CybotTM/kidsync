package com.kidsync.app.data.repository

import com.kidsync.app.data.local.dao.ExpenseDao
import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import com.kidsync.app.domain.repository.ExpenseRepository
import java.util.UUID
import javax.inject.Inject

class ExpenseRepositoryImpl @Inject constructor(
    private val expenseDao: ExpenseDao
) : ExpenseRepository {
    override suspend fun getAllExpenses(): List<ExpenseEntity> = expenseDao.getAllExpenses()
    override suspend fun getExpenseById(id: UUID): ExpenseEntity? = expenseDao.getExpenseById(id)
    override suspend fun getLatestStatusForExpense(expenseId: UUID): ExpenseStatusEntity? = expenseDao.getLatestStatusForExpense(expenseId)
    override suspend fun getStatusHistoryForExpense(expenseId: UUID): List<ExpenseStatusEntity> = expenseDao.getStatusHistoryForExpense(expenseId)
}
