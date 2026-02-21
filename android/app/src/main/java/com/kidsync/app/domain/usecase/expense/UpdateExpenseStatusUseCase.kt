package com.kidsync.app.domain.usecase.expense

import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.ExpenseStatusType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject

class UpdateExpenseStatusUseCase @Inject constructor(
    private val createOperationUseCase: CreateOperationUseCase
) {
    suspend operator fun invoke(
        bucketId: String,
        expenseId: String,
        status: ExpenseStatusType,
        responderDeviceId: String,
        note: String? = null
    ): Result<Unit> {
        val contentData = JsonObject(buildMap {
            put("expenseId", JsonPrimitive(expenseId))
            put("status", JsonPrimitive(status.name))
            put("responderDeviceId", JsonPrimitive(responderDeviceId))
            note?.let { put("note", JsonPrimitive(it)) }
        })

        return createOperationUseCase(
            bucketId = bucketId,
            entityType = EntityType.ExpenseStatus,
            entityId = expenseId,
            operationType = OperationType.UPDATE,
            contentData = contentData
        ).map { }
    }
}
