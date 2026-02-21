package com.kidsync.app.domain.usecase.expense

import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.Expense
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import javax.inject.Inject

class CreateExpenseUseCase @Inject constructor(
    private val createOperationUseCase: CreateOperationUseCase
) {
    suspend operator fun invoke(
        bucketId: String,
        expense: Expense
    ): Result<Unit> {
        val contentData = JsonObject(buildMap {
            put("expenseId", JsonPrimitive(expense.expenseId))
            put("childId", JsonPrimitive(expense.childId))
            put("paidByDeviceId", JsonPrimitive(expense.paidByDeviceId))
            put("amountCents", JsonPrimitive(expense.amountCents))
            put("currencyCode", JsonPrimitive(expense.currencyCode))
            put("category", JsonPrimitive(expense.category.name))
            put("description", JsonPrimitive(expense.description))
            put("incurredAt", JsonPrimitive(expense.incurredAt.toString()))
            put("payerResponsibilityRatio", JsonPrimitive(expense.payerResponsibilityRatio))
            expense.receiptBlobId?.let { put("receiptBlobId", JsonPrimitive(it)) }
            expense.receiptDecryptionKey?.let { put("receiptDecryptionKey", JsonPrimitive(it)) }
        })

        return createOperationUseCase(
            bucketId = bucketId,
            entityType = EntityType.Expense,
            entityId = expense.expenseId,
            operationType = OperationType.CREATE,
            contentData = contentData
        ).map { }
    }
}
