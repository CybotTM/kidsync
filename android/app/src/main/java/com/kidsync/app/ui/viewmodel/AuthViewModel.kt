package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.UserSession
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.TotpSetup
import com.kidsync.app.domain.usecase.auth.LoginUseCase
import com.kidsync.app.domain.usecase.auth.RecoveryUseCase
import com.kidsync.app.domain.usecase.auth.RegisterUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val isLoggedIn: Boolean = false,
    val session: UserSession? = null,
    val error: String? = null,

    // Registration fields
    val registerEmail: String = "",
    val registerPassword: String = "",
    val registerConfirmPassword: String = "",
    val registerDisplayName: String = "",

    // Login fields
    val loginEmail: String = "",
    val loginPassword: String = "",
    val loginTotpCode: String = "",

    // TOTP setup
    val totpSetup: TotpSetup? = null,
    val totpVerificationCode: String = "",
    val isTotpVerified: Boolean = false,

    // Recovery
    val recoveryWords: List<String> = emptyList(),
    val hasSavedRecoveryKey: Boolean = false,
    val recoveryInputWords: List<String> = List(24) { "" },
    val isRecoveryComplete: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val registerUseCase: RegisterUseCase,
    private val loginUseCase: LoginUseCase,
    private val recoveryUseCase: RecoveryUseCase,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkExistingSession()
    }

    private fun checkExistingSession() {
        viewModelScope.launch {
            val session = authRepository.getSession()
            if (session != null) {
                _uiState.update {
                    it.copy(isLoggedIn = true, session = session)
                }
            }
        }
    }

    // -- Registration --

    fun onRegisterEmailChanged(email: String) {
        _uiState.update { it.copy(registerEmail = email, error = null) }
    }

    fun onRegisterPasswordChanged(password: String) {
        _uiState.update { it.copy(registerPassword = password, error = null) }
    }

    fun onRegisterConfirmPasswordChanged(password: String) {
        _uiState.update { it.copy(registerConfirmPassword = password, error = null) }
    }

    fun onRegisterDisplayNameChanged(name: String) {
        _uiState.update { it.copy(registerDisplayName = name, error = null) }
    }

    fun register() {
        val state = _uiState.value

        if (state.registerEmail.isBlank()) {
            _uiState.update { it.copy(error = "Email is required") }
            return
        }
        if (state.registerPassword.length < 8) {
            _uiState.update { it.copy(error = "Password must be at least 8 characters") }
            return
        }
        if (state.registerPassword != state.registerConfirmPassword) {
            _uiState.update { it.copy(error = "Passwords do not match") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = registerUseCase(
                email = state.registerEmail.trim(),
                password = state.registerPassword,
                displayName = state.registerDisplayName.trim().ifBlank { state.registerEmail.substringBefore("@") }
            )

            result.fold(
                onSuccess = { session ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            session = session,
                            isLoggedIn = true
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Registration failed"
                        )
                    }
                }
            )
        }
    }

    // -- Login --

    fun onLoginEmailChanged(email: String) {
        _uiState.update { it.copy(loginEmail = email, error = null) }
    }

    fun onLoginPasswordChanged(password: String) {
        _uiState.update { it.copy(loginPassword = password, error = null) }
    }

    fun onLoginTotpCodeChanged(code: String) {
        _uiState.update { it.copy(loginTotpCode = code, error = null) }
    }

    fun login() {
        val state = _uiState.value

        if (state.loginEmail.isBlank()) {
            _uiState.update { it.copy(error = "Email is required") }
            return
        }
        if (state.loginPassword.isBlank()) {
            _uiState.update { it.copy(error = "Password is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = loginUseCase(
                email = state.loginEmail.trim(),
                password = state.loginPassword
            )

            result.fold(
                onSuccess = { session ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            session = session,
                            isLoggedIn = true
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Login failed"
                        )
                    }
                }
            )
        }
    }

    // -- TOTP --

    fun setupTotp() {
        val session = _uiState.value.session ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = authRepository.setupTotp(session.userId)

            result.fold(
                onSuccess = { setup ->
                    _uiState.update {
                        it.copy(isLoading = false, totpSetup = setup)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "TOTP setup failed"
                        )
                    }
                }
            )
        }
    }

    fun onTotpVerificationCodeChanged(code: String) {
        _uiState.update { it.copy(totpVerificationCode = code, error = null) }
    }

    fun verifyTotp() {
        val state = _uiState.value
        val session = state.session ?: return

        if (state.totpVerificationCode.length != 6) {
            _uiState.update { it.copy(error = "Enter a 6-digit code") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = authRepository.verifyTotp(session.userId, state.totpVerificationCode)

            result.fold(
                onSuccess = { verified ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isTotpVerified = verified,
                            error = if (!verified) "Invalid code, please try again" else null
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Verification failed"
                        )
                    }
                }
            )
        }
    }

    // -- Recovery Key --

    fun generateRecoveryKey() {
        val session = _uiState.value.session ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = recoveryUseCase.generateRecoveryKey(
                userId = session.userId,
                familyId = session.familyId
            )

            result.fold(
                onSuccess = { words ->
                    _uiState.update {
                        it.copy(isLoading = false, recoveryWords = words)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Recovery key generation failed"
                        )
                    }
                }
            )
        }
    }

    fun onRecoverySavedChecked(checked: Boolean) {
        _uiState.update { it.copy(hasSavedRecoveryKey = checked) }
    }

    // -- Recovery Restore --

    fun onRecoveryInputWordChanged(index: Int, word: String) {
        val current = _uiState.value.recoveryInputWords.toMutableList()
        if (index in current.indices) {
            current[index] = word
            _uiState.update { it.copy(recoveryInputWords = current, error = null) }
        }
    }

    fun restoreFromRecovery() {
        val state = _uiState.value
        val session = state.session

        val words = state.recoveryInputWords.map { it.trim().lowercase() }
        val filledWords = words.filter { it.isNotBlank() }

        if (filledWords.size != 24) {
            _uiState.update { it.copy(error = "Please enter all 24 recovery words") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val userId = session?.userId ?: UUID(0, 0)
            val familyId = session?.familyId ?: UUID(0, 0)

            val result = recoveryUseCase.restoreFromRecovery(
                mnemonic = words,
                userId = userId,
                familyId = familyId
            )

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isLoading = false, isRecoveryComplete = true)
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Recovery failed. Check your words and try again."
                        )
                    }
                }
            )
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
