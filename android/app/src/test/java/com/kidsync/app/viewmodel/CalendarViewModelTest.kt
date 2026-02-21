package com.kidsync.app.viewmodel

import com.kidsync.app.domain.model.CustodyDay
import com.kidsync.app.domain.model.CustodyDaySource
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.model.OverrideType
import com.kidsync.app.domain.model.ScheduleOverride
import com.kidsync.app.domain.model.CalendarEvent
import com.kidsync.app.domain.usecase.custody.GetCustodyCalendarUseCase
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.model.Device
import com.kidsync.app.ui.viewmodel.CalendarViewModel
import com.kidsync.app.ui.viewmodel.ChildInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

/**
 * Tests for CalendarViewModel covering:
 * - Initial state defaults
 * - navigateMonth forward and backward
 * - loadMonth success with assignments
 * - loadMonth failure
 * - loadMonth without childId returns early
 * - selectDate updates selectedDate
 * - selectChild triggers loadMonth
 * - setChildren sets children and auto-selects first
 * - setParentInfo
 * - Solo mode detection
 * - clearError
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val getCustodyCalendarUseCase = mockk<GetCustodyCalendarUseCase>()
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        // Default: no solo mode
        coEvery { authRepository.getSession() } returns null
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): CalendarViewModel {
        return CalendarViewModel(getCustodyCalendarUseCase, authRepository, bucketRepository)
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    test("initial state has current month and no selection") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.currentMonth shouldBe YearMonth.now()
            state.selectedDate shouldBe null
            state.childId shouldBe null
            state.isLoading shouldBe false
            state.hasSchedule shouldBe false
        }
    }

    // ── navigateMonth ─────────────────────────────────────────────────────────

    test("navigateMonth forward increments month") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val originalMonth = vm.uiState.value.currentMonth
            vm.navigateMonth(forward = true)
            advanceUntilIdle()

            vm.uiState.value.currentMonth shouldBe originalMonth.plusMonths(1)
        }
    }

    test("navigateMonth backward decrements month") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val originalMonth = vm.uiState.value.currentMonth
            vm.navigateMonth(forward = false)
            advanceUntilIdle()

            vm.uiState.value.currentMonth shouldBe originalMonth.minusMonths(1)
        }
    }

    // ── loadMonth ─────────────────────────────────────────────────────────────

    test("loadMonth without childId returns early without loading") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.loadMonth()
            advanceUntilIdle()

            // Should not have called the use case
            coVerify(exactly = 0) { getCustodyCalendarUseCase(any(), any(), any()) }
            vm.uiState.value.isLoading shouldBe false
        }
    }

    test("loadMonth success populates assignments") {
        runTest(testDispatcher) {
            val today = LocalDate.now()
            val days = listOf(
                CustodyDay(today, "parent-a", CustodyDaySource.BASE_SCHEDULE, null),
                CustodyDay(today.plusDays(1), "parent-b", CustodyDaySource.BASE_SCHEDULE, null)
            )
            coEvery { getCustodyCalendarUseCase(any(), any(), any()) } returns Result.success(days)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectChild("child-001")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.assignments.size shouldBe 2
            state.assignments[today]?.assignedParentId shouldBe "parent-a"
            state.hasSchedule shouldBe true
            state.isLoading shouldBe false
        }
    }

    test("loadMonth failure sets error and hasSchedule=false") {
        runTest(testDispatcher) {
            coEvery { getCustodyCalendarUseCase(any(), any(), any()) } returns Result.failure(
                IllegalStateException("No active schedule")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectChild("child-002")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.error shouldNotBe null
            state.hasSchedule shouldBe false
            state.isLoading shouldBe false
        }
    }

    // ── selectDate ────────────────────────────────────────────────────────────

    test("selectDate updates selectedDate") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val date = LocalDate.of(2026, 3, 15)
            vm.selectDate(date)

            vm.uiState.value.selectedDate shouldBe date
        }
    }

    test("selectDate filters events for the selected day") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Currently monthEvents is empty, so events should also be empty
            val date = LocalDate.of(2026, 3, 15)
            vm.selectDate(date)

            vm.uiState.value.events shouldBe emptyList()
        }
    }

    // ── selectChild ───────────────────────────────────────────────────────────

    test("selectChild updates childId and triggers loadMonth") {
        runTest(testDispatcher) {
            coEvery { getCustodyCalendarUseCase(any(), any(), any()) } returns Result.success(emptyList())

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectChild("child-abc")
            advanceUntilIdle()

            vm.uiState.value.childId shouldBe "child-abc"
            coVerify(atLeast = 1) { getCustodyCalendarUseCase("child-abc", any(), any()) }
        }
    }

    // ── setChildren ───────────────────────────────────────────────────────────

    test("setChildren populates children and auto-selects first") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val children = listOf(
                ChildInfo("c1", "Alice"),
                ChildInfo("c2", "Bob")
            )
            vm.setChildren(children)

            val state = vm.uiState.value
            state.children.size shouldBe 2
            state.childId shouldBe "c1" // auto-selected first
        }
    }

    test("setChildren preserves existing childId if set") {
        runTest(testDispatcher) {
            coEvery { getCustodyCalendarUseCase(any(), any(), any()) } returns Result.success(emptyList())

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectChild("c2")
            advanceUntilIdle()

            val children = listOf(
                ChildInfo("c1", "Alice"),
                ChildInfo("c2", "Bob")
            )
            vm.setChildren(children)

            vm.uiState.value.childId shouldBe "c2" // preserved
        }
    }

    // ── setParentInfo ─────────────────────────────────────────────────────────

    test("setParentInfo updates parent names and IDs") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setParentInfo("pa-001", "Mom", "pb-002", "Dad")

            val state = vm.uiState.value
            state.parentAId shouldBe "pa-001"
            state.parentAName shouldBe "Mom"
            state.parentBId shouldBe "pb-002"
            state.parentBName shouldBe "Dad"
        }
    }

    // ── Solo mode ─────────────────────────────────────────────────────────────

    test("solo mode detected when only one device in bucket") {
        runTest(testDispatcher) {
            val session = DeviceSession("device-solo", "tok", 3600)
            coEvery { authRepository.getSession() } returns session
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-solo")
            coEvery { bucketRepository.getBucketDevices("bucket-solo") } returns Result.success(
                listOf(Device("device-solo", "sk", "ek", Instant.now()))
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.isSolo shouldBe true
        }
    }

    test("shared mode when multiple devices in bucket") {
        runTest(testDispatcher) {
            val session = DeviceSession("device-a", "tok", 3600)
            coEvery { authRepository.getSession() } returns session
            coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-shared")
            coEvery { bucketRepository.getBucketDevices("bucket-shared") } returns Result.success(
                listOf(
                    Device("device-a", "sk1", "ek1", Instant.now()),
                    Device("device-b", "sk2", "ek2", Instant.now())
                )
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.uiState.value.isSolo shouldBe false
        }
    }

    // ── clearError ────────────────────────────────────────────────────────────

    test("clearError resets error to null") {
        runTest(testDispatcher) {
            coEvery { getCustodyCalendarUseCase(any(), any(), any()) } returns Result.failure(
                RuntimeException("fail")
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectChild("child-err")
            advanceUntilIdle()
            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
