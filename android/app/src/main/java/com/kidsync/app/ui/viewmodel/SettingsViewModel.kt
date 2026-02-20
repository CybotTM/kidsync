package com.kidsync.app.ui.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Named

data class SettingsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Server config
    val serverUrl: String = "",
    val isServerConnected: Boolean? = null,
    val isTestingConnection: Boolean = false,

    // Devices
    val devices: List<Device> = emptyList(),
    val isLoadingDevices: Boolean = false,

    // Preferences
    val notificationsEnabled: Boolean = true,
    val defaultCurrency: String = "EUR",
    val defaultSplitRatio: Double = 0.5
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val familyRepository: FamilyRepository,
    private val authRepository: AuthRepository,
    @Named("prefs") private val prefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()
    }

    private fun loadPreferences() {
        _uiState.update {
            it.copy(
                serverUrl = prefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
                notificationsEnabled = prefs.getBoolean(PREF_NOTIFICATIONS, true),
                defaultCurrency = prefs.getString(PREF_CURRENCY, "EUR") ?: "EUR",
                defaultSplitRatio = prefs.getFloat(PREF_SPLIT_RATIO, 0.5f).toDouble()
            )
        }
    }

    // -- Server Config --

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, isServerConnected = null, error = null) }
    }

    fun saveServerUrl() {
        val url = _uiState.value.serverUrl.trim()
        if (url.isBlank()) {
            _uiState.update { it.copy(error = "Server URL is required") }
            return
        }
        prefs.edit().putString(PREF_SERVER_URL, url).apply()
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, isServerConnected = null, error = null) }

            try {
                // Attempt a lightweight API call to verify connectivity
                val isLoggedIn = authRepository.isLoggedIn()
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        isServerConnected = true
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isTestingConnection = false,
                        isServerConnected = false,
                        error = "Connection failed: ${e.message}"
                    )
                }
            }
        }
    }

    // -- Devices --

    fun loadDevices() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDevices = true, error = null) }

            try {
                val session = authRepository.getSession()
                if (session != null) {
                    val devices = familyRepository.getDevices(session.familyId)
                    _uiState.update {
                        it.copy(isLoadingDevices = false, devices = devices)
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoadingDevices = false, error = "Not logged in")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoadingDevices = false,
                        error = e.message ?: "Failed to load devices"
                    )
                }
            }
        }
    }

    fun revokeDevice(deviceId: UUID) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val session = authRepository.getSession()
                if (session != null) {
                    val result = familyRepository.revokeDevice(session.familyId, deviceId)
                    result.fold(
                        onSuccess = {
                            // Reload device list
                            loadDevices()
                            _uiState.update { it.copy(isLoading = false) }
                        },
                        onFailure = { error ->
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = error.message ?: "Failed to revoke device"
                                )
                            }
                        }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to revoke device"
                    )
                }
            }
        }
    }

    // -- Preferences --

    fun onNotificationsEnabledChanged(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_NOTIFICATIONS, enabled).apply()
        _uiState.update { it.copy(notificationsEnabled = enabled) }
    }

    fun onDefaultCurrencyChanged(currency: String) {
        prefs.edit().putString(PREF_CURRENCY, currency).apply()
        _uiState.update { it.copy(defaultCurrency = currency) }
    }

    fun onDefaultSplitRatioChanged(ratio: Double) {
        prefs.edit().putFloat(PREF_SPLIT_RATIO, ratio.toFloat()).apply()
        _uiState.update { it.copy(defaultSplitRatio = ratio) }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        private const val PREF_SERVER_URL = "server_url"
        private const val PREF_NOTIFICATIONS = "notifications_enabled"
        private const val PREF_CURRENCY = "default_currency"
        private const val PREF_SPLIT_RATIO = "default_split_ratio"
        private const val DEFAULT_SERVER_URL = "https://api.example.com/v1/"
    }
}
