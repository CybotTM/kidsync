package com.kidsync.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SportsBasketball
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kidsync.app.R
import com.kidsync.app.domain.model.ExpenseCategory

/**
 * A 4x2 grid of expense category icons with labels.
 * The selected category is highlighted with the primary color.
 */
@Composable
fun CategoryIconGrid(
    selectedCategory: ExpenseCategory?,
    onCategorySelected: (ExpenseCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(ExpenseCategory.entries.toList()) { category ->
            CategoryItem(
                category = category,
                isSelected = category == selectedCategory,
                onClick = { onCategorySelected(category) }
            )
        }
    }
}

@Composable
private fun CategoryItem(
    category: ExpenseCategory,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = categoryIcon(category)
    val label = categoryLabel(category)
    val selectedLabel = if (isSelected) {
        stringResource(R.string.cd_category_selected, label)
    } else {
        label
    }

    Card(
        modifier = modifier
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = selectedLabel
                role = Role.RadioButton
                selected = isSelected
            },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = if (isSelected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun categoryIcon(category: ExpenseCategory): ImageVector {
    return when (category) {
        ExpenseCategory.MEDICAL -> Icons.Filled.LocalHospital
        ExpenseCategory.EDUCATION -> Icons.Filled.School
        ExpenseCategory.CLOTHING -> Icons.Filled.ChildCare
        ExpenseCategory.ACTIVITIES -> Icons.Filled.SportsBasketball
        ExpenseCategory.TRANSPORT -> Icons.Filled.DirectionsCar
        ExpenseCategory.CHILDCARE -> Icons.Filled.Home
        ExpenseCategory.OTHER -> Icons.Filled.MoreHoriz
    }
}

@Composable
fun categoryLabel(category: ExpenseCategory): String {
    return when (category) {
        ExpenseCategory.MEDICAL -> stringResource(R.string.expense_category_medical)
        ExpenseCategory.EDUCATION -> stringResource(R.string.expense_category_education)
        ExpenseCategory.CLOTHING -> stringResource(R.string.expense_category_clothing)
        ExpenseCategory.ACTIVITIES -> stringResource(R.string.expense_category_activities)
        ExpenseCategory.TRANSPORT -> stringResource(R.string.expense_category_transport)
        ExpenseCategory.CHILDCARE -> stringResource(R.string.expense_category_childcare)
        ExpenseCategory.OTHER -> stringResource(R.string.expense_category_other)
    }
}
