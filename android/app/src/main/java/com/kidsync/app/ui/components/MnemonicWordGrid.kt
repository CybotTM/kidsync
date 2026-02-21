package com.kidsync.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kidsync.app.R

/**
 * Display a grid of BIP39 mnemonic words (read-only mode).
 * Shows numbered word chips in a responsive grid layout.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MnemonicWordGrid(
    words: List<String>,
    modifier: Modifier = Modifier
) {
    val gridDescription = stringResource(R.string.cd_mnemonic_grid, words.size)

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = gridDescription },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4
    ) {
        words.forEachIndexed { index, word ->
            // SEC2-A-15: Use generic description without the actual word to prevent
            // mnemonic leakage via accessibility services (TalkBack, screen readers).
            val wordDescription = stringResource(R.string.cd_mnemonic_word_position, index + 1)
            Card(
                modifier = Modifier
                    .widthIn(min = 80.dp)
                    .weight(1f)
                    .semantics { contentDescription = wordDescription },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = word,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

/**
 * Input grid for entering BIP39 mnemonic words during recovery.
 * Each word has a numbered input field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MnemonicWordInputGrid(
    words: List<String>,
    onWordChanged: (index: Int, word: String) -> Unit,
    modifier: Modifier = Modifier,
    wordCount: Int = 24
) {
    val focusManager = LocalFocusManager.current
    val inputGridDescription = stringResource(R.string.cd_mnemonic_input_grid, wordCount)

    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = inputGridDescription },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        maxItemsInEachRow = 3
    ) {
        for (index in 0 until wordCount) {
            val currentWord = words.getOrElse(index) { "" }
            val isLast = index == wordCount - 1
            val fieldDescription = stringResource(R.string.cd_mnemonic_input_word, index + 1)

            Box(
                modifier = Modifier
                    .widthIn(min = 100.dp)
                    .weight(1f)
            ) {
                OutlinedTextField(
                    value = currentWord,
                    onValueChange = { newValue ->
                        onWordChanged(index, newValue.lowercase().trim())
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = fieldDescription },
                    label = {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = if (isLast) ImeAction.Done else ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Next) },
                        onDone = { focusManager.clearFocus() }
                    )
                )
            }
        }
    }
}
