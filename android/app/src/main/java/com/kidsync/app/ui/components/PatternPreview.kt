package com.kidsync.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Visual preview of a custody pattern over multiple weeks.
 *
 * Shows a grid of colored cells where each cell represents one day.
 * Parent A and Parent B are shown in their respective colors, with a
 * legend at the bottom displaying the parent names.
 *
 * @param pattern List of Boolean where true = Parent A, false = Parent B
 * @param weeksToShow Number of weeks to display (repeating the pattern as needed)
 * @param parentAName Display name of Parent A
 * @param parentBName Display name of Parent B
 * @param parentAColor Color for Parent A days
 * @param parentBColor Color for Parent B days
 * @param modifier Modifier for the component
 */
@Composable
fun PatternPreview(
    pattern: List<Boolean>,
    weeksToShow: Int = 2,
    parentAName: String,
    parentBName: String,
    parentAColor: Color,
    parentBColor: Color,
    modifier: Modifier = Modifier
) {
    if (pattern.isEmpty()) return

    val totalDays = weeksToShow * 7
    val dayHeaders = listOf("M", "T", "W", "T", "F", "S", "S")

    val description = buildString {
        append("Pattern preview showing $weeksToShow weeks. ")
        append("$parentAName and $parentBName alternating custody.")
    }

    Column(
        modifier = modifier.semantics {
            contentDescription = description
        }
    ) {
        // Day-of-week headers
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            dayHeaders.forEach { header ->
                Text(
                    text = header,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Pattern grid
        for (week in 0 until weeksToShow) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (day in 0..6) {
                    val dayIndex = week * 7 + day
                    val patternIndex = dayIndex % pattern.size
                    val isParentA = pattern[patternIndex]
                    val color = if (isParentA) parentAColor else parentBColor

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(2.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(color.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color, CircleShape)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Legend
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(parentAColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = parentAName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.width(16.dp))

            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(parentBColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = parentBName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
