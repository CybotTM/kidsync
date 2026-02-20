package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.remote.api.ApiService
import com.kidsync.app.data.remote.dto.CreateFamilyRequest
import com.kidsync.app.data.remote.dto.JoinFamilyRequest
import com.kidsync.app.domain.model.FamilyMember
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.FamilyRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject

data class ChildEntry(
    val name: String = "",
    val dateOfBirth: LocalDate? = null
)

data class FamilyUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Family setup
    val familyName: String = "",
    val familyId: UUID? = null,
    val isFamilyCreated: Boolean = false,
    val isSolo: Boolean = false,

    // Children
    val children: List<ChildEntry> = listOf(ChildEntry()),

    // Invite
    val inviteLink: String? = null,
    val isInviteCopied: Boolean = false,

    // Join
    val joinInviteCode: String = "",
    val isJoined: Boolean = false,
    val joinedFamilyMembers: List<FamilyMember> = emptyList(),
    val fingerprint: String? = null
)

@HiltViewModel
class FamilyViewModel @Inject constructor(
    private val familyRepository: FamilyRepository,
    private val authRepository: AuthRepository,
    private val apiService: ApiService,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FamilyUiState())
    val uiState: StateFlow<FamilyUiState> = _uiState.asStateFlow()

    // -- Family Name --

    fun onFamilyNameChanged(name: String) {
        _uiState.update { it.copy(familyName = name, error = null) }
    }

    fun createFamily(solo: Boolean = false) {
        val name = _uiState.value.familyName.trim()
        if (name.isBlank()) {
            _uiState.update { it.copy(error = "Family name is required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val response = apiService.createFamily(
                    CreateFamilyRequest(name = name, solo = solo)
                )
                if (response.isSuccessful) {
                    val body = response.body()
                    val familyId = body?.familyId?.let { UUID.fromString(it) }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            familyId = familyId,
                            isFamilyCreated = true,
                            isSolo = body?.isSolo ?: solo
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to create family: ${response.message()}"
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create family"
                    )
                }
            }
        }
    }

    // -- Children --

    fun onChildNameChanged(index: Int, name: String) {
        val children = _uiState.value.children.toMutableList()
        if (index in children.indices) {
            children[index] = children[index].copy(name = name)
            _uiState.update { it.copy(children = children, error = null) }
        }
    }

    fun onChildDateOfBirthChanged(index: Int, date: LocalDate?) {
        val children = _uiState.value.children.toMutableList()
        if (index in children.indices) {
            children[index] = children[index].copy(dateOfBirth = date)
            _uiState.update { it.copy(children = children, error = null) }
        }
    }

    fun addChild() {
        val children = _uiState.value.children.toMutableList()
        children.add(ChildEntry())
        _uiState.update { it.copy(children = children) }
    }

    fun removeChild(index: Int) {
        val children = _uiState.value.children.toMutableList()
        if (index in children.indices && children.size > 1) {
            children.removeAt(index)
            _uiState.update { it.copy(children = children) }
        }
    }

    fun saveChildren() {
        val state = _uiState.value
        val validChildren = state.children.filter { it.name.isNotBlank() }

        if (validChildren.isEmpty()) {
            _uiState.update { it.copy(error = "Please add at least one child") }
            return
        }

        // Children are saved as part of the family setup flow.
        // The actual persistence happens via the sync engine operations.
        _uiState.update { it.copy(isLoading = false, error = null) }
    }

    // -- Invite --

    fun generateInviteLink() {
        val familyId = _uiState.value.familyId ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = familyRepository.createInvite(familyId)

            result.fold(
                onSuccess = { token ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            inviteLink = "kidsync://join?code=$token"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Failed to generate invite"
                        )
                    }
                }
            )
        }
    }

    fun onInviteCopied() {
        _uiState.update { it.copy(isInviteCopied = true) }
    }

    // -- Join Family --

    fun onJoinInviteCodeChanged(code: String) {
        _uiState.update { it.copy(joinInviteCode = code, error = null) }
    }

    fun joinFamily() {
        val code = _uiState.value.joinInviteCode.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(error = "Please enter an invite code") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val deviceId = keyManager.getOrCreateDeviceId()
                val result = authRepository.acceptInvite(code, deviceId)

                result.fold(
                    onSuccess = { session ->
                        // Fetch family members to display fingerprint verification
                        val members = familyRepository.getMembers(session.familyId)

                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isJoined = true,
                                familyId = session.familyId,
                                joinedFamilyMembers = members
                            )
                        }
                    },
                    onFailure = { error ->
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = error.message ?: "Failed to join family"
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to join family"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
