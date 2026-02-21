package com.kidsync.app.ui.screens.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CurrencyExchange
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.SettingsViewModel

/**
 * Settings screen updated for zero-knowledge architecture.
 * Shows device key fingerprint instead of email, buckets instead of families,
 * and removes TOTP-related settings.
 */
@Composable
fun SettingsScreen(
    onNavigateToDeviceList: () -> Unit,
    onNavigateToServerConfig: () -> Unit,
    onInviteCoParent: () -> Unit = {},
    onLogout: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.settings_title),
                onBack = onBack
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Device Key section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_device_key))

            SettingsItem(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.settings_device_fingerprint),
                subtitle = formatFingerprintShort(uiState.keyFingerprint),
                onClick = { }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Buckets section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_buckets))

            if (uiState.buckets.isEmpty()) {
                SettingsItem(
                    icon = Icons.Filled.Folder,
                    title = stringResource(R.string.settings_no_buckets),
                    subtitle = stringResource(R.string.settings_no_buckets_subtitle),
                    onClick = { }
                )
            } else {
                uiState.buckets.forEach { bucket ->
                    SettingsItem(
                        icon = Icons.Filled.Folder,
                        title = bucket.localName,
                        subtitle = stringResource(
                            R.string.settings_bucket_id_short,
                            bucket.bucketId.take(8)
                        ),
                        onClick = { }
                    )
                }
            }

            SettingsItem(
                icon = Icons.Filled.PersonAdd,
                title = stringResource(R.string.settings_pair_device),
                subtitle = stringResource(R.string.settings_pair_device_subtitle),
                onClick = onInviteCoParent
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Recovery section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_recovery))

            SettingsItem(
                icon = Icons.Filled.Key,
                title = stringResource(R.string.settings_recovery_phrase),
                subtitle = stringResource(R.string.settings_recovery_phrase_subtitle),
                onClick = { /* Navigate to recovery phrase re-view */ }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Server section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_server))

            SettingsItem(
                icon = Icons.Filled.Cloud,
                title = stringResource(R.string.settings_server_config),
                subtitle = uiState.serverUrl,
                onClick = onNavigateToServerConfig
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Notifications section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_notifications))

            SettingsSwitchItem(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.settings_notifications_enabled),
                checked = uiState.notificationsEnabled,
                onCheckedChange = viewModel::onNotificationsEnabledChanged
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Expenses section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_expenses))

            SettingsItem(
                icon = Icons.Filled.CurrencyExchange,
                title = stringResource(R.string.settings_default_currency),
                subtitle = uiState.defaultCurrency,
                onClick = { /* Currency picker dialog */ }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // Devices section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_devices))

            SettingsItem(
                icon = Icons.Filled.PhoneAndroid,
                title = stringResource(R.string.settings_manage_devices),
                subtitle = stringResource(R.string.settings_manage_devices_subtitle),
                onClick = onNavigateToDeviceList
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            // About section
            SettingsSectionHeader(title = stringResource(R.string.settings_section_about))

            SettingsItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_about),
                subtitle = stringResource(R.string.settings_version, "1.0.0"),
                onClick = { }
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

            Spacer(modifier = Modifier.height(16.dp))

            // Logout
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable {
                        viewModel.logout()
                        onLogout()
                    }
                    .semantics {
                        contentDescription = "Sign out and clear device session"
                    },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = stringResource(R.string.settings_logout),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

private fun formatFingerprintShort(fingerprint: String): String {
    if (fingerprint.isBlank()) return ""
    return fingerprint.take(24).chunked(4).joinToString(" ")
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { heading() }
    )
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = "$title: $subtitle" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (title.contains("Fingerprint", ignoreCase = true))
                            FontFamily.Monospace else FontFamily.Default
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics {
                contentDescription = if (checked) "$title enabled" else "$title disabled"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
