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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.kidsync.app.R
import com.kidsync.app.ui.components.CalendarDayCell
import com.kidsync.app.ui.components.CalendarGrid
import com.kidsync.app.ui.theme.Blue40
import com.kidsync.app.ui.theme.Amber40
import com.kidsync.app.ui.viewmodel.CalendarViewModel
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Main calendar view showing a monthly grid with color-coded parent assignments.
 *
 * Features:
 * - Monthly grid layout with parent color indicators per day
 * - Today indicator with highlight
 * - Current parent display in header
 * - Child selector dropdown for multiple children
 * - Month navigation with arrows
 * - Tap day to see detail
 * - FAB for "Request Swap"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onBack: () -> Unit,
    onDayClick: (String) -> Unit,
    onRequestSwap: () -> Unit,
    onSetupSchedule: () -> Unit,
    viewModel: CalendarViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val parentAColor = Blue40
    val parentBColor = Amber40
    val today = LocalDate.now()

    // Show errors via snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Load data on initial composition
    LaunchedEffect(uiState.childId) {
        if (uiState.childId != null) {
            viewModel.loadMonth()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.calendar_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        floatingActionButton = {
            if (uiState.hasSchedule) {
                ExtendedFloatingActionButton(
                    onClick = onRequestSwap,
                    icon = {
                        Icon(
                            imageVector = Icons.Filled.SwapHoriz,
                            contentDescription = null
                        )
                    },
                    text = { Text(stringResource(R.string.calendar_request_swap)) },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Loading indicator
            if (uiState.isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Child selector (when multiple children)
            if (uiState.children.size > 1) {
                ChildSelector(
                    children = uiState.children,
                    selectedChildId = uiState.childId,
                    onChildSelected = { viewModel.selectChild(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Current parent indicator
            if (uiState.todayAssignedParentId != null) {
                val todayParentName = when (uiState.todayAssignedParentId) {
                    uiState.parentAId -> uiState.parentAName
                    uiState.parentBId -> uiState.parentBName
                    else -> ""
                }
                val todayParentColor = when (uiState.todayAssignedParentId) {
                    uiState.parentAId -> parentAColor
                    uiState.parentBId -> parentBColor
                    else -> Color.Transparent
                }

                CurrentParentBanner(
                    parentName = todayParentName,
                    parentColor = todayParentColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Month navigation header
            MonthNavigationHeader(
                yearMonth = uiState.currentMonth,
                onPreviousMonth = { viewModel.navigateMonth(false) },
                onNextMonth = { viewModel.navigateMonth(true) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Calendar grid
            if (uiState.hasSchedule) {
                val days = uiState.assignments.map { (date, custodyDay) ->
                    CalendarDayCell(
                        date = date,
                        assignedParentId = custodyDay.assignedParentId,
                        isOverride = custodyDay.source == com.kidsync.app.domain.model.CustodyDaySource.OVERRIDE
                    )
                }

                CalendarGrid(
                    yearMonth = uiState.currentMonth,
                    days = days,
                    parentAId = uiState.parentAId ?: java.util.UUID.randomUUID(),
                    parentBId = uiState.parentBId ?: java.util.UUID.randomUUID(),
                    parentAName = uiState.parentAName,
                    parentBName = uiState.parentBName,
                    parentAColor = parentAColor,
                    parentBColor = parentBColor,
                    selectedDate = uiState.selectedDate,
                    today = today,
                    onDayClick = { date ->
                        viewModel.selectDate(date)
                        onDayClick(date.toString())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Parent legend
                ParentLegend(
                    parentAName = uiState.parentAName,
                    parentBName = uiState.parentBName,
                    parentAColor = parentAColor,
                    parentBColor = parentBColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            } else if (!uiState.isLoading) {
                // No schedule configured
                NoSchedulePrompt(
                    onSetupSchedule = onSetupSchedule,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(80.dp)) // FAB clearance
        }
    }
}

@Composable
private fun ChildSelector(
    children: List<com.kidsync.app.ui.viewmodel.ChildInfo>,
    selectedChildId: java.util.UUID?,
    onChildSelected: (java.util.UUID) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedChild = children.find { it.id == selectedChildId }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = "Select child: ${selectedChild?.name ?: "none selected"}"
                }
        ) {
            Text(
                text = selectedChild?.name ?: stringResource(R.string.calendar_select_child),
                modifier = Modifier.weight(1f)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            children.forEach { child ->
                DropdownMenuItem(
                    text = { Text(child.name) },
                    onClick = {
                        onChildSelected(child.id)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun CurrentParentBanner(
    parentName: String,
    parentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = parentColor.copy(alpha = 0.12f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .semantics {
                    contentDescription = "Today's custody: $parentName"
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(parentColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.calendar_today_with),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = parentName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
            }
        }
    }
}

@Composable
private fun MonthNavigationHeader(
    yearMonth: YearMonth,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthName = yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val headerText = "$monthName ${yearMonth.year}"

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPreviousMonth,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ChevronLeft,
                contentDescription = stringResource(R.string.calendar_previous_month),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }

        Text(
            text = headerText,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .semantics {
                    heading()
                    contentDescription = headerText
                }
        )

        IconButton(
            onClick = onNextMonth,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.calendar_next_month),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ParentLegend(
    parentAName: String,
    parentBName: String,
    parentAColor: Color,
    parentBColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .background(parentAColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = parentAName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.width(24.dp))

        Box(
            modifier = Modifier
                .size(14.dp)
                .background(parentBColor, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = parentBName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun NoSchedulePrompt(
    onSetupSchedule: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.calendar_no_schedule),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.calendar_no_schedule_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onSetupSchedule) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.calendar_setup_schedule))
            }
        }
    }
}
