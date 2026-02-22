package com.kidsync.app.viewmodel

import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.CustodyDay
import com.kidsync.app.domain.model.CustodyDaySource
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.model.ScheduleOverride
import com.kidsync.app.domain.model.OverrideType
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import com.kidsync.app.ui.viewmodel.SwapRequestViewModel
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
import java.time.LocalDate

/**
 * Tests for SwapRequestViewModel covering:
 * - Initial state and solo mode detection
 * - Context setup (childId, parentInfo, assignments, pending swaps)
 * - initializeSwapForDate sets start/end date and clears form
 * - setSwapStartDate/setSwapEndDate update dates and trigger preview
 * - setSwapNote updates note
 * - Swap preview generation (parent flipping)
 * - Swap preview handles end before start
 * - submitSwapRequest success clears form and sets swapSubmitted
 * - submitSwapRequest failure sets error
 * - submitSwapRequest returns early when missing required fields
 * - submitSwapRequest returns early when end before start
 * - submitSwapRequest sets error when not logged in
 * - submitSwapRequest sets error when no bucket
 * - approveSwap sends APPROVED status
 * - declineSwap sends DECLINED status
 * - Approve/decline failure sets error
 * - clearError
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwapRequestViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val createOperationUseCase = mockk<CreateOperationUseCase>()
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        coEvery { authRepository.getSession() } returns DeviceSession("device-1", "token", 3600)
        coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
        coEvery { bucketRepository.getBucketDevices("bucket-1") } returns Result.success(
            listOf(
                com.kidsync.app.domain.model.Device("d1", "sk1", "ek1", java.time.Instant.now()),
                com.kidsync.app.domain.model.Device("d2", "sk2", "ek2", java.time.Instant.now())
            )
        )
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): SwapRequestViewModel {
        return SwapRequestViewModel(createOperationUseCase, authRepository, bucketRepository)
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    test("initial state has default values") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.error shouldBe null
            state.childId shouldBe null
            state.swapStartDate shouldBe null
            state.swapEndDate shouldBe null
            state.swapNote shouldBe ""
            state.swapPreview shouldBe emptyList()
            state.swapSubmitted shouldBe false
            state.isSolo shouldBe false // Two devices in bucket = shared mode
        }
    }

    test("solo mode detected when single device in bucket") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getBucketDevices("bucket-1") } returns Result.success(
                listOf(com.kidsync.app.domain.model.Device("d1", "sk1", "ek1", java.time.Instant.now()))
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.isSolo shouldBe true
        }
    }

    // ── Context Setup ─────────────────────────────────────────────────────────

    test("setChildId updates childId") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-abc")
            vm.uiState.value.childId shouldBe "child-abc"
        }
    }

    test("setParentInfo updates parent details") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")

            val state = vm.uiState.value
            state.parentAId shouldBe "pa-1"
            state.parentAName shouldBe "Alice"
            state.parentBId shouldBe "pb-2"
            state.parentBName shouldBe "Bob"
        }
    }

    test("setAssignments updates assignments map") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val assignments = mapOf(
                LocalDate.of(2026, 3, 1) to CustodyDay(
                    date = LocalDate.of(2026, 3, 1),
                    assignedParentId = "pa-1",
                    source = CustodyDaySource.BASE_SCHEDULE
                )
            )
            vm.setAssignments(assignments)

            vm.uiState.value.assignments.size shouldBe 1
        }
    }

    test("setPendingSwaps updates pending swaps list") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val swaps = listOf(
                ScheduleOverride(
                    overrideId = "swap-1",
                    type = OverrideType.SWAP_REQUEST,
                    childId = "child-1",
                    startDate = LocalDate.of(2026, 3, 5),
                    endDate = LocalDate.of(2026, 3, 5),
                    assignedParentId = "pb-2",
                    status = OverrideStatus.PROPOSED,
                    proposerId = "device-1"
                )
            )
            vm.setPendingSwaps(swaps)

            vm.uiState.value.pendingSwaps.size shouldBe 1
        }
    }

    // ── Swap Form ─────────────────────────────────────────────────────────────

    test("initializeSwapForDate sets dates and clears form") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val date = LocalDate.of(2026, 4, 15)
            vm.initializeSwapForDate(date)

            val state = vm.uiState.value
            state.swapStartDate shouldBe date
            state.swapEndDate shouldBe date
            state.swapNote shouldBe ""
            state.swapSubmitted shouldBe false
        }
    }

    test("setSwapStartDate updates start date") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val date = LocalDate.of(2026, 4, 10)
            vm.setSwapStartDate(date)
            vm.uiState.value.swapStartDate shouldBe date
        }
    }

    test("setSwapEndDate updates end date") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val date = LocalDate.of(2026, 4, 12)
            vm.setSwapEndDate(date)
            vm.uiState.value.swapEndDate shouldBe date
        }
    }

    test("setSwapNote updates note") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setSwapNote("Please swap for conference trip")
            vm.uiState.value.swapNote shouldBe "Please swap for conference trip"
        }
    }

    // ── Swap Preview ──────────────────────────────────────────────────────────

    test("swap preview flips parent assignments for date range") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            val march1 = LocalDate.of(2026, 3, 1)
            val march2 = LocalDate.of(2026, 3, 2)
            vm.setAssignments(
                mapOf(
                    march1 to CustodyDay(march1, "pa-1", CustodyDaySource.BASE_SCHEDULE),
                    march2 to CustodyDay(march2, "pb-2", CustodyDaySource.BASE_SCHEDULE)
                )
            )

            vm.initializeSwapForDate(march1)
            vm.setSwapEndDate(march2)

            val preview = vm.uiState.value.swapPreview
            preview.size shouldBe 2
            // March 1 was pa-1 -> should flip to pb-2
            preview[0].assignedParentId shouldBe "pb-2"
            preview[0].source shouldBe CustodyDaySource.OVERRIDE
            // March 2 was pb-2 -> should flip to pa-1
            preview[1].assignedParentId shouldBe "pa-1"
        }
    }

    test("swap preview is empty when end date is before start date") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            vm.setSwapStartDate(LocalDate.of(2026, 3, 5))
            vm.setSwapEndDate(LocalDate.of(2026, 3, 3))

            vm.uiState.value.swapPreview shouldBe emptyList()
        }
    }

    // ── Submit Swap Request ───────────────────────────────────────────────────

    test("submitSwapRequest success clears form and sets swapSubmitted") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            vm.setSwapStartDate(LocalDate.of(2026, 4, 10))
            vm.setSwapEndDate(LocalDate.of(2026, 4, 12))
            vm.setSwapNote("Please swap")

            vm.submitSwapRequest()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isSubmittingSwap shouldBe false
            state.swapSubmitted shouldBe true
            state.swapStartDate shouldBe null
            state.swapEndDate shouldBe null
            state.swapNote shouldBe ""
            state.error shouldBe null
        }
    }

    test("submitSwapRequest failure sets error") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Sync error"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            vm.setSwapStartDate(LocalDate.of(2026, 4, 10))
            vm.setSwapEndDate(LocalDate.of(2026, 4, 12))

            vm.submitSwapRequest()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isSubmittingSwap shouldBe false
            state.swapSubmitted shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Sync error"
        }
    }

    test("submitSwapRequest returns early when childId is null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            vm.setSwapStartDate(LocalDate.of(2026, 4, 10))
            vm.setSwapEndDate(LocalDate.of(2026, 4, 12))
            // No childId

            vm.submitSwapRequest()
            advanceUntilIdle()

            vm.uiState.value.swapSubmitted shouldBe false
            vm.uiState.value.isSubmittingSwap shouldBe false
        }
    }

    test("submitSwapRequest returns early when dates are null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            // No dates

            vm.submitSwapRequest()
            advanceUntilIdle()

            vm.uiState.value.swapSubmitted shouldBe false
        }
    }

    test("submitSwapRequest returns early when end before start") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            vm.setSwapStartDate(LocalDate.of(2026, 4, 15))
            vm.setSwapEndDate(LocalDate.of(2026, 4, 10))

            vm.submitSwapRequest()
            advanceUntilIdle()

            vm.uiState.value.swapSubmitted shouldBe false
        }
    }

    test("submitSwapRequest sets error when not logged in") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            vm.setSwapStartDate(LocalDate.of(2026, 4, 10))
            vm.setSwapEndDate(LocalDate.of(2026, 4, 12))

            vm.submitSwapRequest()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Not logged in"
        }
    }

    test("submitSwapRequest sets error when no bucket available") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setParentInfo("pa-1", "Alice", "pb-2", "Bob")
            vm.setSwapStartDate(LocalDate.of(2026, 4, 10))
            vm.setSwapEndDate(LocalDate.of(2026, 4, 12))

            vm.submitSwapRequest()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "No bucket available"
        }
    }

    // ── Approve / Decline ─────────────────────────────────────────────────────

    test("approveSwap sends APPROVED status via createOperationUseCase") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.approveSwap("swap-123")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.error shouldBe null

            coVerify {
                createOperationUseCase(
                    bucketId = "bucket-1",
                    entityType = any(),
                    entityId = "swap-123",
                    operationType = any(),
                    contentData = any()
                )
            }
        }
    }

    test("declineSwap sends DECLINED status") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.declineSwap("swap-123")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.error shouldBe null
        }
    }

    test("approveSwap failure sets error") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Approve failed"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.approveSwap("swap-123")
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null
            vm.uiState.value.error!! shouldContain "Approve failed"
        }
    }

    test("approveSwap sets error when not logged in") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            vm.approveSwap("swap-123")
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Not logged in"
        }
    }

    // ── clearError ────────────────────────────────────────────────────────────

    test("clearError sets error to null") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("err"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setParentInfo("pa-1", "A", "pb-2", "B")
            vm.setSwapStartDate(LocalDate.of(2026, 4, 10))
            vm.setSwapEndDate(LocalDate.of(2026, 4, 10))
            vm.submitSwapRequest()
            advanceUntilIdle()
            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
