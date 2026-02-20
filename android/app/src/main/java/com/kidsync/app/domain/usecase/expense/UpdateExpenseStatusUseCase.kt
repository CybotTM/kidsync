package com.kidsync.app.domain.usecase.expense

import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.ExpenseStatusType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class UpdateExpenseStatusUseCase @Inject constructor(
    private val createOperationUseCase: CreateOperationUseCase
) {
    suspend operator fun invoke(
        familyId: UUID,
        deviceId: UUID,
        expenseId: UUID,
        status: ExpenseStatusType,
        responderId: UUID,
        note: String? = null
    ): Result<Unit> {
        val payloadMap = buildMap<String, Any?> {
            put("payloadType", "UpdateExpenseStatus")
            put("entityId", expenseId.toString())
            put("timestamp", Instant.now().toString())
            put("operationType", "UPDATE")
            put("expenseId", expenseId.toString())
            put("status", status.name)
            put("responderId", responderId.toString())
            note?.let { put("note", it) }
        }

        return createOperationUseCase(
            familyId = familyId,
            deviceId = deviceId,
            entityType = EntityType.ExpenseStatus,
            entityId = expenseId,
            operationType = OperationType.UPDATE,
            payloadMap = payloadMap,
            transitionTo = status.name
        ).map { }
    }
}
