package com.kidsync.app.ui.screens.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kidsync.app.R
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.EventFormViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Event form screen for adding or editing calendar events.
 *
 * Fields:
 * - Title (required)
 * - Date (required, pre-filled if passed)
 * - Time (optional)
 * - Location (optional)
 * - Notes (optional)
 *
 * Actions: Save, Cancel, Delete (for existing events)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventFormScreen(
    date: String?,
    eventId: String?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EventFormViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val isEditing = eventId != null
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Initialize form from parameters
    LaunchedEffect(date, eventId) {
        if (eventId == null && date != null) {
            viewModel.initializeEventForDate(LocalDate.parse(date))
        }
        // If editing, the event should already be loaded via startEditingEvent()
    }

    // Handle errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(
                    if (isEditing) R.string.calendar_edit_event else R.string.calendar_add_event
                ),
                onBack = {
                    viewModel.clearEventForm()
                    onBack()
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(
                    if (isEditing) R.string.calendar_edit_event_heading
                    else R.string.calendar_add_event_heading
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Title
            OutlinedTextField(
                value = uiState.eventTitle,
                onValueChange = { viewModel.setEventTitle(it) },
                label = { Text(stringResource(R.string.calendar_event_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Date
            Text(
                text = stringResource(R.string.calendar_event_date),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = { showDatePicker = true }) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiState.eventDate?.format(dateFormatter)
                        ?: stringResource(R.string.calendar_select_date)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Time (optional)
            Text(
                text = stringResource(R.string.calendar_event_time),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                TextButton(onClick = { showTimePicker = true }) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.eventTime?.format(timeFormatter)
                            ?: stringResource(R.string.calendar_select_time)
                    )
                }

                if (uiState.eventTime != null) {
                    TextButton(onClick = { viewModel.setEventTime(null) }) {
                        Text(stringResource(R.string.calendar_clear))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Location (optional)
            OutlinedTextField(
                value = uiState.eventLocation,
                onValueChange = { viewModel.setEventLocation(it) },
                label = { Text(stringResource(R.string.calendar_event_location)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Notes (optional)
            OutlinedTextField(
                value = uiState.eventNotes,
                onValueChange = { viewModel.setEventNotes(it) },
                label = { Text(stringResource(R.string.calendar_event_notes)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isEditing) {
                    OutlinedButton(
                        onClick = {
                            eventId?.let { id ->
                                viewModel.deleteEvent(id)
                                onBack()
                            }
                        },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.calendar_delete))
                    }
                }

                LoadingButton(
                    text = stringResource(R.string.calendar_save),
                    onClick = {
                        viewModel.saveEvent()
                        onSaved()
                    },
                    isLoading = uiState.isSavingEvent,
                    enabled = uiState.eventTitle.isNotBlank() && uiState.eventDate != null,
                    loadingDescription = stringResource(R.string.cd_saving_event),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cancel
            TextButton(
                onClick = {
                    viewModel.clearEventForm()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.calendar_cancel))
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = uiState.eventDate?.let {
                it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { millis ->
                            val selected = Instant.ofEpochMilli(millis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDate()
                            viewModel.setEventDate(selected)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(R.string.calendar_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.calendar_cancel))
                }
            }
        ) {
            DatePicker(state = state)
        }
    }

    // Time picker dialog
    if (showTimePicker) {
        val currentTime = uiState.eventTime ?: LocalTime.of(12, 0)
        val state = rememberTimePickerState(
            initialHour = currentTime.hour,
            initialMinute = currentTime.minute,
            is24Hour = true
        )

        TimePickerDialogWrapper(
            onDismiss = { showTimePicker = false },
            onConfirm = {
                viewModel.setEventTime(LocalTime.of(state.hour, state.minute))
                showTimePicker = false
            }
        ) {
            TimePicker(state = state)
        }
    }
}

/**
 * Wrapper composable for the time picker dialog since Material 3 does not yet
 * provide a built-in TimePickerDialog composable.
 */
@Composable
private fun TimePickerDialogWrapper(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.calendar_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.calendar_cancel))
            }
        },
        text = { content() }
    )
}
