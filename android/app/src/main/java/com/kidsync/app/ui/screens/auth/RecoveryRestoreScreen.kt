package com.kidsync.app.ui.screens.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.MnemonicWordInputGrid
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.AuthViewModel

/**
 * Recovery restore screen for zero-knowledge architecture.
 * - 24-word mnemonic input
 * - Optional passphrase input (BIP39 25th word)
 * - Derives recovery key with passphrase if provided
 * - Downloads and decrypts recovery blob
 * - Re-derives keypairs from recovered seed
 * - Registers as new device, authenticates
 * - Navigates to dashboard after restore
 */
@Composable
fun RecoveryRestoreScreen(
    onRestored: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var passphraseVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.isRecoveryComplete) {
        if (uiState.isRecoveryComplete) {
            onRestored()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    val filledCount = uiState.recoveryInputWords.count { it.isNotBlank() }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.recovery_restore_title),
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.recovery_restore_heading),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.recovery_restore_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.recovery_restore_progress, filledCount, 24),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            MnemonicWordInputGrid(
                words = uiState.recoveryInputWords,
                onWordChanged = viewModel::onRecoveryInputWordChanged,
                wordCount = 24,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Optional passphrase input
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.recovery_restore_passphrase_heading),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = stringResource(R.string.recovery_restore_passphrase_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = uiState.recoveryInputPassphrase,
                        onValueChange = viewModel::onRecoveryInputPassphraseChanged,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Optional recovery passphrase"
                            },
                        label = { Text(stringResource(R.string.recovery_passphrase_label)) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { passphraseVisible = !passphraseVisible }
                            ) {
                                Icon(
                                    imageVector = if (passphraseVisible) Icons.Filled.VisibilityOff
                                    else Icons.Filled.Visibility,
                                    contentDescription = if (passphraseVisible)
                                        stringResource(R.string.cd_hide_passphrase)
                                    else stringResource(R.string.cd_show_passphrase)
                                )
                            }
                        },
                        visualTransformation = if (passphraseVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Progress indicator during restore
            if (uiState.isLoading && uiState.recoveryProgress.isNotBlank()) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics {
                        contentDescription = "Restoring account"
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.recoveryProgress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            LoadingButton(
                text = stringResource(R.string.recovery_restore_button),
                onClick = { viewModel.restoreFromRecovery() },
                isLoading = uiState.isLoading,
                enabled = filledCount == 24,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                loadingDescription = stringResource(R.string.cd_restoring_account)
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
