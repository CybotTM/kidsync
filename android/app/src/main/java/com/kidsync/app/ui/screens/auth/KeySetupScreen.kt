package com.kidsync.app.ui.screens.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import android.app.Activity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.ui.components.KeyFingerprintDisplay
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.AuthViewModel

/**
 * Key setup screen shown after "Set Up New" on WelcomeScreen.
 * Generates Ed25519 + X25519 keypairs, registers with the server
 * automatically, shows the device key fingerprint, and navigates
 * to RecoveryKeyScreen to display the mnemonic.
 */
@Composable
fun KeySetupScreen(
    onKeysReady: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // SEC5-A-11: Prevent screenshots/screen recording while key fingerprint is visible
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    // Auto-start device setup on first composition
    LaunchedEffect(Unit) {
        if (!uiState.isAuthenticated && !uiState.isLoading) {
            viewModel.setupDevice()
        }
    }

    // Navigate when setup is complete
    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated && uiState.keyFingerprint != null) {
            onKeysReady()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.key_setup_title),
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
            Spacer(modifier = Modifier.height(32.dp))

            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.key_setup_heading),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.key_setup_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.isLoading) {
                // Progress display
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = "Setting up device keys"
                            }
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = uiState.setupProgress,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Progress steps
                    SetupProgressStep(
                        label = stringResource(R.string.key_setup_step_generate),
                        isComplete = uiState.isKeyGenerated
                    )
                    SetupProgressStep(
                        label = stringResource(R.string.key_setup_step_register),
                        isComplete = uiState.isRegisteredWithServer
                    )
                    SetupProgressStep(
                        label = stringResource(R.string.key_setup_step_auth),
                        isComplete = uiState.isAuthenticated
                    )
                }
            } else if (uiState.keyFingerprint != null) {
                // Show fingerprint after setup
                KeyFingerprintDisplay(
                    fingerprint = uiState.keyFingerprint!!,
                    label = stringResource(R.string.key_setup_your_fingerprint),
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (uiState.error != null) {
                // Retry button on error
                LoadingButton(
                    text = stringResource(R.string.key_setup_retry),
                    onClick = { viewModel.setupDevice() },
                    isLoading = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupProgressStep(
    label: String,
    isComplete: Boolean,
    modifier: Modifier = Modifier
) {
    val icon = if (isComplete) Icons.Filled.Key else Icons.Filled.Key
    val color = if (isComplete) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }

    Text(
        text = if (isComplete) "$label ..." else label,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        modifier = modifier.padding(vertical = 2.dp)
    )
}
