package com.kidsync.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import java.util.UUID

@Dao
interface ExpenseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpense(expense: ExpenseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenseStatus(status: ExpenseStatusEntity)

    @Query("SELECT * FROM expenses WHERE childId = :childId ORDER BY incurredAt DESC")
    suspend fun getExpensesForChild(childId: UUID): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE expenseId = :expenseId")
    suspend fun getExpenseById(expenseId: UUID): ExpenseEntity?

    @Query(
        """
        SELECT * FROM expense_statuses
        WHERE expenseId = :expenseId
        ORDER BY clientTimestamp DESC
        LIMIT 1
        """
    )
    suspend fun getLatestStatusForExpense(expenseId: UUID): ExpenseStatusEntity?

    @Query("SELECT * FROM expense_statuses WHERE expenseId = :expenseId ORDER BY clientTimestamp DESC")
    suspend fun getStatusHistoryForExpense(expenseId: UUID): List<ExpenseStatusEntity>

    @Query("SELECT * FROM expenses")
    suspend fun getAllExpenses(): List<ExpenseEntity>

    @Query("DELETE FROM expenses")
    suspend fun deleteAllExpenses()

    @Query("DELETE FROM expense_statuses")
    suspend fun deleteAllStatuses()
}
