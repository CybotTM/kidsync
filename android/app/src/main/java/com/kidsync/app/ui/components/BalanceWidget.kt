package com.kidsync.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kidsync.app.R
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Running balance display widget for the expense list.
 *
 * Color coding:
 * - Green (secondary): the other parent owes you money
 * - Red (error): you owe the other parent
 * - Gray (neutral): settled / zero balance
 *
 * The [balanceCents] is positive when the other parent owes you,
 * negative when you owe them, and zero when settled.
 */
@Composable
fun BalanceWidget(
    balanceCents: Long,
    currencyCode: String,
    modifier: Modifier = Modifier
) {
    val formattedAmount = formatCurrency(
        amountCents = kotlin.math.abs(balanceCents),
        currencyCode = currencyCode
    )

    val (label, accentColor, containerColor) = when {
        balanceCents > 0 -> Triple(
            stringResource(R.string.expense_balance_they_owe, formattedAmount),
            MaterialTheme.colorScheme.secondary,
            MaterialTheme.colorScheme.secondaryContainer
        )
        balanceCents < 0 -> Triple(
            stringResource(R.string.expense_balance_you_owe, formattedAmount),
            MaterialTheme.colorScheme.error,
            MaterialTheme.colorScheme.errorContainer
        )
        else -> Triple(
            stringResource(R.string.expense_balance_settled),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant
        )
    }

    val accessibilityDescription = when {
        balanceCents > 0 -> stringResource(R.string.cd_balance_they_owe, formattedAmount)
        balanceCents < 0 -> stringResource(R.string.cd_balance_you_owe, formattedAmount)
        else -> stringResource(R.string.cd_balance_settled)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = accessibilityDescription
            },
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Balance,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = accentColor
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = stringResource(R.string.expense_balance_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor.copy(alpha = 0.8f)
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.semantics { heading() }
                )
            }
        }
    }
}

/**
 * Formats an amount in cents to a locale-aware currency string.
 */
fun formatCurrency(amountCents: Long, currencyCode: String): String {
    val formatter = NumberFormat.getCurrencyInstance(Locale.getDefault())
    try {
        formatter.currency = Currency.getInstance(currencyCode)
    } catch (_: IllegalArgumentException) {
        // Fall back to default currency if code is invalid
    }
    return formatter.format(amountCents / 100.0)
}
