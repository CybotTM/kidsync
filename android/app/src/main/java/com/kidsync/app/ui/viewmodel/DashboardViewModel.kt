package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val currentBucketId: String? = null,
    val currentBucketName: String = "",
    val bucketCount: Int = 0,
    val hasCoParent: Boolean = false
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val bucketRepository: BucketRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadBucketInfo()
    }

    private fun loadBucketInfo() {
        viewModelScope.launch {
            try {
                val bucketIds = bucketRepository.getAccessibleBuckets()
                val currentBucketId = bucketIds.firstOrNull()

                if (currentBucketId != null) {
                    val localName = bucketRepository.getLocalBucketName(currentBucketId) ?: "My Bucket"
                    val devicesResult = bucketRepository.getBucketDevices(currentBucketId)
                    val devices = devicesResult.getOrDefault(emptyList())

                    _uiState.update {
                        it.copy(
                            currentBucketId = currentBucketId,
                            currentBucketName = localName,
                            bucketCount = bucketIds.size,
                            hasCoParent = devices.size > 1
                        )
                    }
                }
            } catch (_: Exception) {
                // Non-critical: dashboard still functional without bucket info
            }
        }
    }
}
