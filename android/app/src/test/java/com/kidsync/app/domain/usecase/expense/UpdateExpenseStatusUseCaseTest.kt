package com.kidsync.app.domain.usecase.expense

import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.ExpenseStatusType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Tests for UpdateExpenseStatusUseCase covering:
 * - ACKNOWLEDGED status transition creates correct operation
 * - DISPUTED status transition creates correct operation
 * - LOGGED status transition creates correct operation
 * - Optional note is included when provided
 * - Optional note is excluded when null
 * - Failure from CreateOperationUseCase is propagated
 * - ContentData contains correct fields (expenseId, status, responderDeviceId)
 * - EntityType is ExpenseStatus
 * - OperationType is UPDATE
 */
class UpdateExpenseStatusUseCaseTest : FunSpec({

    val createOperationUseCase = mockk<CreateOperationUseCase>()

    beforeEach {
        clearAllMocks()
    }

    fun createUseCase() = UpdateExpenseStatusUseCase(createOperationUseCase)

    // ── Success Cases ─────────────────────────────────────────────────────────

    test("ACKNOWLEDGED status creates correct operation") {
        runTest {
            val entity = mockk<OpLogEntryEntity>()
            val capturedContentData = slot<JsonObject>()

            coEvery {
                createOperationUseCase(
                    bucketId = "bucket-1",
                    entityType = EntityType.ExpenseStatus,
                    entityId = "expense-123",
                    operationType = OperationType.UPDATE,
                    contentData = capture(capturedContentData)
                )
            } returns Result.success(entity)

            val useCase = createUseCase()
            val result = useCase(
                bucketId = "bucket-1",
                expenseId = "expense-123",
                status = ExpenseStatusType.ACKNOWLEDGED,
                responderDeviceId = "device-abc"
            )

            result.isSuccess shouldBe true

            val data = capturedContentData.captured
            data["expenseId"]?.jsonPrimitive?.content shouldBe "expense-123"
            data["status"]?.jsonPrimitive?.content shouldBe "ACKNOWLEDGED"
            data["responderDeviceId"]?.jsonPrimitive?.content shouldBe "device-abc"
            data.containsKey("note") shouldBe false
        }
    }

    test("DISPUTED status creates correct operation") {
        runTest {
            val entity = mockk<OpLogEntryEntity>()
            coEvery {
                createOperationUseCase(any(), any(), any(), any(), any())
            } returns Result.success(entity)

            val useCase = createUseCase()
            val result = useCase(
                bucketId = "bucket-1",
                expenseId = "expense-456",
                status = ExpenseStatusType.DISPUTED,
                responderDeviceId = "device-xyz",
                note = "Amount seems incorrect"
            )

            result.isSuccess shouldBe true

            coVerify {
                createOperationUseCase(
                    bucketId = "bucket-1",
                    entityType = EntityType.ExpenseStatus,
                    entityId = "expense-456",
                    operationType = OperationType.UPDATE,
                    contentData = any()
                )
            }
        }
    }

    test("LOGGED status creates correct operation") {
        runTest {
            val entity = mockk<OpLogEntryEntity>()
            coEvery {
                createOperationUseCase(any(), any(), any(), any(), any())
            } returns Result.success(entity)

            val useCase = createUseCase()
            val result = useCase(
                bucketId = "bucket-1",
                expenseId = "expense-789",
                status = ExpenseStatusType.LOGGED,
                responderDeviceId = "device-001"
            )

            result.isSuccess shouldBe true
        }
    }

    // ── Note Handling ─────────────────────────────────────────────────────────

    test("note is included in contentData when provided") {
        runTest {
            val entity = mockk<OpLogEntryEntity>()
            val capturedContentData = slot<JsonObject>()

            coEvery {
                createOperationUseCase(any(), any(), any(), any(), capture(capturedContentData))
            } returns Result.success(entity)

            val useCase = createUseCase()
            useCase(
                bucketId = "bucket-1",
                expenseId = "expense-123",
                status = ExpenseStatusType.DISPUTED,
                responderDeviceId = "device-abc",
                note = "This is wrong"
            )

            val data = capturedContentData.captured
            data["note"]?.jsonPrimitive?.content shouldBe "This is wrong"
        }
    }

    test("note is excluded from contentData when null") {
        runTest {
            val entity = mockk<OpLogEntryEntity>()
            val capturedContentData = slot<JsonObject>()

            coEvery {
                createOperationUseCase(any(), any(), any(), any(), capture(capturedContentData))
            } returns Result.success(entity)

            val useCase = createUseCase()
            useCase(
                bucketId = "bucket-1",
                expenseId = "expense-123",
                status = ExpenseStatusType.ACKNOWLEDGED,
                responderDeviceId = "device-abc",
                note = null
            )

            val data = capturedContentData.captured
            data.containsKey("note") shouldBe false
        }
    }

    // ── Failure Propagation ───────────────────────────────────────────────────

    test("failure from CreateOperationUseCase is propagated") {
        runTest {
            coEvery {
                createOperationUseCase(any(), any(), any(), any(), any())
            } returns Result.failure(RuntimeException("Device not registered"))

            val useCase = createUseCase()
            val result = useCase(
                bucketId = "bucket-1",
                expenseId = "expense-123",
                status = ExpenseStatusType.ACKNOWLEDGED,
                responderDeviceId = "device-abc"
            )

            result.isFailure shouldBe true
            result.exceptionOrNull() shouldNotBe null
            result.exceptionOrNull()?.message shouldBe "Device not registered"
        }
    }

    // ── Entity/Operation Type ─────────────────────────────────────────────────

    test("uses EntityType.ExpenseStatus and OperationType.UPDATE") {
        runTest {
            val entity = mockk<OpLogEntryEntity>()
            coEvery {
                createOperationUseCase(any(), any(), any(), any(), any())
            } returns Result.success(entity)

            val useCase = createUseCase()
            useCase(
                bucketId = "bucket-1",
                expenseId = "expense-123",
                status = ExpenseStatusType.ACKNOWLEDGED,
                responderDeviceId = "device-abc"
            )

            coVerify {
                createOperationUseCase(
                    bucketId = "bucket-1",
                    entityType = EntityType.ExpenseStatus,
                    entityId = "expense-123",
                    operationType = OperationType.UPDATE,
                    contentData = any()
                )
            }
        }
    }

    test("uses expenseId as entityId in createOperationUseCase call") {
        runTest {
            val entity = mockk<OpLogEntryEntity>()
            coEvery {
                createOperationUseCase(any(), any(), any(), any(), any())
            } returns Result.success(entity)

            val useCase = createUseCase()
            useCase(
                bucketId = "bucket-42",
                expenseId = "expense-specific-id",
                status = ExpenseStatusType.LOGGED,
                responderDeviceId = "device-999"
            )

            coVerify {
                createOperationUseCase(
                    bucketId = "bucket-42",
                    entityType = EntityType.ExpenseStatus,
                    entityId = "expense-specific-id",
                    operationType = OperationType.UPDATE,
                    contentData = any()
                )
            }
        }
    }
})
