package com.kidsync.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.SyncInterval
import com.kidsync.app.ui.viewmodel.WebDavSettingsViewModel

/**
 * Settings screen for configuring WebDAV/NextCloud sync.
 *
 * Provides fields for server URL, username, password, connection testing,
 * sync enable/disable toggle, interval selection, and manual sync trigger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WebDavSettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.webdav_settings_title),
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .imePadding()
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // -- Connection Settings Section --
            Text(
                text = "Connection",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .semantics { heading() }
            )

            // Server URL
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = viewModel::onServerUrlChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "WebDAV server URL input" },
                label = { Text(stringResource(R.string.webdav_server_url)) },
                placeholder = { Text(stringResource(R.string.webdav_server_url_hint)) },
                leadingIcon = {
                    Icon(Icons.Filled.Storage, contentDescription = null)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Username
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::onUsernameChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "WebDAV username input" },
                label = { Text(stringResource(R.string.webdav_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Password with visibility toggle
            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::onPasswordChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "WebDAV password input" },
                label = { Text(stringResource(R.string.webdav_password)) },
                singleLine = true,
                visualTransformation = if (uiState.passwordVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = viewModel::togglePasswordVisibility) {
                        Icon(
                            imageVector = if (uiState.passwordVisible)
                                Icons.Filled.VisibilityOff
                            else
                                Icons.Filled.Visibility,
                            contentDescription = if (uiState.passwordVisible)
                                "Hide password" else "Show password"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() }
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Test Connection button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LoadingButton(
                    text = if (uiState.isTesting)
                        stringResource(R.string.webdav_testing)
                    else
                        stringResource(R.string.webdav_test_connection),
                    onClick = {
                        focusManager.clearFocus()
                        viewModel.testConnection()
                    },
                    isLoading = uiState.isTesting,
                    modifier = Modifier.weight(1f),
                    loadingDescription = "Testing WebDAV connection"
                )
            }

            // Connection status indicator
            uiState.isConnected?.let { connected ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics {
                        contentDescription = if (connected)
                            "WebDAV connection successful"
                        else
                            "WebDAV connection failed"
                    }
                ) {
                    Icon(
                        imageVector = if (connected) Icons.Filled.CheckCircle
                        else Icons.Filled.Error,
                        contentDescription = null,
                        tint = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (connected)
                            stringResource(R.string.webdav_connected)
                        else
                            stringResource(R.string.webdav_connection_failed, uiState.error ?: "Unknown error"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (connected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // -- Sync Settings Section --
            Text(
                text = "Sync",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .semantics { heading() }
            )

            // Enable WebDAV Sync toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .semantics {
                        contentDescription = if (uiState.isEnabled)
                            "WebDAV sync enabled"
                        else
                            "WebDAV sync disabled"
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.webdav_enable_sync),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = uiState.isEnabled,
                    onCheckedChange = viewModel::setEnabled
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sync Interval selector
            var intervalExpanded by remember { mutableStateOf(false) }
            val currentInterval = SyncInterval.fromMinutes(uiState.syncIntervalMinutes)

            ExposedDropdownMenuBox(
                expanded = intervalExpanded,
                onExpandedChange = { intervalExpanded = it }
            ) {
                OutlinedTextField(
                    value = currentInterval.label,
                    onValueChange = {},
                    readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .semantics { contentDescription = "Sync interval: ${currentInterval.label}" },
                    label = { Text(stringResource(R.string.webdav_sync_interval)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = intervalExpanded)
                    }
                )
                ExposedDropdownMenu(
                    expanded = intervalExpanded,
                    onDismissRequest = { intervalExpanded = false }
                ) {
                    SyncInterval.entries.forEach { interval ->
                        DropdownMenuItem(
                            text = { Text(interval.label) },
                            onClick = {
                                viewModel.onSyncIntervalChanged(interval)
                                intervalExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sync Now button
            LoadingButton(
                text = if (uiState.isSyncing)
                    stringResource(R.string.webdav_syncing)
                else
                    stringResource(R.string.webdav_sync_now),
                onClick = viewModel::syncNow,
                isLoading = uiState.isSyncing,
                modifier = Modifier.fillMaxWidth(),
                loadingDescription = "Syncing with WebDAV server"
            )

            // Last sync time
            uiState.lastSyncTime?.let { lastSync ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.webdav_last_sync, formatLastSyncTime(lastSync)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Format an ISO instant string into a more readable form.
 */
private fun formatLastSyncTime(isoInstant: String): String {
    return try {
        val instant = java.time.Instant.parse(isoInstant)
        val zoned = instant.atZone(java.time.ZoneId.systemDefault())
        val formatter = java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm")
        zoned.format(formatter)
    } catch (_: Exception) {
        isoInstant
    }
}
