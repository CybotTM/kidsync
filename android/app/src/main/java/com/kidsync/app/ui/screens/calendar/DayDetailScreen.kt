package com.kidsync.app.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kidsync.app.R
import com.kidsync.app.domain.model.CalendarEvent
import com.kidsync.app.domain.model.CustodyDaySource
import com.kidsync.app.domain.model.OverrideStatus
import com.kidsync.app.domain.model.ScheduleOverride
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.theme.Amber40
import com.kidsync.app.ui.theme.Blue40
import com.kidsync.app.ui.viewmodel.CalendarViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Day detail view showing:
 * - Assigned parent for the day
 * - Events/appointments for the day
 * - Pending swap requests affecting this day
 * - "Add Event" and "Request Swap" buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailScreen(
    date: String,
    onBack: () -> Unit,
    onAddEvent: (String) -> Unit,
    onRequestSwap: (String) -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val localDate = LocalDate.parse(date)
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
    val formattedDate = localDate.format(dateFormatter)

    val parentAColor = Blue40
    val parentBColor = Amber40

    // Select this date on entry
    LaunchedEffect(date) {
        viewModel.selectDate(localDate)
    }

    val assignment = uiState.assignments[localDate]
    val assignedParentName = when (assignment?.assignedParentId) {
        uiState.parentAId -> uiState.parentAName
        uiState.parentBId -> uiState.parentBName
        else -> stringResource(R.string.calendar_unassigned)
    }
    val assignedParentColor = when (assignment?.assignedParentId) {
        uiState.parentAId -> parentAColor
        uiState.parentBId -> parentBColor
        else -> Color.Transparent
    }
    val isOverride = assignment?.source == CustodyDaySource.OVERRIDE

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = formattedDate,
                onBack = onBack
            )
        },
        modifier = modifier
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Assigned parent card
            item {
                Spacer(modifier = Modifier.height(8.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Assigned to $assignedParentName"
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = assignedParentColor.copy(alpha = 0.12f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .background(assignedParentColor, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.calendar_assigned_parent),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = assignedParentName,
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.semantics { heading() }
                            )
                            if (isOverride) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.calendar_override_indicator),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
            }

            // Events section header
            item {
                Text(
                    text = stringResource(R.string.calendar_events),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() }
                )
            }

            // Events list
            if (uiState.events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.calendar_no_events_day),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(uiState.events) { event ->
                    EventCard(event = event)
                }
            }

            // Pending swaps section
            val relevantSwaps = uiState.pendingSwaps.filter { swap ->
                swap.status == OverrideStatus.PROPOSED &&
                    !localDate.isBefore(swap.startDate) &&
                    !localDate.isAfter(swap.endDate)
            }

            if (relevantSwaps.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.calendar_pending_swaps),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() }
                    )
                }

                items(relevantSwaps) { swap ->
                    SwapRequestCard(
                        swap = swap,
                        parentAId = uiState.parentAId ?: "",
                        parentAName = uiState.parentAName,
                        parentBName = uiState.parentBName
                    )
                }
            }

            // Action buttons
            item {
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { onAddEvent(date) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.calendar_add_event))
                    }

                    OutlinedButton(
                        onClick = { onRequestSwap(date) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.calendar_request_swap))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun EventCard(
    event: CalendarEvent,
    modifier: Modifier = Modifier
) {
    val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    val description = buildString {
        append(event.title)
        event.time?.let { append(" at ${it.format(timeFormatter)}") }
        event.location?.let { append(" at $it") }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (event.time != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AccessTime,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = event.time.format(timeFormatter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (event.location != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (event.notes != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = event.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SwapRequestCard(
    swap: ScheduleOverride,
    parentAId: String,
    parentAName: String,
    parentBName: String,
    modifier: Modifier = Modifier
) {
    val dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    val proposerName = if (swap.proposerId == parentAId) parentAName else parentBName
    val assigneeName = if (swap.assignedParentId == parentAId) parentAName else parentBName

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.calendar_swap_pending),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(
                    R.string.calendar_swap_from_to,
                    proposerName,
                    swap.startDate.format(dateFormatter),
                    swap.endDate.format(dateFormatter)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = stringResource(R.string.calendar_swap_assigned_to, assigneeName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (swap.note != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = swap.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
