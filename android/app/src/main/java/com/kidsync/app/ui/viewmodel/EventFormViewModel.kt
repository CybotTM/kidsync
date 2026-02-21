package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.CalendarEvent
import com.kidsync.app.domain.model.EntityType
import com.kidsync.app.domain.model.OperationType
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.sync.CreateOperationUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    private val authRepository: AuthRepository,
    private val bucketRepository: BucketRepository
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
                editingEventId = try { UUID.fromString(event.eventId) } catch (_: Exception) { null },
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

                val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull() ?: run {
                    _uiState.update { it.copy(isSavingEvent = false, error = "No bucket available") }
                    return@launch
                }

                val eventId = state.editingEventId ?: UUID.randomUUID()
                val operationType = if (state.editingEventId != null) OperationType.UPDATE else OperationType.CREATE

                val contentData = buildJsonObject {
                    put("eventId", JsonPrimitive(eventId.toString()))
                    put("childId", JsonPrimitive(childId.toString()))
                    put("title", JsonPrimitive(title))
                    put("date", JsonPrimitive(date.toString()))
                    state.eventTime?.let { put("time", JsonPrimitive(it.toString())) }
                    state.eventLocation.takeIf { it.isNotBlank() }?.let { put("location", JsonPrimitive(it)) }
                    state.eventNotes.takeIf { it.isNotBlank() }?.let { put("notes", JsonPrimitive(it)) }
                }

                val result = createOperationUseCase(
                    bucketId = bucketId,
                    entityType = EntityType.CalendarEvent,
                    entityId = eventId.toString(),
                    operationType = operationType,
                    contentData = contentData
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

                val bucketId = bucketRepository.getAccessibleBuckets().firstOrNull() ?: run {
                    _uiState.update { it.copy(isLoading = false, error = "No bucket available") }
                    return@launch
                }

                val contentData = buildJsonObject {
                    put("eventId", JsonPrimitive(eventId.toString()))
                    put("cancelled", JsonPrimitive(true))
                }

                val result = createOperationUseCase(
                    bucketId = bucketId,
                    entityType = EntityType.CalendarEvent,
                    entityId = eventId.toString(),
                    operationType = OperationType.UPDATE,
                    contentData = contentData
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
