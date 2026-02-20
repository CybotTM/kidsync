package com.kidsync.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kidsync.app.R
import com.kidsync.app.domain.model.ExpenseStatusType

/**
 * Colored status badge chip for expense status.
 *
 * Color coding:
 * - PENDING: blue (primary)
 * - ACKNOWLEDGED: green (secondary/teal)
 * - DISPUTED: red/amber (error)
 * - RESOLVED: gray (neutral)
 */
@Composable
fun ExpenseStatusBadge(
    status: ExpenseStatusType,
    modifier: Modifier = Modifier
) {
    val (label, icon, containerColor, labelColor) = statusAttributes(status)

    val accessibilityLabel = stringResource(R.string.cd_expense_status, label)

    AssistChip(
        onClick = { },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = labelColor,
            leadingIconContentColor = labelColor
        ),
        border = null,
        modifier = modifier.semantics {
            contentDescription = accessibilityLabel
        }
    )
}

private data class StatusAttributes(
    val label: String,
    val icon: ImageVector,
    val containerColor: Color,
    val labelColor: Color
)

@Composable
private fun statusAttributes(status: ExpenseStatusType): StatusAttributes {
    return when (status) {
        ExpenseStatusType.PENDING -> StatusAttributes(
            label = stringResource(R.string.expense_status_pending),
            icon = Icons.Filled.HourglassBottom,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        ExpenseStatusType.ACKNOWLEDGED -> StatusAttributes(
            label = stringResource(R.string.expense_status_acknowledged),
            icon = Icons.Filled.CheckCircle,
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        ExpenseStatusType.DISPUTED -> StatusAttributes(
            label = stringResource(R.string.expense_status_disputed),
            icon = Icons.Filled.Report,
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer
        )
        ExpenseStatusType.RESOLVED -> StatusAttributes(
            label = stringResource(R.string.expense_status_resolved),
            icon = Icons.Filled.TaskAlt,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
