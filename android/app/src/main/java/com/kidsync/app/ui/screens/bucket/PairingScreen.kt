package com.kidsync.app.ui.screens.bucket

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kidsync.app.R
import com.kidsync.app.ui.components.LoadingButton
import com.kidsync.app.ui.components.QrCodeDisplay
import com.kidsync.app.ui.components.TopAppBarWithBack
import com.kidsync.app.ui.viewmodel.BucketViewModel

/**
 * Pairing screen -- replaces InviteCoParentScreen.
 * Generates an invite token, registers its hash with the server,
 * and displays a QR code containing:
 * { v:1, s:serverUrl, b:bucketId, t:inviteToken, f:keyFingerprint }
 *
 * NO DEK in the QR code. The DEK is exchanged via wrapped key exchange
 * after both devices are authenticated.
 */
@Composable
fun PairingScreen(
    onContinue: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BucketViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current

    // SEC-C1, SEC3-A-19: Prevent screenshots while invite token is visible
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.isInviteCopied) {
        if (uiState.isInviteCopied) {
            snackbarHostState.showSnackbar("Invite data copied to clipboard")
            // SEC-A-03: Clear clipboard after 60 seconds to prevent token leakage
            kotlinx.coroutines.delay(60_000L)
            clipboardManager.setText(AnnotatedString(""))
        }
    }

    Scaffold(
        topBar = {
            TopAppBarWithBack(
                title = stringResource(R.string.pairing_title),
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
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.pairing_heading),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.pairing_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            if (uiState.qrPayload == null) {
                // Generate invite button
                LoadingButton(
                    text = stringResource(R.string.pairing_generate_button),
                    onClick = { viewModel.generateInvite() },
                    isLoading = uiState.isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    loadingDescription = stringResource(R.string.cd_generating_invite)
                )
            } else {
                // Show QR code
                QrCodeDisplay(
                    data = uiState.qrPayload!!,
                    size = 240.dp,
                    description = stringResource(R.string.cd_pairing_qr_code)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.pairing_qr_instruction),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Copy and share buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly
                ) {
                    OutlinedButton(
                        onClick = {
                            uiState.qrPayload?.let {
                                clipboardManager.setText(AnnotatedString(it))
                                viewModel.onInviteCopied()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Copy invite data to clipboard"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.pairing_copy))
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    OutlinedButton(
                        onClick = { /* Share intent would go here */ },
                        modifier = Modifier
                            .weight(1f)
                            .semantics {
                                contentDescription = "Share invite data"
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.pairing_share))
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            TextButton(
                onClick = onContinue,
                modifier = Modifier.semantics {
                    contentDescription = "Skip pairing and continue to dashboard"
                }
            ) {
                Text(
                    text = stringResource(R.string.pairing_skip),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
