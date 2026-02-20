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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kidsync.app.R
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.PatternPreview
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.theme.Amber40
import com.kidsync.app.ui.theme.Blue40
import com.kidsync.app.ui.viewmodel.CalendarViewModel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.TimeZone

/**
 * Anchor date and timezone selection screen.
 *
 * The user picks:
 * - Anchor date (when the custody pattern starts repeating from day 1)
 * - Timezone (searchable dropdown)
 *
 * Shows a review summary of the complete schedule configuration before saving.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnchorDateScreen(
    onBack: () -> Unit,
    onScheduleSaved: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var showDatePicker by remember { mutableStateOf(false) }
    var tzSearchQuery by remember { mutableStateOf("") }
    var tzExpanded by remember { mutableStateOf(false) }

    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
    val availableTimeZones = remember {
        TimeZone.getAvailableIDs()
            .filter { !it.startsWith("SystemV") && it.contains("/") }
            .sorted()
    }
    val filteredTimeZones = remember(tzSearchQuery) {
        if (tzSearchQuery.isBlank()) availableTimeZones
        else availableTimeZones.filter {
            it.contains(tzSearchQuery, ignoreCase = true)
        }.take(20)
    }

    // Navigate after save
    LaunchedEffect(uiState.hasSchedule) {
        if (uiState.hasSchedule && !uiState.isLoading) {
            // Small delay to let state propagate
        }
    }

    // Show errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.calendar_anchor_date),
                onBack = onBack
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
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.calendar_anchor_heading),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.calendar_anchor_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Anchor date picker
            Text(
                text = stringResource(R.string.calendar_anchor_date_label),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.semantics {
                    contentDescription = "Anchor date: ${
                        uiState.setupAnchorDate?.format(dateFormatter) ?: "not selected"
                    }"
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.CalendarMonth,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = uiState.setupAnchorDate?.format(dateFormatter)
                        ?: stringResource(R.string.calendar_select_date)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Timezone selector
            Text(
                text = stringResource(R.string.calendar_timezone_label),
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = tzExpanded,
                onExpandedChange = { tzExpanded = it }
            ) {
                OutlinedTextField(
                    value = tzSearchQuery.ifBlank { uiState.setupTimeZone },
                    onValueChange = { tzSearchQuery = it; tzExpanded = true },
                    label = { Text(stringResource(R.string.calendar_timezone_search)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = tzExpanded) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor()
                )

                ExposedDropdownMenu(
                    expanded = tzExpanded,
                    onDismissRequest = { tzExpanded = false }
                ) {
                    filteredTimeZones.forEach { tz ->
                        DropdownMenuItem(
                            text = { Text(tz) },
                            onClick = {
                                viewModel.setTimeZone(tz)
                                tzSearchQuery = ""
                                tzExpanded = false
                            },
                            leadingIcon = if (tz == uiState.setupTimeZone) {
                                { Icon(Icons.Filled.Check, contentDescription = null) }
                            } else null
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Review summary
            Text(
                text = stringResource(R.string.calendar_review_summary),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SummaryRow(
                        label = stringResource(R.string.calendar_summary_cycle),
                        value = stringResource(R.string.calendar_summary_cycle_value, uiState.setupCycleLength)
                    )

                    SummaryRow(
                        label = stringResource(R.string.calendar_summary_anchor),
                        value = uiState.setupAnchorDate?.format(dateFormatter) ?: "-"
                    )

                    SummaryRow(
                        label = stringResource(R.string.calendar_summary_timezone),
                        value = uiState.setupTimeZone
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.setupPattern.isNotEmpty()) {
                        PatternPreview(
                            pattern = uiState.setupPattern,
                            weeksToShow = 2,
                            parentAName = uiState.parentAName,
                            parentBName = uiState.parentBName,
                            parentAColor = Blue40,
                            parentBColor = Amber40,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save button
            LoadingButton(
                text = stringResource(R.string.calendar_save_schedule),
                onClick = {
                    viewModel.saveSchedule()
                    onScheduleSaved()
                },
                isLoading = uiState.isLoading,
                enabled = uiState.setupAnchorDate != null && uiState.setupPattern.isNotEmpty(),
                loadingDescription = stringResource(R.string.cd_saving_schedule),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Date picker dialog
    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = uiState.setupAnchorDate?.let {
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
                            viewModel.setAnchorDate(selected)
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
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
