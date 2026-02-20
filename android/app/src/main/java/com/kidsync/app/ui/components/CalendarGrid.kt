package com.kidsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

/**
 * Data class representing a single day cell in the calendar grid.
 *
 * @param date The date for this cell
 * @param assignedParentId The UUID of the parent assigned for this day, or null if no schedule
 * @param isOverride Whether this assignment comes from an override rather than the base schedule
 */
data class CalendarDayCell(
    val date: LocalDate,
    val assignedParentId: UUID? = null,
    val isOverride: Boolean = false
)

/**
 * Reusable monthly calendar grid composable.
 *
 * Displays a 7-column grid (Monday-Sunday or locale-based) with each cell showing
 * the day number and a color indicator for the assigned parent. Today is highlighted
 * and the selected day is outlined.
 *
 * @param yearMonth The month to display
 * @param days The list of day cells with parent assignments
 * @param parentAId UUID of Parent A
 * @param parentBId UUID of Parent B
 * @param parentAName Display name of Parent A
 * @param parentBName Display name of Parent B
 * @param parentAColor Color for Parent A days
 * @param parentBColor Color for Parent B days
 * @param selectedDate Currently selected date, or null
 * @param today Today's date
 * @param onDayClick Callback when a day is tapped
 * @param modifier Modifier for the grid
 */
@Composable
fun CalendarGrid(
    yearMonth: YearMonth,
    days: List<CalendarDayCell>,
    parentAId: UUID,
    parentBId: UUID,
    parentAName: String,
    parentBName: String,
    parentAColor: Color,
    parentBColor: Color,
    selectedDate: LocalDate?,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val daysByDate = days.associateBy { it.date }
    val firstDayOfMonth = yearMonth.atDay(1)
    val lastDayOfMonth = yearMonth.atEndOfMonth()

    // Week starts on Monday (ISO)
    val firstDayOfWeek = DayOfWeek.MONDAY
    val daysOfWeek = (0..6).map { firstDayOfWeek.plus(it.toLong()) }

    // Calculate offset: how many blank cells before day 1
    val startOffset = (firstDayOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val totalDays = yearMonth.lengthOfMonth()
    val totalCells = startOffset + totalDays
    val rows = (totalCells + 6) / 7

    Column(modifier = modifier) {
        // Day-of-week headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            daysOfWeek.forEach { dow ->
                Text(
                    text = dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .semantics {
                            contentDescription = dow.getDisplayName(TextStyle.FULL, Locale.getDefault())
                        }
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Calendar rows
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (col in 0..6) {
                    val cellIndex = row * 7 + col
                    val dayNumber = cellIndex - startOffset + 1

                    if (dayNumber < 1 || dayNumber > totalDays) {
                        // Empty cell
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                        )
                    } else {
                        val date = yearMonth.atDay(dayNumber)
                        val dayCell = daysByDate[date]
                        val isToday = date == today
                        val isSelected = date == selectedDate

                        val parentName = when (dayCell?.assignedParentId) {
                            parentAId -> parentAName
                            parentBId -> parentBName
                            else -> null
                        }

                        val parentColor = when (dayCell?.assignedParentId) {
                            parentAId -> parentAColor
                            parentBId -> parentBColor
                            else -> Color.Transparent
                        }

                        val description = buildString {
                            append("$dayNumber")
                            if (parentName != null) {
                                append(", assigned to $parentName")
                            }
                            if (isToday) append(", today")
                            if (dayCell?.isOverride == true) append(", modified")
                        }

                        CalendarDayCell(
                            dayNumber = dayNumber,
                            parentColor = parentColor,
                            isToday = isToday,
                            isSelected = isSelected,
                            contentDescription = description,
                            onClick = { onDayClick(date) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    dayNumber: Int,
    parentColor: Color,
    isToday: Boolean,
    isSelected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(shape)
            .then(
                if (parentColor != Color.Transparent) {
                    Modifier.background(parentColor.copy(alpha = 0.2f), shape)
                } else {
                    Modifier
                }
            )
            .then(
                if (isSelected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else if (isToday) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.outline, shape)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
            }
            .padding(4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = dayNumber.toString(),
                style = if (isToday) {
                    MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = if (isToday) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )

            if (parentColor != Color.Transparent) {
                Spacer(modifier = Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(parentColor, CircleShape)
                )
            }
        }
    }
}
