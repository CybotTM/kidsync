package com.kidsync.app.ui.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.repository.BucketRepository
import com.kidsync.app.sync.webdav.WebDavConfig
import com.kidsync.app.sync.webdav.WebDavSyncManager
import com.kidsync.app.sync.webdav.WebDavSyncWorker
import com.kidsync.app.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Named

/**
 * UI state for the WebDAV settings screen.
 */
data class WebDavSettingsUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val basePath: String = "kidsync",
    val isEnabled: Boolean = false,
    val isTesting: Boolean = false,
    val isSyncing: Boolean = false,
    val isConnected: Boolean? = null,
    val syncIntervalMinutes: Long = 60,
    val lastSyncTime: String? = null,
    val error: String? = null,
    val passwordVisible: Boolean = false
)

/**
 * Available sync interval options with display labels.
 */
enum class SyncInterval(val minutes: Long, val label: String) {
    FIFTEEN_MINUTES(15, "15 minutes"),
    THIRTY_MINUTES(30, "30 minutes"),
    ONE_HOUR(60, "1 hour"),
    FOUR_HOURS(240, "4 hours");

    companion object {
        fun fromMinutes(minutes: Long): SyncInterval {
            return entries.find { it.minutes == minutes } ?: ONE_HOUR
        }
    }
}

/**
 * ViewModel for the WebDAV settings screen.
 *
 * Manages WebDAV configuration, connection testing, and sync scheduling.
 * All credentials are stored in [EncryptedSharedPreferences].
 */
@HiltViewModel
class WebDavSettingsViewModel @Inject constructor(
    private val webDavSyncManager: WebDavSyncManager,
    private val bucketRepository: BucketRepository,
    @Named("encrypted_prefs") private val encryptedPrefs: SharedPreferences,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebDavSettingsUiState())
    val uiState: StateFlow<WebDavSettingsUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    /**
     * Load saved WebDAV configuration from encrypted prefs.
     */
    private fun loadConfig() {
        val serverUrl = encryptedPrefs.getString(WebDavSyncWorker.PREF_WEBDAV_SERVER_URL, "") ?: ""
        val username = encryptedPrefs.getString(WebDavSyncWorker.PREF_WEBDAV_USERNAME, "") ?: ""
        val password = encryptedPrefs.getString(WebDavSyncWorker.PREF_WEBDAV_PASSWORD, "") ?: ""
        val basePath = encryptedPrefs.getString(WebDavSyncWorker.PREF_WEBDAV_BASE_PATH, "kidsync") ?: "kidsync"
        val isEnabled = encryptedPrefs.getBoolean(WebDavSyncWorker.PREF_WEBDAV_ENABLED, false)
        val intervalMinutes = encryptedPrefs.getLong(WebDavSyncWorker.PREF_WEBDAV_SYNC_INTERVAL, 60L)
        val lastSync = encryptedPrefs.getString(WebDavSyncWorker.PREF_WEBDAV_LAST_SYNC, null)

        _uiState.update {
            it.copy(
                serverUrl = serverUrl,
                username = username,
                password = password,
                basePath = basePath,
                isEnabled = isEnabled,
                syncIntervalMinutes = intervalMinutes,
                lastSyncTime = lastSync
            )
        }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, isConnected = null, error = null) }
    }

    fun onUsernameChanged(username: String) {
        _uiState.update { it.copy(username = username, isConnected = null, error = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, isConnected = null, error = null) }
    }

    fun onBasePathChanged(basePath: String) {
        _uiState.update { it.copy(basePath = basePath, error = null) }
    }

    fun togglePasswordVisibility() {
        _uiState.update { it.copy(passwordVisible = !it.passwordVisible) }
    }

    fun onSyncIntervalChanged(interval: SyncInterval) {
        _uiState.update { it.copy(syncIntervalMinutes = interval.minutes) }
        encryptedPrefs.edit()
            .putLong(WebDavSyncWorker.PREF_WEBDAV_SYNC_INTERVAL, interval.minutes)
            .apply()

        // Reschedule if enabled
        if (_uiState.value.isEnabled) {
            WebDavSyncWorker.schedule(appContext, interval.minutes)
        }
    }

    /**
     * Validate that the URL uses HTTPS. HTTP is only allowed in debug builds.
     * Returns an error message if invalid, null if valid.
     */
    private fun validateUrl(url: String): String? {
        val trimmedUrl = url.trim().lowercase()
        if (trimmedUrl.startsWith("http://") && !BuildConfig.DEBUG) {
            return "HTTPS is required. HTTP URLs are not allowed for security reasons."
        }
        if (!trimmedUrl.startsWith("https://") && !trimmedUrl.startsWith("http://")) {
            return "URL must start with https://"
        }
        return null
    }

    /**
     * Save the current configuration to encrypted prefs.
     */
    fun saveConfig() {
        val state = _uiState.value

        if (state.serverUrl.isBlank()) {
            _uiState.update { it.copy(error = "Server URL is required") }
            return
        }

        val urlError = validateUrl(state.serverUrl)
        if (urlError != null) {
            _uiState.update { it.copy(error = urlError) }
            return
        }

        if (state.username.isBlank()) {
            _uiState.update { it.copy(error = "Username is required") }
            return
        }
        if (state.password.isBlank()) {
            _uiState.update { it.copy(error = "Password is required") }
            return
        }

        encryptedPrefs.edit()
            .putString(WebDavSyncWorker.PREF_WEBDAV_SERVER_URL, state.serverUrl.trim())
            .putString(WebDavSyncWorker.PREF_WEBDAV_USERNAME, state.username.trim())
            .putString(WebDavSyncWorker.PREF_WEBDAV_PASSWORD, state.password)
            .putString(WebDavSyncWorker.PREF_WEBDAV_BASE_PATH, state.basePath.trim().ifBlank { "kidsync" })
            .apply()

        // Also store the active bucket ID for the worker
        viewModelScope.launch {
            try {
                val bucketIds = bucketRepository.getAccessibleBuckets()
                if (bucketIds.isNotEmpty()) {
                    encryptedPrefs.edit()
                        .putString(WebDavSyncWorker.PREF_WEBDAV_BUCKET_ID, bucketIds.first())
                        .apply()
                }
            } catch (_: Exception) {
                // Non-critical
            }
        }
    }

    /**
     * Test the WebDAV connection with the current config.
     */
    fun testConnection() {
        val state = _uiState.value

        if (state.serverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Please fill in all fields first") }
            return
        }

        val urlError = validateUrl(state.serverUrl)
        if (urlError != null) {
            _uiState.update { it.copy(error = urlError) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, isConnected = null, error = null) }

            val config = WebDavConfig(
                serverUrl = state.serverUrl.trim(),
                username = state.username.trim(),
                password = state.password.toCharArray(),
                basePath = state.basePath.trim().ifBlank { "kidsync" }
            )

            webDavSyncManager.configure(config)
            val result = webDavSyncManager.testConnection()

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isTesting = false, isConnected = true, error = null)
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            isTesting = false,
                            isConnected = false,
                            error = e.message ?: "Connection failed"
                        )
                    }
                }
            )
        }
    }

    /**
     * Enable or disable WebDAV sync.
     * When enabling, saves config and schedules the periodic worker.
     * When disabling, cancels the worker.
     */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            val state = _uiState.value
            if (state.serverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
                _uiState.update { it.copy(error = "Please configure and test the connection first") }
                return
            }

            saveConfig()
            encryptedPrefs.edit().putBoolean(WebDavSyncWorker.PREF_WEBDAV_ENABLED, true).apply()
            WebDavSyncWorker.schedule(appContext, state.syncIntervalMinutes)
        } else {
            encryptedPrefs.edit().putBoolean(WebDavSyncWorker.PREF_WEBDAV_ENABLED, false).apply()
            WebDavSyncWorker.cancel(appContext)
        }

        _uiState.update { it.copy(isEnabled = enabled) }
    }

    /**
     * Trigger an immediate sync.
     */
    fun syncNow() {
        val state = _uiState.value
        if (state.serverUrl.isBlank() || state.username.isBlank() || state.password.isBlank()) {
            _uiState.update { it.copy(error = "Please configure the connection first") }
            return
        }

        saveConfig()
        _uiState.update { it.copy(isSyncing = true) }
        WebDavSyncWorker.syncNow(appContext)

        // We can't easily observe WorkManager result here, so just set a short delay
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            val lastSync = encryptedPrefs.getString(WebDavSyncWorker.PREF_WEBDAV_LAST_SYNC, null)
            _uiState.update { it.copy(isSyncing = false, lastSyncTime = lastSync) }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
