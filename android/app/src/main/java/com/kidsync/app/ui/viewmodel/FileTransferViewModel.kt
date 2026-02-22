package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.data.local.dao.BucketDao
import com.kidsync.app.data.local.entity.BucketEntity
import com.kidsync.app.sync.filetransfer.ExportManifest
import com.kidsync.app.sync.filetransfer.FileTransferManager
import com.kidsync.app.sync.filetransfer.ImportResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

data class FileTransferUiState(
    val buckets: List<BucketEntity> = emptyList(),
    val selectedBucketId: String? = null,
    val isExporting: Boolean = false,
    val isImporting: Boolean = false,
    val exportResult: ExportManifest? = null,
    val importResult: ImportResult? = null,
    val error: String? = null
)

@HiltViewModel
class FileTransferViewModel @Inject constructor(
    private val fileTransferManager: FileTransferManager,
    private val bucketDao: BucketDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileTransferUiState())
    val uiState: StateFlow<FileTransferUiState> = _uiState.asStateFlow()

    init {
        loadBuckets()
    }

    private fun loadBuckets() {
        viewModelScope.launch {
            try {
                val buckets = bucketDao.getAllBuckets()
                _uiState.update {
                    it.copy(
                        buckets = buckets,
                        selectedBucketId = buckets.firstOrNull()?.bucketId
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Failed to load buckets: ${e.message}") }
            }
        }
    }

    fun selectBucket(bucketId: String) {
        _uiState.update { it.copy(selectedBucketId = bucketId, error = null) }
    }

    fun exportBucket(outputStream: OutputStream) {
        val bucketId = _uiState.value.selectedBucketId ?: run {
            _uiState.update { it.copy(error = "No bucket selected") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(isExporting = true, exportResult = null, importResult = null, error = null)
            }

            val result = fileTransferManager.exportBucket(bucketId, outputStream)
            result.fold(
                onSuccess = { manifest ->
                    _uiState.update {
                        it.copy(isExporting = false, exportResult = manifest)
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isExporting = false, error = e.message ?: "Export failed")
                    }
                }
            )
        }
    }

    fun importBundle(inputStream: InputStream) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isImporting = true, exportResult = null, importResult = null, error = null)
            }

            val result = fileTransferManager.importBundle(inputStream)
            result.fold(
                onSuccess = { importResult ->
                    _uiState.update {
                        it.copy(isImporting = false, importResult = importResult)
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(isImporting = false, error = e.message ?: "Import failed")
                    }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearResults() {
        _uiState.update { it.copy(exportResult = null, importResult = null, error = null) }
    }
}
