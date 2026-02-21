package com.kidsync.app.ui.screens.bucket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.kidsync.app.ui.components.QrCodeScanner
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.BucketViewModel

/**
 * Join bucket screen -- replaces JoinFamilyScreen.
 * Uses CameraX + ML Kit to scan the pairing QR code, then:
 * 1. Parses the QR payload
 * 2. Registers device if not already registered
 * 3. Authenticates with challenge-response
 * 4. Redeems invite token via POST /buckets/{id}/join
 * 5. Verifies initiator's key fingerprint from QR
 * 6. Waits for DEK to be wrapped and pushed
 * 7. Navigates to dashboard once DEK is received
 */
@Composable
fun JoinBucketScreen(
    onJoined: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BucketViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.isJoined) {
        if (uiState.isJoined) {
            onJoined()
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
                title = stringResource(R.string.join_bucket_title),
                onBack = onBack
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!uiState.isLoading && !uiState.isWaitingForDek && uiState.joinProgress.isBlank()) {
                // Phase 1: QR Scanner
                Text(
                    text = stringResource(R.string.join_bucket_heading),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                        .semantics { heading() }
                )

                Text(
                    text = stringResource(R.string.join_bucket_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // QR code scanner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    QrCodeScanner(
                        onQrScanned = { qrData ->
                            viewModel.joinBucket(qrData)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            } else {
                // Phase 2: Processing / Waiting for DEK
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (uiState.isWaitingForDek) {
                        // Waiting for DEK from initiator
                        Icon(
                            imageVector = Icons.Filled.HourglassTop,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = stringResource(R.string.join_bucket_waiting_title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.join_bucket_waiting_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Waiting for encryption key from co-parent"
                            }
                        )
                    } else {
                        // Processing steps
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(48.dp)
                                .semantics {
                                    contentDescription = "Joining bucket"
                                }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = uiState.joinProgress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Show peer fingerprint for verification
                    uiState.peerFingerprint?.let { fingerprint ->
                        Spacer(modifier = Modifier.height(24.dp))

                        KeyFingerprintDisplay(
                            fingerprint = fingerprint,
                            label = stringResource(R.string.join_bucket_peer_fingerprint),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = stringResource(R.string.join_bucket_verify_instruction),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
