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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Data entry for a single bar in the chart.
 */
data class BarChartEntry(
    val label: String,
    val value: Long,
    val formattedValue: String,
    val color: Color
)

/**
 * A simple vertical bar chart composable for expense summaries.
 *
 * Displays vertical bars with category labels below and amounts on top.
 * Each bar is announced for accessibility with its label and amount.
 *
 * @param entries List of bar chart data entries
 * @param maxBarHeight Maximum height for the tallest bar
 * @param modifier Optional modifier
 */
@Composable
fun SimpleBarChart(
    entries: List<BarChartEntry>,
    modifier: Modifier = Modifier,
    maxBarHeight: Dp = 160.dp
) {
    if (entries.isEmpty()) return

    val maxValue = entries.maxOf { it.value }.coerceAtLeast(1)

    val chartDescription = entries.joinToString(". ") { entry ->
        "${entry.label}: ${entry.formattedValue}"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = chartDescription
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxBarHeight + 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom
        ) {
            entries.forEach { entry ->
                val barHeight = if (maxValue > 0) {
                    (entry.value.toFloat() / maxValue.toFloat() * maxBarHeight.value).dp
                        .coerceAtLeast(4.dp)
                } else {
                    4.dp
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom
                ) {
                    // Amount label on top
                    Text(
                        text = entry.formattedValue,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    // Bar
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(barHeight)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(entry.color)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Category labels below bars
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            entries.forEach { entry ->
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
