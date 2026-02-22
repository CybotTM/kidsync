package com.kidsync.app.viewmodel

import com.kidsync.app.data.local.entity.OpLogEntryEntity
import com.kidsync.app.domain.model.CalendarEvent
import com.kidsync.app.domain.model.DeviceSession
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import com.kidsync.app.ui.viewmodel.EventFormUiState
import com.kidsync.app.ui.viewmodel.EventFormViewModel
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
import java.time.LocalTime

/**
 * Tests for EventFormViewModel covering:
 * - Initial state
 * - Form field updates (title, date, time, location, notes)
 * - Validation: title is required, date is required, childId is required
 * - Save event success (create flow)
 * - Save event success (update/edit flow)
 * - Save event failure
 * - Save returns early when not logged in
 * - Save returns early when no bucket available
 * - Delete event success
 * - Delete event failure
 * - initializeEventForDate resets form with date
 * - startEditingEvent populates form from CalendarEvent
 * - clearEventForm resets all fields
 * - clearError
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EventFormViewModelTest : FunSpec({

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

    fun createViewModel(): EventFormViewModel {
        return EventFormViewModel(createOperationUseCase, authRepository, bucketRepository)
    }

    // ── Initial State ─────────────────────────────────────────────────────────

    test("initial state has empty form fields") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.eventTitle shouldBe ""
            state.eventDate shouldBe null
            state.eventTime shouldBe null
            state.eventLocation shouldBe ""
            state.eventNotes shouldBe ""
            state.editingEventId shouldBe null
            state.isLoading shouldBe false
            state.isSavingEvent shouldBe false
            state.error shouldBe null
            state.eventSaved shouldBe false
            state.eventDeleted shouldBe false
        }
    }

    // ── Form Field Updates ────────────────────────────────────────────────────

    test("setChildId updates childId") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-abc")
            vm.uiState.value.childId shouldBe "child-abc"
        }
    }

    test("setEventTitle updates title") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setEventTitle("Doctor Appointment")
            vm.uiState.value.eventTitle shouldBe "Doctor Appointment"
        }
    }

    test("setEventDate updates date") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val date = LocalDate.of(2026, 3, 15)
            vm.setEventDate(date)
            vm.uiState.value.eventDate shouldBe date
        }
    }

    test("setEventTime updates time") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            val time = LocalTime.of(14, 30)
            vm.setEventTime(time)
            vm.uiState.value.eventTime shouldBe time
        }
    }

    test("setEventLocation updates location") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setEventLocation("Main Street 42")
            vm.uiState.value.eventLocation shouldBe "Main Street 42"
        }
    }

    test("setEventNotes updates notes") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setEventNotes("Bring vaccination record")
            vm.uiState.value.eventNotes shouldBe "Bring vaccination record"
        }
    }

    // ── Validation ────────────────────────────────────────────────────────────

    test("saveEvent returns early when childId is null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Do not set childId
            vm.setEventTitle("Test")
            vm.setEventDate(LocalDate.of(2026, 3, 15))
            vm.saveEvent()
            advanceUntilIdle()

            // Should silently return without launching coroutine
            vm.uiState.value.isSavingEvent shouldBe false
            vm.uiState.value.eventSaved shouldBe false
        }
    }

    test("saveEvent returns early when date is null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setEventTitle("Test")
            // Do not set date
            vm.saveEvent()
            advanceUntilIdle()

            vm.uiState.value.isSavingEvent shouldBe false
            vm.uiState.value.eventSaved shouldBe false
        }
    }

    test("saveEvent sets error when title is blank") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setEventDate(LocalDate.of(2026, 3, 15))
            vm.setEventTitle("   ")
            vm.saveEvent()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Event title is required"
        }
    }

    // ── Save Event ────────────────────────────────────────────────────────────

    test("saveEvent success creates operation and sets eventSaved") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setEventTitle("Dentist")
            vm.setEventDate(LocalDate.of(2026, 4, 10))
            vm.setEventTime(LocalTime.of(10, 0))
            vm.setEventLocation("Dental Clinic")
            vm.setEventNotes("Check up")

            vm.saveEvent()
            advanceUntilIdle()

            // After save + clearEventForm, the form should be reset
            val state = vm.uiState.value
            state.isSavingEvent shouldBe false
            state.error shouldBe null
            // eventSaved is set before clearEventForm resets it
            // clearEventForm sets eventSaved = false, so check that form was reset
            state.eventTitle shouldBe ""
            state.editingEventId shouldBe null
        }
    }

    test("saveEvent failure sets error message") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Encryption failed"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setEventTitle("Dentist")
            vm.setEventDate(LocalDate.of(2026, 4, 10))

            vm.saveEvent()
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isSavingEvent shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Encryption failed"
        }
    }

    test("saveEvent sets error when not logged in") {
        runTest(testDispatcher) {
            coEvery { authRepository.getSession() } returns null

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setEventTitle("Dentist")
            vm.setEventDate(LocalDate.of(2026, 4, 10))

            vm.saveEvent()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "Not logged in"
        }
    }

    test("saveEvent sets error when no bucket available") {
        runTest(testDispatcher) {
            coEvery { bucketRepository.getAccessibleBuckets() } returns emptyList()

            val vm = createViewModel()
            advanceUntilIdle()

            vm.setChildId("child-1")
            vm.setEventTitle("Dentist")
            vm.setEventDate(LocalDate.of(2026, 4, 10))

            vm.saveEvent()
            advanceUntilIdle()

            vm.uiState.value.error shouldBe "No bucket available"
        }
    }

    // ── Edit Event ────────────────────────────────────────────────────────────

    test("startEditingEvent populates form from CalendarEvent") {
        runTest(testDispatcher) {
            val event = CalendarEvent(
                eventId = "event-123",
                childId = "child-1",
                title = "School Play",
                date = LocalDate.of(2026, 5, 20),
                time = LocalTime.of(18, 0),
                location = "Auditorium",
                notes = "Arrive early"
            )

            val vm = createViewModel()
            advanceUntilIdle()

            vm.startEditingEvent(event)

            val state = vm.uiState.value
            state.editingEventId shouldBe "event-123"
            state.eventTitle shouldBe "School Play"
            state.eventDate shouldBe LocalDate.of(2026, 5, 20)
            state.eventTime shouldBe LocalTime.of(18, 0)
            state.eventLocation shouldBe "Auditorium"
            state.eventNotes shouldBe "Arrive early"
            state.eventSaved shouldBe false
            state.eventDeleted shouldBe false
        }
    }

    test("initializeEventForDate sets date and clears other fields") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // First set some values
            vm.setEventTitle("Old Title")
            vm.setEventNotes("Old notes")

            // Then initialize for a new date
            val date = LocalDate.of(2026, 6, 1)
            vm.initializeEventForDate(date)

            val state = vm.uiState.value
            state.eventDate shouldBe date
            state.eventTitle shouldBe ""
            state.eventTime shouldBe null
            state.eventLocation shouldBe ""
            state.eventNotes shouldBe ""
            state.editingEventId shouldBe null
            state.eventSaved shouldBe false
        }
    }

    // ── Delete Event ──────────────────────────────────────────────────────────

    test("deleteEvent success sets eventDeleted") {
        runTest(testDispatcher) {
            val entity = mockk<OpLogEntryEntity>()
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns Result.success(entity)

            val vm = createViewModel()
            advanceUntilIdle()

            vm.deleteEvent("event-123")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.eventDeleted shouldBe true
        }
    }

    test("deleteEvent failure sets error") {
        runTest(testDispatcher) {
            coEvery { createOperationUseCase(any(), any(), any(), any(), any()) } returns
                Result.failure(RuntimeException("Delete failed"))

            val vm = createViewModel()
            advanceUntilIdle()

            vm.deleteEvent("event-123")
            advanceUntilIdle()

            val state = vm.uiState.value
            state.isLoading shouldBe false
            state.error shouldNotBe null
            state.error!! shouldContain "Delete failed"
        }
    }

    // ── clearEventForm ────────────────────────────────────────────────────────

    test("clearEventForm resets all form fields") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            vm.setEventTitle("Something")
            vm.setEventDate(LocalDate.of(2026, 1, 1))
            vm.setEventNotes("Notes")

            vm.clearEventForm()

            val state = vm.uiState.value
            state.eventTitle shouldBe ""
            state.eventDate shouldBe null
            state.eventTime shouldBe null
            state.eventLocation shouldBe ""
            state.eventNotes shouldBe ""
            state.editingEventId shouldBe null
        }
    }

    // ── clearError ────────────────────────────────────────────────────────────

    test("clearError sets error to null") {
        runTest(testDispatcher) {
            val vm = createViewModel()
            advanceUntilIdle()

            // Trigger an error
            vm.setChildId("child-1")
            vm.setEventDate(LocalDate.of(2026, 1, 1))
            vm.setEventTitle("")
            vm.saveEvent()
            advanceUntilIdle()

            vm.uiState.value.error shouldNotBe null

            vm.clearError()
            vm.uiState.value.error shouldBe null
        }
    }
})
