package com.kidsync.app.domain.usecase.expense

import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.Expense
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class CreateExpenseUseCase @Inject constructor(
    private val createOperationUseCase: CreateOperationUseCase
) {
    suspend operator fun invoke(
        familyId: UUID,
        deviceId: UUID,
        expense: Expense
    ): Result<Unit> {
        val payloadMap = buildMap<String, Any?> {
            put("payloadType", "CreateExpense")
            put("entityId", expense.expenseId.toString())
            put("timestamp", Instant.now().toString())
            put("operationType", "CREATE")
            put("expenseId", expense.expenseId.toString())
            put("childId", expense.childId.toString())
            put("paidByUserId", expense.paidByUserId.toString())
            put("amountCents", expense.amountCents)
            put("currencyCode", expense.currencyCode)
            put("category", expense.category.name)
            put("description", expense.description)
            put("incurredAt", expense.incurredAt.toString())
            put("payerResponsibilityRatio", expense.payerResponsibilityRatio)
            expense.receiptBlobId?.let { put("receiptBlobId", it.toString()) }
            expense.receiptDecryptionKey?.let { put("receiptDecryptionKey", it) }
        }

        return createOperationUseCase(
            familyId = familyId,
            deviceId = deviceId,
            entityType = EntityType.Expense,
            entityId = expense.expenseId,
            operationType = OperationType.CREATE,
            payloadMap = payloadMap
        ).map { }
    }
}
