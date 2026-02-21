package com.kidsync.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kidsync.app.R

/**
 * Displays a device key fingerprint in a readable, grouped hex format.
 * The fingerprint is shown in groups of 4 characters separated by spaces
 * for easy visual comparison during pairing verification.
 */
@Composable
fun KeyFingerprintDisplay(
    fingerprint: String,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.key_fingerprint_label)
) {
    val formattedFingerprint = remember(fingerprint) {
        formatFingerprint(fingerprint)
    }

    val accessibilityText = stringResource(R.string.cd_key_fingerprint, formattedFingerprint)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = accessibilityText },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Fingerprint,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = formattedFingerprint,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Start
                )
            }
        }
    }
}

/**
 * Formats a hex fingerprint string into groups of 4 characters
 * separated by spaces for readability.
 * Example: "a1b2c3d4e5f6" -> "a1b2 c3d4 e5f6"
 */
private fun formatFingerprint(fingerprint: String): String {
    return fingerprint
        .replace(" ", "")
        .lowercase()
        .chunked(4)
        .joinToString(" ")
}
