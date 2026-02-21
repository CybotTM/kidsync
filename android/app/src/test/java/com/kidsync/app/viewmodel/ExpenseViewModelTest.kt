package com.kidsync.app.viewmodel

import com.kidsync.app.data.local.entity.ExpenseEntity
import com.kidsync.app.data.local.entity.ExpenseStatusEntity
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.model.Expense
import com.kidsync.app.domain.model.ExpenseCategory
import com.kidsync.app.domain.model.ExpenseStatusType
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.repository.ExpenseRepository
import com.kidsync.app.domain.usecase.expense.CreateExpenseUseCase
import com.kidsync.app.domain.usecase.expense.GetExpenseSummaryUseCase
import com.kidsync.app.domain.usecase.expense.UpdateExpenseStatusUseCase
import com.kidsync.app.ui.viewmodel.ExpenseFilter
import com.kidsync.app.ui.viewmodel.ExpenseViewModel
import com.kidsync.app.ui.viewmodel.SummaryPeriod
import androidx.lifecycle.SavedStateHandle
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Tests for ExpenseViewModel covering:
 * - loadExpenses success with status mapping
 * - loadExpenses failure
 * - saveExpense validation (empty amount, no category, blank description, no child)
 * - saveExpense success resets form
 * - saveExpense not authenticated
 * - saveExpense no bucket
 * - acknowledgeExpense / disputeExpense
 * - Filter application
 * - Summary period change
 * - Solo mode detection (split ratio defaults to 1.0)
 * - Form input handlers
 * - clearError / resetSavedState
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val createExpenseUseCase = mockk<CreateExpenseUseCase>(relaxed = true)
    val updateExpenseStatusUseCase = mockk<UpdateExpenseStatusUseCase>(relaxed = true)
    val getExpenseSummaryUseCase = mockk<GetExpenseSummaryUseCase>(relaxed = true)
    val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)
    val savedStateHandle = SavedStateHandle()

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        coEvery { authRepository.getSession() } returns null
        coEvery { expenseRepository.getAllExpenses() } returns emptyList()
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): ExpenseViewModel {
        return ExpenseViewModel(
            createExpenseUseCase, updateExpenseStatusUseCase, getExpenseSummaryUseCase,
            expenseRepository, authRepository, bucketRepository, savedStateHandle
        )
    }

    // ── loadExpenses ──────────────────────────────────────────────────────────

    test("loadExpenses success maps status correctly") {
        runTest(testDispatcher) {
            val expenseIdStr = UUID.randomUUID().toString()
            val entity = ExpenseEntity(
                expenseId = expenseIdStr,
                childId = UUID.randomUUID().toString(),
                paidByDeviceId = "device-001",
                amountCents = 5000,
                currencyCode = "USD",
                category = "MEDICAL",
                description = "Doctor visit",
                incurredAt = "2026-03-01",
                payerResponsibilityRatio = 0.5
            )
            val statusEntity = ExpenseStatusEntity(
                id = UUID.randomUUID().toString(),
                expenseId = expenseIdStr,
                status = "ACKNOWLEDGED",
                responderId = "device-002",
                clientTimestamp = "2026-03-02T10:00:00Z"
            )

            coEvery { expenseRepository.getAllExpenses() } returns listOf(entity)
            coEvery { expenseRepository.getLatestStatusForExpense(any()) } returns statusEntity
            coEvery { expenseRepository.getStatusHistoryForExpense(any()) } returns listOf(statusEntity)

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.expenses.size shouldBe 1
            state.expenses[0].latestStatus shouldBe ExpenseStatusType.ACKNOWLEDGED
            state.currencyCode shouldBe "USD"
            state.isLoading shouldBe false
        }
    }

    test("loadExpenses failure sets error") {
        runTest(testDispatcher) {
            coEvery { expenseRepository.getAllExpenses() } throws RuntimeException("DB failure")

            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.error shouldNotBe null
            state.isLoading shouldBe false
        }
    }

    test("loadExpenses with unknown status defaults to LOGGED") {
        runTest(testDispatcher) {
            val expenseIdStr = UUID.randomUUID().toString()
            val entity = ExpenseEntity(
                expenseId = expenseIdStr,
                childId = UUID.randomUUID().toString(),
                paidByDeviceId = "device-001",
                amountCents = 1000,
                currencyCode = "EUR",
                category = "FOOD",
                description = "Lunch",
                incurredAt = "2026-03-01",
                payerResponsibilityRatio = 0.5
            )
            val invalidStatusEntity = ExpenseStatusEntity(
                id = UUID.randomUUID().toString(),
                expenseId = expenseIdStr,
                status = "UNKNOWN_STATUS",
                responderId = "device-002",
                clientTimestamp = "2026-03-02T10:00:00Z"
            )

            coEvery { expenseRepository.getAllExpenses() } returns listOf(entity)
            coEvery { expenseRepository.getLatestStatusForExpense(any()) } returns invalidStatusEntity
            coEvery { expenseRepository.getStatusHistoryForExpense(any()) } returns listOf(invalidStatusEntity)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.expenses[0].latestStatus shouldBe ExpenseStatusType.LOGGED
        }
    }

    // ── saveExpense validation ─────────────────────────────────────────────────

    test("saveExpense with invalid amount shows error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("") // empty
            vm.onCategoryChanged(ExpenseCategory.MEDICAL)
            vm.onDescriptionChanged("Test")
            vm.onChildSelected(UUID.randomUUID().toString())
            vm.saveExpense()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "amount"
        }
    }

    test("saveExpense with zero amount shows error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("0")
            vm.onCategoryChanged(ExpenseCategory.MEDICAL)
            vm.onDescriptionChanged("Test")
            vm.onChildSelected(UUID.randomUUID().toString())
            vm.saveExpense()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "amount"
        }
    }

    test("saveExpense without category shows error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("25.00")
            // No category set
            vm.onDescriptionChanged("Test")
            vm.onChildSelected(UUID.randomUUID().toString())
            vm.saveExpense()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "category"
        }
    }

    test("saveExpense with blank description shows error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("25.00")
            vm.onCategoryChanged(ExpenseCategory.MEDICAL)
            vm.onDescriptionChanged("   ") // blank
            vm.onChildSelected(UUID.randomUUID().toString())
            vm.saveExpense()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "description"
        }
    }

    test("saveExpense without child selected shows error") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("25.00")
            vm.onCategoryChanged(ExpenseCategory.MEDICAL)
            vm.onDescriptionChanged("Valid description")
            // No child selected
            vm.saveExpense()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "child"
        }
    }

    test("saveExpense not authenticated shows error") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("25.00")
            vm.onCategoryChanged(ExpenseCategory.MEDICAL)
            vm.onDescriptionChanged("Test expense")
            vm.onChildSelected(UUID.randomUUID().toString())
            vm.saveExpense()
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "authenticated"
        }
    }

    test("saveExpense no bucket available shows error") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns DeviceSession("d1", "tok", 3600)
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("25.00")
            vm.onCategoryChanged(ExpenseCategory.MEDICAL)
            vm.onDescriptionChanged("Test expense")
            vm.onChildSelected(UUID.randomUUID().toString())
            vm.saveExpense()
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "bucket"
        }
    }

    test("saveExpense success resets form fields and sets isSaved") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns DeviceSession("d1", "tok", 3600)
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-exp")
            coEvery { createExpenseUseCase(any(), any()) } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("25.00")
            vm.onCategoryChanged(ExpenseCategory.MEDICAL)
            vm.onDescriptionChanged("Test expense")
            vm.onChildSelected(UUID.randomUUID().toString())
            vm.saveExpense()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isSaved shouldBe true
            state.isSaving shouldBe false
            state.addAmountText shouldBe ""
            state.addCategory shouldBe null
            state.addDescription shouldBe ""
            state.addReceiptUri shouldBe null
        }
    }

    // ── Status updates ────────────────────────────────────────────────────────

    test("acknowledgeExpense calls updateExpenseStatusUseCase with ACKNOWLEDGED") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns DeviceSession("d1", "tok", 3600)
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-001")
            coEvery { updateExpenseStatusUseCase(any(), any(), ExpenseStatusType.ACKNOWLEDGED, any(), any()) } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            val expenseId = UUID.randomUUID().toString()
            vm.acknowledgeExpense(expenseId)
            advanceUntilIdle()

            coVerify {
                updateExpenseStatusUseCase(
                    bucketId = "bucket-001",
                    expenseId = expenseId,
                    status = ExpenseStatusType.ACKNOWLEDGED,
                    responderDeviceId = "d1",
                    note = null
                )
            }
        }
    }

    test("disputeExpense calls updateExpenseStatusUseCase with DISPUTED and note") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns DeviceSession("d1", "tok", 3600)
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-001")
            coEvery { updateExpenseStatusUseCase(any(), any(), ExpenseStatusType.DISPUTED, any(), any()) } returns Result.success(Unit)

            val vm = createViewModel()
            advanceUntilIdle()

            val expenseId = UUID.randomUUID().toString()
            vm.disputeExpense(expenseId, "Too expensive")
            advanceUntilIdle()

            coVerify {
                updateExpenseStatusUseCase(
                    bucketId = "bucket-001",
                    expenseId = expenseId,
                    status = ExpenseStatusType.DISPUTED,
                    responderDeviceId = "d1",
                    note = "Too expensive"
                )
            }
        }
    }

    // ── Filters ───────────────────────────────────────────────────────────────

    test("applyFilter updates filter state") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val filter = ExpenseFilter(category = ExpenseCategory.EDUCATION)
            vm.applyFilter(filter)
            advanceUntilIdle()

            vm.uiState.value.filter.category shouldBe ExpenseCategory.EDUCATION
        }
    }

    test("clearFilters resets filter to default") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.applyFilter(ExpenseFilter(category = ExpenseCategory.MEDICAL))
            advanceUntilIdle()

            vm.clearFilters()
            advanceUntilIdle()

            vm.uiState.value.filter shouldBe ExpenseFilter()
        }
    }

    // ── Solo mode ─────────────────────────────────────────────────────────────

    test("solo mode sets addSplitRatio to 1.0") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns DeviceSession("d-solo", "tok", 3600)
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-solo")
            coEvery { bucketRepository.getBucketDevices("bucket-solo") } returns Result.success(
                listOf(Device("d-solo", "sk", "ek", Instant.now()))
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.isSolo shouldBe true
            vm.uiState.value.addSplitRatio shouldBe 1.0f
        }
    }

    // ── Form input handlers ───────────────────────────────────────────────────

    test("onAmountChanged sanitizes input to digits and dots") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onAmountChanged("$25.50abc")
            vm.uiState.value.addAmountText shouldBe "25.50"
        }
    }

    test("onSplitRatioChanged clamps to 0..1 range") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onSplitRatioChanged(1.5f)
            vm.uiState.value.addSplitRatio shouldBe 1.0f

            vm.onSplitRatioChanged(-0.5f)
            vm.uiState.value.addSplitRatio shouldBe 0.0f

            vm.onSplitRatioChanged(0.75f)
            vm.uiState.value.addSplitRatio shouldBe 0.75f
        }
    }

    test("onCurrencyChanged updates currency code") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onCurrencyChanged("EUR")
            vm.uiState.value.addCurrencyCode shouldBe "EUR"
        }
    }

    test("resetSavedState clears isSaved flag") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.resetSavedState()
            vm.uiState.value.isSaved shouldBe false
        }
    }

    // ── Summary ───────────────────────────────────────────────────────────────

    test("onSummaryPeriodChanged updates period and triggers loadSummary") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.onSummaryPeriodChanged(SummaryPeriod.YEARLY)
            advanceUntilIdle()

            vm.uiState.value.summaryPeriod shouldBe SummaryPeriod.YEARLY
        }
    }

    test("navigateSummaryForward increments month") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val original = vm.uiState.value.summaryMonth
            vm.navigateSummaryForward()
            advanceUntilIdle()

            vm.uiState.value.summaryMonth shouldBe original.plusMonths(1)
        }
    }

    test("navigateSummaryBackward decrements month") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val original = vm.uiState.value.summaryMonth
            vm.navigateSummaryBackward()
            advanceUntilIdle()

            vm.uiState.value.summaryMonth shouldBe original.minusMonths(1)
        }
    }

    // ── clearError ────────────────────────────────────────────────────────────

    test("clearError resets error to null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
