package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.sync.p2p.P2PState
import com.kidsync.app.sync.p2p.P2PSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class P2PUiState(
    val buckets: List<BucketOption> = emptyList(),
    val selectedBucketId: String? = null,
    val permissionsGranted: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class BucketOption(
    val bucketId: String,
    val displayName: String
)

@HiltViewModel
class P2PViewModel @Inject constructor(
    private val p2pSyncManager: P2PSyncManager,
    private val bucketDao: BucketDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(P2PUiState())
    val uiState: StateFlow<P2PUiState> = _uiState.asStateFlow()

    val p2pState: StateFlow<P2PState> = p2pSyncManager.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), P2PState.Idle)

    init {
        loadBuckets()
    }

    private fun loadBuckets() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                val bucketEntities = bucketDao.getAllBuckets()
                val bucketOptions = bucketEntities.map { entity ->
                    BucketOption(
                        bucketId = entity.bucketId,
                        displayName = entity.bucketId.take(8) + "..."
                    )
                }
                _uiState.update {
                    it.copy(
                        buckets = bucketOptions,
                        selectedBucketId = bucketOptions.firstOrNull()?.bucketId,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to load buckets: ${e.message}")
                }
            }
        }
    }

    fun selectBucket(bucketId: String) {
        _uiState.update { it.copy(selectedBucketId = bucketId) }
    }

    fun setPermissionsGranted(granted: Boolean) {
        _uiState.update { it.copy(permissionsGranted = granted) }
    }

    fun startAdvertising() {
        val bucketId = _uiState.value.selectedBucketId
        if (bucketId == null) {
            _uiState.update { it.copy(error = "No bucket selected") }
            return
        }
        p2pSyncManager.startAdvertising(bucketId)
    }

    fun startDiscovery() {
        val bucketId = _uiState.value.selectedBucketId
        if (bucketId == null) {
            _uiState.update { it.copy(error = "No bucket selected") }
            return
        }
        p2pSyncManager.startDiscovery(bucketId)
    }

    fun stop() {
        p2pSyncManager.stop()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        p2pSyncManager.stop()
    }
}
