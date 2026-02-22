package com.kidsync.app.domain.usecase

import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.Expense
import com.kidsync.app.domain.model.ExpenseCategory
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.expense.CreateExpenseUseCase
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.serialization.json.JsonObject
import java.time.LocalDate

class CreateExpenseUseCaseTest : FunSpec({

    val createOperationUseCase = mockk<CreateOperationUseCase>()

    fun createUseCase() = CreateExpenseUseCase(createOperationUseCase)

    beforeEach {
        clearAllMocks()
    }

    val bucketId = "bucket-expense-test"

    fun makeExpense(
        id: String = "exp-1",
        amountCents: Int = 5000,
        category: ExpenseCategory = ExpenseCategory.MEDICAL,
        receiptBlobId: String? = null
    ) = Expense(
        expenseId = id,
        childId = "child-1",
        paidByDeviceId = "device-1",
        amountCents = amountCents,
        currencyCode = "USD",
        category = category,
        description = "Doctor visit",
        incurredAt = LocalDate.of(2026, 1, 15),
        payerResponsibilityRatio = 0.5,
        receiptBlobId = receiptBlobId
    )

    // ── Basic invocation ────────────────────────────────────────────────────

    test("invoke delegates to CreateOperationUseCase with Expense entity type") {
        val entity = mockk<OpLogEntryEntity>()
        coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

        val useCase = createUseCase()
        val result = useCase(bucketId, makeExpense())

        result.isSuccess shouldBe true
        coVerify {
            createOperationUseCase(
                bucketId = bucketId,
                entityType = EntityType.Expense,
                entityId = "exp-1",
                operationType = OperationType.CREATE,
                contentData = any<JsonObject>()
            )
        }
    }

    test("invoke passes expense fields in contentData") {
        val entity = mockk<OpLogEntryEntity>()
        val capturedContent = slot<JsonObject>()
        coEvery {
            createOperationUseCase(any(), any(), any(), any(), capture(capturedContent))
        } returns Result.success(entity)

        val useCase = createUseCase()
        useCase(bucketId, makeExpense(amountCents = 7500))

        val content = capturedContent.captured
        content["amountCents"].toString() shouldBe "7500"
        content["currencyCode"].toString() shouldBe "\"USD\""
        content["category"].toString() shouldBe "\"MEDICAL\""
    }

    test("invoke includes receipt fields when present") {
        val entity = mockk<OpLogEntryEntity>()
        val capturedContent = slot<JsonObject>()
        coEvery {
            createOperationUseCase(any(), any(), any(), any(), capture(capturedContent))
        } returns Result.success(entity)

        val expense = makeExpense(receiptBlobId = "blob-receipt-1")
        val useCase = createUseCase()
        useCase(bucketId, expense)

        val content = capturedContent.captured
        content["receiptBlobId"].toString() shouldBe "\"blob-receipt-1\""
    }

    test("invoke omits receipt fields when null") {
        val entity = mockk<OpLogEntryEntity>()
        val capturedContent = slot<JsonObject>()
        coEvery {
            createOperationUseCase(any(), any(), any(), any(), capture(capturedContent))
        } returns Result.success(entity)

        val useCase = createUseCase()
        useCase(bucketId, makeExpense(receiptBlobId = null))

        val content = capturedContent.captured
        content.containsKey("receiptBlobId") shouldBe false
    }

    // ── Error propagation ───────────────────────────────────────────────────

    test("invoke propagates failure from CreateOperationUseCase") {
        coEvery {
            createOperationUseCase(any(), any(), any(), any(), any())
        } returns Result.failure(IllegalStateException("Device not registered"))

        val useCase = createUseCase()
        val result = useCase(bucketId, makeExpense())

        result.isFailure shouldBe true
    }

    test("invoke maps successful OpLogEntryEntity to Unit") {
        val entity = mockk<OpLogEntryEntity>()
        coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

        val useCase = createUseCase()
        val result = useCase(bucketId, makeExpense())

        result.isSuccess shouldBe true
        result.getOrNull() shouldBe Unit
    }
})
