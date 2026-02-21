package com.kidsync.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.domain.repository.AuthRepository
import com.kidsync.app.domain.repository.BucketRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject

/**
 * QR code payload for pairing.
 * Contains connection info and initiator's key fingerprint but never the DEK.
 */
@Serializable
data class QrPairingPayload(
    val v: Int = 1,
    val s: String,  // serverUrl
    val b: String,  // bucketId
    val t: String,  // inviteToken (plaintext)
    val f: String   // signingKeyFingerprint of initiator
)

data class BucketInfo(
    val bucketId: String,
    val localName: String = ""
)

data class BucketUiState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Bucket list
    val buckets: List<BucketInfo> = emptyList(),
    val currentBucket: BucketInfo? = null,

    // Bucket creation
    val localBucketName: String = "",
    val isBucketCreated: Boolean = false,

    // Pairing / Invite
    val qrPayload: String? = null,
    val inviteToken: String? = null,
    val isInviteCopied: Boolean = false,

    // Join
    val isJoined: Boolean = false,
    val isWaitingForDek: Boolean = false,
    val joinProgress: String = "",
    val peerFingerprint: String? = null
)

@HiltViewModel
class BucketViewModel @Inject constructor(
    private val bucketRepository: BucketRepository,
    private val authRepository: AuthRepository,
    private val cryptoManager: CryptoManager,
    private val keyManager: KeyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(BucketUiState())
    val uiState: StateFlow<BucketUiState> = _uiState.asStateFlow()

    // -- Bucket Name (local only) --

    fun onLocalBucketNameChanged(name: String) {
        _uiState.update { it.copy(localBucketName = name, error = null) }
    }

    /**
     * Creates an anonymous bucket on the server.
     * No name is sent to the server -- the name is stored locally only
     * (inside encrypted ops as a client-side convention).
     */
    fun createBucket() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val result = bucketRepository.createBucket()
                val bucket = result.getOrThrow()
                val bucketId = bucket.bucketId
                val localName = _uiState.value.localBucketName.trim().ifBlank { "My Bucket" }

                // Store local bucket name in encrypted preferences
                bucketRepository.storeLocalBucketName(bucketId, localName)

                // Generate DEK for this bucket
                cryptoManager.generateAndStoreDek(bucketId)

                val bucketInfo = BucketInfo(
                    bucketId = bucketId,
                    localName = localName
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isBucketCreated = true,
                        currentBucket = bucketInfo,
                        buckets = it.buckets + bucketInfo
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to create bucket"
                    )
                }
            }
        }
    }

    // -- Pairing / Invite --

    /**
     * Generates an invite token, registers its hash with the server,
     * and builds a QR payload for pairing.
     */
    fun generateInvite() {
        val currentBucket = _uiState.value.currentBucket
        if (currentBucket == null) {
            _uiState.update { it.copy(error = "No bucket selected") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // Generate a random invite token
                val inviteToken = cryptoManager.generateInviteToken()

                // Register invite with server (createInvite hashes the token internally)
                val inviteResult = bucketRepository.createInvite(
                    bucketId = currentBucket.bucketId,
                    inviteToken = inviteToken
                )
                inviteResult.getOrThrow()

                // Build QR payload
                val serverUrl = authRepository.getServerUrl()
                val fingerprint = keyManager.getEncryptionKeyFingerprint()

                val payload = QrPairingPayload(
                    v = 1,
                    s = serverUrl,
                    b = currentBucket.bucketId,
                    t = inviteToken,
                    f = fingerprint
                )

                val qrData = Json.encodeToString(payload)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        qrPayload = qrData,
                        inviteToken = inviteToken
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to generate invite"
                    )
                }
            }
        }
    }

    fun onInviteCopied() {
        _uiState.update { it.copy(isInviteCopied = true) }
    }

    // -- Join Bucket --

    /**
     * Joins a bucket by parsing the QR payload, registering the device if needed,
     * authenticating, redeeming the invite token, and waiting for the DEK.
     */
    fun joinBucket(qrData: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoading = true, error = null, joinProgress = "Parsing invite...")
            }

            try {
                // Parse QR payload
                val payload = Json.decodeFromString<QrPairingPayload>(qrData)

                _uiState.update {
                    it.copy(
                        peerFingerprint = payload.f,
                        joinProgress = "Connecting to server..."
                    )
                }

                // Configure server URL from QR
                authRepository.setServerUrl(payload.s)

                // Register device if not already registered
                if (!keyManager.hasExistingKeys()) {
                    _uiState.update { it.copy(joinProgress = "Generating device keys...") }

                    val (signingPublicKey, _) = keyManager.getOrCreateSigningKeyPair()
                    val encryptionKeyPair = keyManager.getEncryptionKeyPair()

                    _uiState.update { it.copy(joinProgress = "Registering device...") }

                    val signingKeyBase64 = Base64.getEncoder().encodeToString(signingPublicKey)
                    val encryptionKeyBase64 = Base64.getEncoder().encodeToString(
                        encryptionKeyPair.public.encoded
                    )

                    val registerResult = authRepository.register(signingKeyBase64, encryptionKeyBase64)
                    val deviceId = registerResult.getOrThrow()
                    keyManager.storeDeviceId(deviceId)
                }

                // Authenticate via challenge-response
                _uiState.update { it.copy(joinProgress = "Authenticating...") }
                val authResult = authRepository.authenticate()
                authResult.getOrThrow()

                // Redeem invite token (join bucket)
                _uiState.update { it.copy(joinProgress = "Joining bucket...") }
                val joinResult = bucketRepository.joinBucket(
                    bucketId = payload.b,
                    inviteToken = payload.t
                )
                joinResult.getOrThrow()

                // Verify initiator's key fingerprint from QR
                _uiState.update {
                    it.copy(
                        joinProgress = "Verifying peer key...",
                        peerFingerprint = payload.f
                    )
                }
                val peerDevicesResult = bucketRepository.getBucketDevices(payload.b)
                val peerDevices = peerDevicesResult.getOrThrow()
                val peerVerified = peerDevices.any { device ->
                    cryptoManager.computeKeyFingerprint(device.encryptionKey) == payload.f
                }

                if (!peerVerified) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Could not verify initiator's key fingerprint. Pairing may be compromised."
                        )
                    }
                    return@launch
                }

                // Wait for wrapped DEK from initiator
                _uiState.update {
                    it.copy(
                        isWaitingForDek = true,
                        joinProgress = "Waiting for encryption key..."
                    )
                }

                val wrappedDek = bucketRepository.waitForWrappedDek(
                    bucketId = payload.b
                )

                // Unwrap DEK with our private key
                val encryptionKeyPair = keyManager.getEncryptionKeyPair()
                cryptoManager.unwrapAndStoreDek(
                    bucketId = payload.b,
                    wrappedDek = wrappedDek.wrappedDek,
                    senderPublicKey = wrappedDek.wrappedBy,
                    privateKey = encryptionKeyPair.private
                )

                // Cross-sign the peer's key
                val peerDevice = peerDevices.first { device ->
                    cryptoManager.computeKeyFingerprint(device.encryptionKey) == payload.f
                }
                val attestation = keyManager.createKeyAttestation(
                    attestedDeviceId = peerDevice.deviceId,
                    attestedEncryptionKey = Base64.getDecoder().decode(peerDevice.encryptionKey)
                )
                bucketRepository.uploadKeyAttestation(attestation)

                // Store local name and track accessible bucket
                bucketRepository.storeLocalBucketName(payload.b, "Shared Bucket")

                val bucketInfo = BucketInfo(
                    bucketId = payload.b,
                    localName = bucketRepository.getLocalBucketName(payload.b) ?: "Shared Bucket"
                )

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isJoined = true,
                        isWaitingForDek = false,
                        currentBucket = bucketInfo,
                        buckets = it.buckets + bucketInfo,
                        joinProgress = ""
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isWaitingForDek = false,
                        error = e.message ?: "Failed to join bucket",
                        joinProgress = ""
                    )
                }
            }
        }
    }

    // -- Leave Bucket --

    /**
     * Self-revokes this device from a bucket via DELETE /buckets/{id}/devices/me.
     */
    fun leaveBucket(bucketId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                bucketRepository.leaveBucket(bucketId)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        buckets = it.buckets.filter { b -> b.bucketId != bucketId },
                        currentBucket = if (it.currentBucket?.bucketId == bucketId) null
                        else it.currentBucket
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

    // -- Load buckets --

    fun loadBuckets() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                val bucketIds = bucketRepository.getAccessibleBuckets()
                val buckets = bucketIds.map { id ->
                    BucketInfo(
                        bucketId = id,
                        localName = bucketRepository.getLocalBucketName(id) ?: "Bucket"
                    )
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        buckets = buckets,
                        currentBucket = buckets.firstOrNull()
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Failed to load buckets"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
