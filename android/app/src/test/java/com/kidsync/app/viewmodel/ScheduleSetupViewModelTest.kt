package com.kidsync.app.viewmodel

import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import com.kidsync.app.ui.viewmodel.PatternPreset
import com.kidsync.app.ui.viewmodel.ScheduleSetupViewModel
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
import java.util.UUID

/**
 * Tests for ScheduleSetupViewModel covering:
 * - Initial state defaults
 * - Context setup (childId, parent info)
 * - selectPresetPattern applies pattern and cycle length
 * - setCustomCycleLength clamps and extends/truncates pattern
 * - togglePatternDay flips individual days
 * - togglePatternDay ignores out-of-bounds index
 * - setAnchorDate and setTimeZone
 * - saveSchedule success
 * - saveSchedule failure
 * - saveSchedule returns early when required fields are missing
 * - saveSchedule sets error when not logged in
 * - saveSchedule sets error when no bucket available
 * - clearError
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScheduleSetupViewModelTest : FunSpec({

    val testDispatcher = StandardTestDispatcher()

    val createOperationUseCase = mockk<CreateOperationUseCase>()
    val authRepository = mockk<AuthRepository>(relaxed = true)
    val bucketRepository = mockk<BucketRepository>(relaxed = true)

    beforeEach {
        Dispatchers.setMain(testDispatcher)
        clearAllMocks()
        coEvery { authRepository.getSession() } returns DeviceSession("device-1", "token", 3600)
        coEvery { bucketRepository.getAccessibleBuckets() } returns listOf("bucket-1")
    }

    afterEach {
        Dispatchers.resetMain()
    }

    fun createViewModel(): ScheduleSetupViewModel {
        return ScheduleSetupViewModel(createOperationUseCase, authRepository, bucketRepository)
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
            state.parentAId shouldBe null
            state.parentBId shouldBe null
            state.setupPattern shouldBe emptyList()
            state.setupCycleLength shouldBe 14
            state.setupAnchorDate shouldBe null
            state.scheduleSaved shouldBe false
        }
    }

    // ── Context Setup ─────────────────────────────────────────────────────────

    test("setChildId updates childId") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val childId = UUID.randomUUID()
            vm.setChildId(childId)
            vm.uiState.value.childId shouldBe childId
        }
    }

    test("setParentInfo updates parent IDs and names") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val parentA = UUID.randomUUID()
            val parentB = UUID.randomUUID()
            vm.setParentInfo(parentA, "Alice", parentB, "Bob")

            val state = vm.uiState.value
            state.parentAId shouldBe parentA
            state.parentAName shouldBe "Alice"
            state.parentBId shouldBe parentB
            state.parentBName shouldBe "Bob"
        }
    }

    // ── Pattern Selection ─────────────────────────────────────────────────────

    test("selectPresetPattern applies WEEK_ON_WEEK_OFF pattern") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)

            val state = vm.uiState.value
            state.setupPattern.size shouldBe 14
            state.setupCycleLength shouldBe 14
            // First 7 days should be true (Parent A), next 7 false (Parent B)
            state.setupPattern.take(7).all { it } shouldBe true
            state.setupPattern.drop(7).all { !it } shouldBe true
        }
    }

    test("selectPresetPattern applies TWO_TWO_THREE pattern") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectPresetPattern(PatternPreset.TWO_TWO_THREE)

            val state = vm.uiState.value
            state.setupPattern.size shouldBe 14
            state.setupCycleLength shouldBe 14
        }
    }

    test("setCustomCycleLength clamps to range 1..60") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setCustomCycleLength(0)
            vm.uiState.value.setupCycleLength shouldBe 1

            vm.setCustomCycleLength(100)
            vm.uiState.value.setupCycleLength shouldBe 60

            vm.setCustomCycleLength(7)
            vm.uiState.value.setupCycleLength shouldBe 7
        }
    }

    test("setCustomCycleLength extends pattern with true for new days") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Start with a 3-day pattern
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            // Pattern is 14 days. Extend to 18.
            vm.setCustomCycleLength(18)

            val state = vm.uiState.value
            state.setupPattern.size shouldBe 18
            // First 14 should preserve original pattern, next 4 default to true
            state.setupPattern.drop(14).all { it } shouldBe true
        }
    }

    test("setCustomCycleLength truncates pattern when shrinking") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            vm.setCustomCycleLength(5)

            val state = vm.uiState.value
            state.setupPattern.size shouldBe 5
            // Should preserve first 5 days of original pattern (all true since first 7 were true)
            state.setupPattern.all { it } shouldBe true
        }
    }

    // ── Toggle Pattern Day ────────────────────────────────────────────────────

    test("togglePatternDay flips a specific day") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            // Day 0 is true (Parent A). Toggle it.
            vm.togglePatternDay(0)
            vm.uiState.value.setupPattern[0] shouldBe false

            // Toggle back
            vm.togglePatternDay(0)
            vm.uiState.value.setupPattern[0] shouldBe true
        }
    }

    test("togglePatternDay ignores out-of-bounds index") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            val originalPattern = vm.uiState.value.setupPattern.toList()

            vm.togglePatternDay(100) // out of bounds
            vm.togglePatternDay(-1)  // negative

            vm.uiState.value.setupPattern shouldBe originalPattern
        }
    }

    // ── Anchor Date & Timezone ────────────────────────────────────────────────

    test("setAnchorDate updates anchor date") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val date = LocalDate.of(2026, 3, 1)
            vm.setAnchorDate(date)
            vm.uiState.value.setupAnchorDate shouldBe date
        }
    }

    test("setTimeZone updates timezone") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setTimeZone("America/New_York")
            vm.uiState.value.setupTimeZone shouldBe "America/New_York"
        }
    }

    // ── Save Schedule ─────────────────────────────────────────────────────────

    test("saveSchedule success sets scheduleSaved") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            val childId = UUID.randomUUID()
            val parentA = UUID.randomUUID()
            val parentB = UUID.randomUUID()

            vm.setChildId(childId)
            vm.setParentInfo(parentA, "Alice", parentB, "Bob")
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            vm.setAnchorDate(LocalDate.of(2026, 3, 1))

            vm.saveSchedule()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.scheduleSaved shouldBe true
            state.isLoading shouldBe false
            state.error shouldBe null
        }
    }

    test("saveSchedule failure sets error") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Sync error"))

            val vm = createViewModel()
            advanceUntilIdle()

            val childId = UUID.randomUUID()
            val parentA = UUID.randomUUID()
            val parentB = UUID.randomUUID()

            vm.setChildId(childId)
            vm.setParentInfo(parentA, "Alice", parentB, "Bob")
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            vm.setAnchorDate(LocalDate.of(2026, 3, 1))

            vm.saveSchedule()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.scheduleSaved shouldBe false
            state.isLoading shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Sync error"
        }
    }

    test("saveSchedule returns early when childId is null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Don't set childId
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            vm.setAnchorDate(LocalDate.of(2026, 3, 1))

            vm.saveSchedule()
            advanceUntilIdle()

            vm.uiState.value.scheduleSaved shouldBe false
            vm.uiState.value.isLoading shouldBe false
        }
    }

    test("saveSchedule returns early when anchorDate is null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId(UUID.randomUUID())
            vm.setParentInfo(UUID.randomUUID(), "A", UUID.randomUUID(), "B")
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            // Don't set anchor date

            vm.saveSchedule()
            advanceUntilIdle()

            vm.uiState.value.scheduleSaved shouldBe false
        }
    }

    test("saveSchedule returns early when pattern is empty") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId(UUID.randomUUID())
            vm.setParentInfo(UUID.randomUUID(), "A", UUID.randomUUID(), "B")
            vm.setAnchorDate(LocalDate.of(2026, 3, 1))
            // Don't set pattern

            vm.saveSchedule()
            advanceUntilIdle()

            vm.uiState.value.scheduleSaved shouldBe false
        }
    }

    test("saveSchedule sets error when not logged in") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            val childId = UUID.randomUUID()
            val parentA = UUID.randomUUID()
            val parentB = UUID.randomUUID()

            vm.setChildId(childId)
            vm.setParentInfo(parentA, "Alice", parentB, "Bob")
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            vm.setAnchorDate(LocalDate.of(2026, 3, 1))

            vm.saveSchedule()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Not logged in"
        }
    }

    test("saveSchedule sets error when no bucket available") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            val childId = UUID.randomUUID()
            val parentA = UUID.randomUUID()
            val parentB = UUID.randomUUID()

            vm.setChildId(childId)
            vm.setParentInfo(parentA, "Alice", parentB, "Bob")
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            vm.setAnchorDate(LocalDate.of(2026, 3, 1))

            vm.saveSchedule()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "No bucket available"
        }
    }

    // ── clearError ────────────────────────────────────────────────────────────

    test("clearError sets error to null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Force error via save without session
            coEvery { authRepository.getSession() } returns null
            val childId = UUID.randomUUID()
            vm.setChildId(childId)
            vm.setParentInfo(UUID.randomUUID(), "A", UUID.randomUUID(), "B")
            vm.selectPresetPattern(PatternPreset.WEEK_ON_WEEK_OFF)
            vm.setAnchorDate(LocalDate.of(2026, 3, 1))
            vm.saveSchedule()
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
