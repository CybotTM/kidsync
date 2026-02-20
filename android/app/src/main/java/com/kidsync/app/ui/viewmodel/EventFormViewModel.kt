package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.CalendarEvent
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

// ── UI State ────────────────────────────────────────────────────────────────

data class EventFormUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Child context
    val childId: UUID? = null,

    // Event form fields
    val eventTitle: String = "",
    val eventDate: LocalDate? = null,
    val eventTime: LocalTime? = null,
    val eventLocation: String = "",
    val eventNotes: String = "",
    val editingEventId: UUID? = null,
    val isSavingEvent: Boolean = false,

    // Result flags
    val eventSaved: Boolean = false,
    val eventDeleted: Boolean = false
)

// ── ViewModel ───────────────────────────────────────────────────────────────

@HiltViewModel
class EventFormViewModel @Inject constructor(
    private val createOperationUseCase: CreateOperationUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(EventFormUiState())
    val uiState: StateFlow<EventFormUiState> = _uiState.asStateFlow()

    // ── Context Setup ────────────────────────────────────────────────────

    fun setChildId(childId: UUID) {
        _uiState.update { it.copy(childId = childId) }
    }

    // ── Event Form ───────────────────────────────────────────────────────

    fun initializeEventForDate(date: LocalDate) {
        _uiState.update {
            it.copy(
                editingEventId = null,
                eventTitle = "",
                eventDate = date,
                eventTime = null,
                eventLocation = "",
                eventNotes = "",
                eventSaved = false,
                eventDeleted = false
            )
        }
    }

    fun startEditingEvent(event: CalendarEvent) {
        _uiState.update {
            it.copy(
                editingEventId = event.eventId,
                eventTitle = event.title,
                eventDate = event.date,
                eventTime = event.time,
                eventLocation = event.location ?: "",
                eventNotes = event.notes ?: "",
                eventSaved = false,
                eventDeleted = false
            )
        }
    }

    fun setEventTitle(title: String) {
        _uiState.update { it.copy(eventTitle = title) }
    }

    fun setEventDate(date: LocalDate) {
        _uiState.update { it.copy(eventDate = date) }
    }

    fun setEventTime(time: LocalTime?) {
        _uiState.update { it.copy(eventTime = time) }
    }

    fun setEventLocation(location: String) {
        _uiState.update { it.copy(eventLocation = location) }
    }

    fun setEventNotes(notes: String) {
        _uiState.update { it.copy(eventNotes = notes) }
    }

    fun clearEventForm() {
        _uiState.update {
            it.copy(
                editingEventId = null,
                eventTitle = "",
                eventDate = null,
                eventTime = null,
                eventLocation = "",
                eventNotes = "",
                eventSaved = false,
                eventDeleted = false
            )
        }
    }

    // ── Save Event ───────────────────────────────────────────────────────

    fun saveEvent() {
        val state = _uiState.value
        val childId = state.childId ?: return
        val title = state.eventTitle.trim()
        val date = state.eventDate ?: return

        if (title.isBlank()) {
            _uiState.update { it.copy(error = "Event title is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingEvent = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isSavingEvent = false, error = "Not logged in") }
                    return@launch
                }

                val eventId = state.editingEventId ?: UUID.randomUUID()
                val operationType = if (state.editingEventId != null) OperationType.UPDATE else OperationType.CREATE
                val payloadType = if (state.editingEventId != null) "UpdateEvent" else "CreateEvent"

                val payloadMap = buildMap<String, Any?> {
                    put("payloadType", payloadType)
                    put("entityId", eventId.toString())
                    put("timestamp", Instant.now().toString())
                    put("operationType", operationType.name)
                    put("eventId", eventId.toString())
                    put("childId", childId.toString())
                    put("title", title)
                    put("date", date.toString())
                    state.eventTime?.let { put("time", it.toString()) }
                    state.eventLocation.takeIf { it.isNotBlank() }?.let { put("location", it) }
                    state.eventNotes.takeIf { it.isNotBlank() }?.let { put("notes", it) }
                }

                val result = createOperationUseCase(
                    familyId = session.familyId,
                    deviceId = session.deviceId,
                    entityType = EntityType.Event,
                    entityId = eventId,
                    operationType = operationType,
                    payloadMap = payloadMap
                )

                result.fold(
                    onSuccess = {
                        _uiState.update {
                            it.copy(isSavingEvent = false, eventSaved = true)
                        }
                        clearEventForm()
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(isSavingEvent = false, error = error.message)
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isSavingEvent = false, error = e.message ?: "Failed to save event")
                }
            }
        }
    }

    // ── Delete Event ─────────────────────────────────────────────────────

    fun deleteEvent(eventId: UUID) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val session = authRepository.getSession() ?: run {
                    _uiState.update { it.copy(isLoading = false, error = "Not logged in") }
                    return@launch
                }

                val payloadMap = mapOf(
                    "payloadType" to "CancelEvent",
                    "entityId" to eventId.toString(),
                    "timestamp" to Instant.now().toString(),
                    "operationType" to OperationType.UPDATE.name,
                    "eventId" to eventId.toString()
                )

                val result = createOperationUseCase(
                    familyId = session.familyId,
                    deviceId = session.deviceId,
                    entityType = EntityType.Event,
                    entityId = eventId,
                    operationType = OperationType.UPDATE,
                    payloadMap = payloadMap
                )

                result.fold(
                    onSuccess = {
                        _uiState.update { it.copy(isLoading = false, eventDeleted = true) }
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isLoading = false, error = error.message) }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = e.message ?: "Failed to delete event")
                }
            }
        }
    }

    // ── Utility ──────────────────────────────────────────────────────────

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
