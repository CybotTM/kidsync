package com.kidsync.app.domain.usecase.expense

import com.kidsync.app.data.local.dao.ExpenseDao
import javax.inject.Inject

data class ExpenseSummary(
    val totalExpensesCents: Long,
    val parentAShareCents: Long,
    val parentBShareCents: Long,
    val parentAOwes: Long,
    val parentBOwes: Long,
    val expenseCount: Int
)

class GetExpenseSummaryUseCase @Inject constructor(
    private val expenseDao: ExpenseDao
) {
    suspend operator fun invoke(
        childId: String,
        parentAId: String,
        parentBId: String
    ): Result<ExpenseSummary> {
        return try {
            val expenses = expenseDao.getExpensesForChild(childId)

            var totalCents = 0L
            var parentAPaid = 0L
            var parentBPaid = 0L
            var parentAResponsible = 0L
            var parentBResponsible = 0L

            for (expense in expenses) {
                totalCents += expense.amountCents
                val ratio = expense.payerResponsibilityRatio

                if (expense.paidByDeviceId == parentAId) {
                    parentAPaid += expense.amountCents
                    // Parent A's responsibility portion
                    parentAResponsible += (expense.amountCents * ratio).toLong()
                    parentBResponsible += (expense.amountCents * (1 - ratio)).toLong()
                } else {
                    parentBPaid += expense.amountCents
                    parentBResponsible += (expense.amountCents * ratio).toLong()
                    parentAResponsible += (expense.amountCents * (1 - ratio)).toLong()
                }
            }

            // Calculate what each parent owes the other
            // Parent A owes = what A is responsible for - what A already paid
            val parentANetOwes = maxOf(0, parentAResponsible - parentAPaid)
            val parentBNetOwes = maxOf(0, parentBResponsible - parentBPaid)

            Result.success(
                ExpenseSummary(
                    totalExpensesCents = totalCents,
                    parentAShareCents = parentAResponsible,
                    parentBShareCents = parentBResponsible,
                    parentAOwes = parentANetOwes,
                    parentBOwes = parentBNetOwes,
                    expenseCount = expenses.size
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
