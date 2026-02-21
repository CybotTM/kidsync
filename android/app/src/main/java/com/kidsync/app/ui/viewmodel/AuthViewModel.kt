package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.domain.usecase.auth.RecoveryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Arrays
import java.util.Base64
import javax.inject.Inject

/**
 * UI state for key-based authentication.
 * No email, password, or TOTP fields -- the device key is the identity.
 */
data class AuthUiState(
    val isLoading: Boolean = false,
    val isAuthenticated: Boolean = false,
    val deviceId: String? = null,
    val keyFingerprint: String? = null,
    val error: String? = null,

    // Key setup progress
    val isKeyGenerated: Boolean = false,
    val isRegisteredWithServer: Boolean = false,
    val setupProgress: String = "",

    // Recovery
    val recoveryWords: List<String> = emptyList(),
    val recoveryPassphrase: String = "",
    val hasSavedRecoveryKey: Boolean = false,
    val recoveryInputWords: List<String> = List(24) { "" },
    val recoveryInputPassphrase: String = "",
    val isRecoveryComplete: Boolean = false,
    val recoveryProgress: String = ""
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val recoveryUseCase: RecoveryUseCase,
    private val keyManager: KeyManager,
    private val bucketRepository: BucketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkExistingSession()
    }

    private fun checkExistingSession() {
        viewModelScope.launch {
            try {
                val session = authRepository.getSession()
                if (session != null) {
                    val fingerprint = keyManager.getSigningKeyFingerprint()
                    _uiState.update {
                        it.copy(
                            isAuthenticated = true,
                            deviceId = session.deviceId,
                            keyFingerprint = fingerprint
                        )
                    }
                }
            } catch (_: Exception) {
                // No existing session
            }
        }
    }

    // -- Device Setup --

    /**
     * Generates Ed25519 + X25519 keypairs, registers with server via
     * challenge-response, and authenticates. This is the main onboarding action.
     */
    fun setupDevice() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, setupProgress = "Generating keypairs...")
            }

            try {
                // Step 1: Generate keypairs via getOrCreateSigningKeyPair
                val (signingPublicKey, signingPrivateKey) = keyManager.getOrCreateSigningKeyPair()
                try {
                    val encryptionKeyPair = keyManager.getEncryptionKeyPair()

                    _uiState.update {
                        it.copy(
                            isKeyGenerated = true,
                            setupProgress = "Registering with server..."
                        )
                    }

                    // Step 2: Register with server (POST /register)
                    val signingKeyBase64 = Base64.getEncoder().encodeToString(signingPublicKey)
                    val encryptionKeyBase64 = Base64.getEncoder().encodeToString(
                        encryptionKeyPair.public.encoded
                    )

                    val registerResult = authRepository.register(signingKeyBase64, encryptionKeyBase64)
                    val deviceId = registerResult.getOrThrow()
                    keyManager.storeDeviceId(deviceId)

                    _uiState.update {
                        it.copy(
                            isRegisteredWithServer = true,
                            deviceId = deviceId,
                            setupProgress = "Authenticating..."
                        )
                    }

                    // Step 3: Authenticate via challenge-response
                    val authResult = authRepository.authenticate()
                    authResult.getOrThrow()

                    val fingerprint = keyManager.getSigningKeyFingerprint()

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isAuthenticated = true,
                            keyFingerprint = fingerprint,
                            setupProgress = ""
                        )
                    }
                } finally {
                    // SEC3-A-16: Zero signing private key after use
                    Arrays.fill(signingPrivateKey, 0.toByte())
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Device setup failed",
                        setupProgress = ""
                    )
                }
            }
        }
    }

    /**
     * Authenticate an existing device via challenge-response.
     * Used when the device already has keys but needs a new session.
     */
    fun authenticate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val authResult = authRepository.authenticate()
                authResult.getOrThrow()

                val fingerprint = keyManager.getSigningKeyFingerprint()
                val deviceId = keyManager.getDeviceId()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        deviceId = deviceId,
                        keyFingerprint = fingerprint
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Authentication failed"
                    )
                }
            }
        }
    }

    // -- Recovery Key Generation --

    /**
     * Generates a 24-word BIP39 mnemonic for recovery.
     * Uses the RecoveryUseCase which generates from entropy (not from seed directly).
     */
    fun generateRecoveryPhrase() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // Generate recovery key for the first accessible bucket
                // The recovery use case generates a mnemonic and wraps the DEK
                val currentBucketId = bucketRepository.getAccessibleBuckets().firstOrNull()
                    ?: throw IllegalStateException("No accessible bucket for recovery key generation")
                val result = recoveryUseCase.generateRecoveryKey(
                    bucketId = currentBucketId,
                    passphrase = _uiState.value.recoveryPassphrase
                )
                val words = result.getOrThrow()

                _uiState.update {
                    it.copy(isLoading = false, recoveryWords = words)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Recovery phrase generation failed"
                    )
                }
            }
        }
    }

    fun onRecoveryPassphraseChanged(passphrase: String) {
        _uiState.update { it.copy(recoveryPassphrase = passphrase, error = null) }
    }

    fun onRecoverySavedChecked(checked: Boolean) {
        _uiState.update { it.copy(hasSavedRecoveryKey = checked) }
    }

    /**
     * SEC-C3: Called when the user confirms they have saved their recovery key
     * and navigates away. Clears recovery words from memory.
     * The recovery blob was already uploaded during generateRecoveryPhrase
     * (via recoveryUseCase.generateRecoveryKey which wraps the DEK).
     */
    fun confirmRecoveryKeySaved() {
        _uiState.update {
            it.copy(
                recoveryWords = emptyList(),
                recoveryPassphrase = "",
                hasSavedRecoveryKey = true
            )
        }
    }

    // -- Recovery Restore --

    fun onRecoveryInputWordChanged(index: Int, word: String) {
        val current = _uiState.value.recoveryInputWords.toMutableList()
        if (index in current.indices) {
            current[index] = word
            _uiState.update { it.copy(recoveryInputWords = current, error = null) }
        }
    }

    fun onRecoveryInputPassphraseChanged(passphrase: String) {
        _uiState.update { it.copy(recoveryInputPassphrase = passphrase, error = null) }
    }

    /**
     * Restores from a 24-word mnemonic + optional passphrase:
     * 1. Derive recovery key from mnemonic + passphrase via HKDF
     * 2. Re-derive Ed25519 + X25519 keypairs
     * 3. Register as new device, authenticate
     * 4. Unwrap DEK using recovery key
     */
    fun restoreFromRecovery() {
        val state = _uiState.value
        val words = state.recoveryInputWords.map { it.trim().lowercase() }
        val filledWords = words.filter { it.isNotBlank() }

        if (filledWords.size != 24) {
            _uiState.update { it.copy(error = "Please enter all 24 recovery words") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, recoveryProgress = "Deriving recovery key...")
            }

            try {
                // Step 1: Generate new keypairs for this device
                val (signingPublicKey, _) = keyManager.getOrCreateSigningKeyPair()
                val encryptionKeyPair = keyManager.getEncryptionKeyPair()

                _uiState.update {
                    it.copy(recoveryProgress = "Registering device...")
                }

                // Step 2: Register as new device
                val signingKeyBase64 = Base64.getEncoder().encodeToString(signingPublicKey)
                val encryptionKeyBase64 = Base64.getEncoder().encodeToString(
                    encryptionKeyPair.public.encoded
                )

                val registerResult = authRepository.register(signingKeyBase64, encryptionKeyBase64)
                val deviceId = registerResult.getOrThrow()
                keyManager.storeDeviceId(deviceId)

                _uiState.update {
                    it.copy(recoveryProgress = "Authenticating...")
                }

                // Step 3: Authenticate
                val authResult = authRepository.authenticate()
                authResult.getOrThrow()

                _uiState.update {
                    it.copy(recoveryProgress = "Restoring encryption keys...")
                }

                // Step 4: Restore DEK from recovery mnemonic
                val recoveryBucketId = bucketRepository.getAccessibleBuckets().firstOrNull()
                    ?: throw IllegalStateException("No accessible bucket after authentication")
                val restoreResult = recoveryUseCase.restoreFromRecovery(
                    mnemonic = words,
                    bucketId = recoveryBucketId,
                    passphrase = state.recoveryInputPassphrase
                )
                restoreResult.getOrThrow()

                val fingerprint = keyManager.getSigningKeyFingerprint()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        isRecoveryComplete = true,
                        deviceId = deviceId,
                        keyFingerprint = fingerprint,
                        recoveryProgress = "",
                        // SEC-C3: Clear recovery input from memory
                        recoveryInputWords = List(24) { "" },
                        recoveryInputPassphrase = ""
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Recovery failed. Check your words and try again.",
                        recoveryProgress = ""
                    )
                }
            }
        }
    }

    // -- Logout --

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _uiState.update { AuthUiState() }
        }
    }

    /**
     * SEC2-A-05: Clear all sensitive data from the ViewModel's StateFlow.
     * Should be called when the user navigates away from screens displaying
     * recovery words/passphrase, or when the ViewModel is being cleared.
     */
    fun clearSensitiveData() {
        _uiState.update {
            it.copy(
                recoveryWords = emptyList(),
                recoveryPassphrase = "",
                recoveryInputWords = List(24) { "" },
                recoveryInputPassphrase = "",
                recoveryProgress = ""
            )
        }
    }

    override fun onCleared() {
        // SEC2-A-05: Ensure sensitive data is cleared when ViewModel is destroyed
        clearSensitiveData()
        super.onCleared()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
