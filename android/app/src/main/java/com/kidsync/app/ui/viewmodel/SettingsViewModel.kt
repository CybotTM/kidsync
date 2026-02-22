package com.kidsync.app.ui.viewmodel

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.model.Device
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

data class SettingsUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Device identity
    val keyFingerprint: String = "",
    val deviceId: String = "",

    // Server config
    val serverUrl: String = "",
    val isServerConnected: Boolean? = null,
    val isTestingConnection: Boolean = false,

    // Devices
    val devices: List<Device> = emptyList(),
    val isLoadingDevices: Boolean = false,

    // Buckets
    val buckets: List<BucketInfo> = emptyList(),

    // Preferences
    val notificationsEnabled: Boolean = true,
    val defaultCurrency: String = "EUR",
    val defaultSplitRatio: Double = 0.5
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val bucketRepository: BucketRepository,
    private val authRepository: AuthRepository,
    private val keyManager: KeyManager,
    @Named("prefs") private val prefs: SharedPreferences,
    // SEC2-A-09: Use encrypted prefs for server URL to match AuthRepositoryImpl
    @Named("encrypted_prefs") private val encryptedPrefs: SharedPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadDeviceInfo()
        loadPreferences()
        loadBucketList()
    }

    private fun loadDeviceInfo() {
        viewModelScope.launch {
            try {
                val fingerprint = keyManager.getSigningKeyFingerprint()
                val deviceId = keyManager.getDeviceId() ?: ""
                _uiState.update {
                    it.copy(
                        keyFingerprint = fingerprint,
                        deviceId = deviceId
                    )
                }
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    private fun loadBucketList() {
        viewModelScope.launch {
            try {
                val bucketIds = bucketRepository.getAccessibleBuckets()
                val buckets = bucketIds.map { id ->
                    BucketInfo(
                        bucketId = id,
                        localName = bucketRepository.getLocalBucketName(id) ?: "Bucket"
                    )
                }
                _uiState.update { it.copy(buckets = buckets) }
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    private fun loadPreferences() {
        _uiState.update {
            it.copy(
                // SEC2-A-09: Read server URL from encrypted prefs consistently with AuthRepositoryImpl
                serverUrl = encryptedPrefs.getString(PREF_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL,
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
        // SEC4-A-02: Require HTTPS to prevent cleartext credential transmission
        if (url.startsWith("http://")) {
            _uiState.update {
                it.copy(error = "Insecure HTTP connections are not allowed. Use HTTPS instead.")
            }
            return
        }
        if (!url.startsWith("https://")) {
            _uiState.update {
                it.copy(error = "Server URL must use HTTPS (e.g., https://api.example.com)")
            }
            return
        }
        // SEC2-A-09: Store server URL in encrypted prefs consistently with AuthRepositoryImpl
        encryptedPrefs.edit().putString(PREF_SERVER_URL, url).apply()
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, isServerConnected = null, error = null) }

            try {
                authRepository.testConnection()
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
                val currentBucket = _uiState.value.buckets.firstOrNull()
                if (currentBucket != null) {
                    val devicesResult = bucketRepository.getBucketDevices(currentBucket.bucketId)
                    val devices = devicesResult.getOrThrow()
                    _uiState.update {
                        it.copy(isLoadingDevices = false, devices = devices)
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoadingDevices = false, error = "No bucket selected")
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

    /**
     * Self-revoke: a device can only remove itself from a bucket.
     */
    fun leaveBucket(bucketId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                bucketRepository.leaveBucket(bucketId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        buckets = it.buckets.filter { b -> b.bucketId != bucketId }
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to leave bucket"
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
        // SEC6-A-10: Match AppModule.kt baseUrl to avoid mismatch between default and injected URL
        private const val DEFAULT_SERVER_URL = "https://api.kidsync.app/"
    }
}
