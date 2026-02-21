package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.usecase.auth.RecoveryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val keyManager: KeyManager
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
                // Step 1: Generate keypairs
                val seed = keyManager.generateSeed()
                val signingKeyPair = keyManager.deriveSigningKeyPair(seed)
                val encryptionKeyPair = keyManager.deriveEncryptionKeyPair(seed)
                keyManager.storeSeed(seed)

                _uiState.update {
                    it.copy(
                        isKeyGenerated = true,
                        setupProgress = "Registering with server..."
                    )
                }

                // Step 2: Register with server (POST /register)
                val deviceId = authRepository.registerDevice(
                    signingKey = keyManager.encodePublicKey(signingKeyPair.public),
                    encryptionKey = keyManager.encodePublicKey(encryptionKeyPair.public)
                )

                _uiState.update {
                    it.copy(
                        isRegisteredWithServer = true,
                        deviceId = deviceId,
                        setupProgress = "Authenticating..."
                    )
                }

                // Step 3: Authenticate via challenge-response
                authRepository.authenticateWithChallenge(signingKeyPair)

                val fingerprint = keyManager.getSigningKeyFingerprint()

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isAuthenticated = true,
                        keyFingerprint = fingerprint,
                        setupProgress = ""
                    )
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
                val signingKeyPair = keyManager.getSigningKeyPair()
                authRepository.authenticateWithChallenge(signingKeyPair)

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
     * Generates a 24-word BIP39 mnemonic from the device seed.
     */
    fun generateRecoveryPhrase() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val words = recoveryUseCase.generateMnemonicFromSeed(
                    seed = keyManager.getSeed()
                )

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
     * and navigates away. Clears recovery words from memory and uploads
     * the encrypted recovery blob.
     */
    fun confirmRecoveryKeySaved() {
        viewModelScope.launch {
            try {
                val passphrase = _uiState.value.recoveryPassphrase
                recoveryUseCase.uploadRecoveryBlob(
                    seed = keyManager.getSeed(),
                    passphrase = passphrase
                )
            } catch (_: Exception) {
                // Recovery blob upload failure is non-fatal during onboarding;
                // user can re-upload later from settings
            }

            _uiState.update {
                it.copy(
                    recoveryWords = emptyList(),
                    recoveryPassphrase = "",
                    hasSavedRecoveryKey = true
                )
            }
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
     * 2. Download and decrypt recovery blob from server
     * 3. Re-derive Ed25519 + X25519 keypairs from recovered seed
     * 4. Register as new device, authenticate
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
                // Step 1: Derive seed from mnemonic
                val seed = recoveryUseCase.deriveSeedFromMnemonic(
                    mnemonic = words,
                    passphrase = state.recoveryInputPassphrase
                )

                _uiState.update {
                    it.copy(recoveryProgress = "Downloading recovery data...")
                }

                // Step 2: Download and decrypt recovery blob
                recoveryUseCase.downloadAndDecryptRecoveryBlob(
                    seed = seed,
                    passphrase = state.recoveryInputPassphrase
                )

                _uiState.update {
                    it.copy(recoveryProgress = "Re-deriving keypairs...")
                }

                // Step 3: Re-derive keypairs and store seed
                val signingKeyPair = keyManager.deriveSigningKeyPair(seed)
                val encryptionKeyPair = keyManager.deriveEncryptionKeyPair(seed)
                keyManager.storeSeed(seed)

                _uiState.update {
                    it.copy(recoveryProgress = "Registering device...")
                }

                // Step 4: Register as new device
                val deviceId = authRepository.registerDevice(
                    signingKey = keyManager.encodePublicKey(signingKeyPair.public),
                    encryptionKey = keyManager.encodePublicKey(encryptionKeyPair.public)
                )

                _uiState.update {
                    it.copy(recoveryProgress = "Authenticating...")
                }

                // Step 5: Authenticate
                authRepository.authenticateWithChallenge(signingKeyPair)

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

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
