package com.kidsync.app.sync.p2p

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.kidsync.app.crypto.CryptoManager
import com.kidsync.app.crypto.KeyManager
import com.kidsync.app.data.local.dao.OpLogDao
import com.kidsync.app.data.local.entity.OpLogEntryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages P2P sync via Google Nearby Connections API.
 *
 * Protocol:
 * 1. Device A advertises, Device B discovers
 * 2. Both exchange Handshake messages containing HMAC(key=DEK, msg=bucketId)
 * 3. If HMACs match, both have the same DEK (same bucket membership)
 * 4. Devices exchange encrypted OpLog entries the peer is missing
 * 5. SyncComplete message summarizes transfer
 *
 * All payloads transferred are already encrypted; the P2P layer does not
 * perform any encryption/decryption of the actual data.
 */
class P2PSyncManager(
    private val context: Context,
    private val opLogDao: OpLogDao,
    private val keyManager: KeyManager,
    private val cryptoManager: CryptoManager
) {
    private val connectionsClient: ConnectionsClient by lazy {
        Nearby.getConnectionsClient(context)
    }

    private val _state = MutableStateFlow<P2PState>(P2PState.Idle)
    val state: StateFlow<P2PState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private var currentBucketId: String? = null
    private var connectedEndpointId: String? = null
    private var peerLastSequence: Long = -1
    private var opsSentCount: Long = 0
    private var opsReceivedCount: Long = 0

    companion object {
        private const val TAG = "P2PSyncManager"
        const val SERVICE_ID = "com.kidsync.app.p2p"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val BATCH_SIZE = 50
        private const val HANDSHAKE_TIMESTAMP_TOLERANCE_MS = 30_000L // 30 seconds

        /**
         * Compute HMAC-SHA256 and return base64-encoded result.
         */
        fun hmacSha256(key: ByteArray, data: ByteArray): String {
            val mac = Mac.getInstance(HMAC_ALGORITHM)
            mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
            val result = mac.doFinal(data)
            return Base64.getEncoder().encodeToString(result)
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Advertise this device for P2P sync on the given bucket.
     * The other device should call [startDiscovery] with the same bucketId.
     */
    fun startAdvertising(bucketId: String) {
        currentBucketId = bucketId
        resetSyncCounters()
        _state.value = P2PState.Advertising

        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        connectionsClient.startAdvertising(
            bucketId.take(8),
            SERVICE_ID,
            connectionLifecycleCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Advertising started for bucket ${bucketId.take(8)}")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start advertising", e)
            _state.value = P2PState.Error("Failed to start advertising: ${e.message}")
        }
    }

    /**
     * Discover nearby advertising devices for the given bucket.
     */
    fun startDiscovery(bucketId: String) {
        currentBucketId = bucketId
        resetSyncCounters()
        _state.value = P2PState.Discovering

        val options = DiscoveryOptions.Builder()
            .setStrategy(Strategy.P2P_POINT_TO_POINT)
            .build()

        connectionsClient.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            options
        ).addOnSuccessListener {
            Log.d(TAG, "Discovery started for bucket ${bucketId.take(8)}")
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to start discovery", e)
            _state.value = P2PState.Error("Failed to start discovery: ${e.message}")
        }
    }

    /**
     * Stop all advertising, discovery, and disconnect any active connection.
     */
    fun stop() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectedEndpointId?.let { connectionsClient.disconnectFromEndpoint(it) }
        connectedEndpointId = null
        currentBucketId = null
        _state.value = P2PState.Idle
    }

    // ── HMAC Computation ─────────────────────────────────────────────────────

    /**
     * Compute HMAC-SHA256(key=DEK, message=bucketId+timestamp) to prove bucket membership.
     * Both devices must share the same DEK to produce matching HMACs.
     * Including the timestamp prevents replay attacks.
     *
     * Visible for testing via [computeHandshakeHmac].
     */
    suspend fun computeHandshakeHmac(bucketId: String, timestamp: Long): String {
        val epoch = keyManager.getCurrentEpoch(bucketId)
        val dek = keyManager.getDek(bucketId, epoch)
            ?: throw IllegalStateException("No DEK available for bucket $bucketId epoch $epoch")

        return try {
            val message = (bucketId + timestamp.toString()).toByteArray(Charsets.UTF_8)
            hmacSha256(dek, message)
        } finally {
            // DEK is managed by KeyManager; we don't zero it here
        }
    }

    /**
     * Determine which ops to send to a peer based on their last known sequence.
     * Returns ops with globalSequence > peerLastSeq for the given bucket.
     *
     * Visible for testing.
     */
    suspend fun getOpsToSend(bucketId: String, peerLastSeq: Long): List<OpLogEntryEntity> {
        return opLogDao.getOpsAfterSequence(bucketId, peerLastSeq, limit = Int.MAX_VALUE)
    }

    /**
     * Convert an OpLogEntryEntity to the P2P transfer format.
     *
     * Visible for testing.
     */
    fun entityToP2POp(entity: OpLogEntryEntity): P2POp {
        return P2POp(
            globalSequence = entity.globalSequence,
            bucketId = entity.bucketId,
            deviceId = entity.deviceId,
            deviceSequence = entity.deviceSequence,
            keyEpoch = entity.keyEpoch,
            encryptedPayload = entity.encryptedPayload,
            devicePrevHash = entity.devicePrevHash,
            currentHash = entity.currentHash,
            serverTimestamp = entity.serverTimestamp
        )
    }

    /**
     * Convert a received P2POp to an OpLogEntryEntity for local storage.
     *
     * Visible for testing.
     */
    fun p2pOpToEntity(op: P2POp): OpLogEntryEntity {
        return OpLogEntryEntity(
            globalSequence = op.globalSequence,
            bucketId = op.bucketId,
            deviceId = op.deviceId,
            deviceSequence = op.deviceSequence,
            keyEpoch = op.keyEpoch,
            encryptedPayload = op.encryptedPayload,
            devicePrevHash = op.devicePrevHash,
            currentHash = op.currentHash,
            serverTimestamp = op.serverTimestamp,
            isPending = false
        )
    }

    /**
     * Serialize a P2PMessage to JSON bytes for transmission.
     *
     * Visible for testing.
     */
    fun serializeMessage(message: P2PMessage): ByteArray {
        return json.encodeToString(P2PMessage.serializer(), message).toByteArray(Charsets.UTF_8)
    }

    /**
     * Deserialize a P2PMessage from JSON bytes.
     *
     * Visible for testing.
     */
    fun deserializeMessage(bytes: ByteArray): P2PMessage {
        return json.decodeFromString(P2PMessage.serializer(), String(bytes, Charsets.UTF_8))
    }

    // ── Connection Lifecycle ─────────────────────────────────────────────────

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Log.d(TAG, "Connection initiated with ${info.endpointName}")
            _state.value = P2PState.Connecting(info.endpointName)
            // Accept the connection; HMAC verification happens after connection is established
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            when (result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    Log.d(TAG, "Connected to $endpointId")
                    connectedEndpointId = endpointId
                    connectionsClient.stopAdvertising()
                    connectionsClient.stopDiscovery()

                    val endpointName = (_state.value as? P2PState.Connecting)?.endpointName ?: endpointId
                    _state.value = P2PState.Connected(endpointName)

                    // Send handshake
                    scope.launch { sendHandshake(endpointId) }
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    Log.w(TAG, "Connection rejected by $endpointId")
                    _state.value = P2PState.Error("Connection rejected by peer")
                }

                else -> {
                    Log.e(TAG, "Connection failed: ${result.status}")
                    _state.value = P2PState.Error("Connection failed: ${result.status.statusMessage}")
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            Log.d(TAG, "Disconnected from $endpointId")
            connectedEndpointId = null
            val currentState = _state.value
            // Only go to Idle if we're not already in Completed or Error state
            if (currentState !is P2PState.Completed && currentState !is P2PState.Error) {
                _state.value = P2PState.Idle
            }
        }
    }

    // ── Endpoint Discovery ───────────────────────────────────────────────────

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            Log.d(TAG, "Endpoint found: ${info.endpointName} ($endpointId)")
            _state.value = P2PState.Connecting(info.endpointName)
            connectionsClient.requestConnection(
                currentBucketId?.take(8) ?: "kidsync",
                endpointId,
                connectionLifecycleCallback
            ).addOnFailureListener { e ->
                Log.e(TAG, "Failed to request connection", e)
                _state.value = P2PState.Error("Failed to connect: ${e.message}")
            }
        }

        override fun onEndpointLost(endpointId: String) {
            Log.d(TAG, "Endpoint lost: $endpointId")
        }
    }

    // ── Payload Handling ─────────────────────────────────────────────────────

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch {
                handleReceivedMessage(endpointId, bytes)
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Byte payloads are transferred atomically; no progress tracking needed
        }
    }

    // ── Message Handling ─────────────────────────────────────────────────────

    private suspend fun sendHandshake(endpointId: String) {
        val bucketId = currentBucketId ?: return
        try {
            val deviceId = keyManager.getDeviceId() ?: "unknown"
            val timestamp = System.currentTimeMillis()
            val hmac = computeHandshakeHmac(bucketId, timestamp)
            val allOps = opLogDao.getAllOpsForBucket(bucketId)
            val lastSeq = allOps.maxOfOrNull { it.globalSequence } ?: 0L

            val handshake = P2PMessage.Handshake(
                deviceId = deviceId,
                bucketId = bucketId,
                hmac = hmac,
                lastSequence = lastSeq,
                timestamp = timestamp
            )
            sendMessage(endpointId, handshake)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send handshake", e)
            _state.value = P2PState.Error("Handshake failed: ${e.message}")
            connectionsClient.disconnectFromEndpoint(endpointId)
        }
    }

    private suspend fun handleReceivedMessage(endpointId: String, bytes: ByteArray) {
        try {
            val message = deserializeMessage(bytes)
            when (message) {
                is P2PMessage.Handshake -> handleHandshake(endpointId, message)
                is P2PMessage.OpsPayload -> handleOpsPayload(message)
                is P2PMessage.SyncComplete -> handleSyncComplete(message)
                is P2PMessage.Error -> handleError(endpointId, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle received message", e)
            sendMessage(endpointId, P2PMessage.Error("PARSE_ERROR", "Failed to parse message: ${e.message}"))
            _state.value = P2PState.Error("Protocol error: ${e.message}")
        }
    }

    private suspend fun handleHandshake(endpointId: String, handshake: P2PMessage.Handshake) {
        val bucketId = currentBucketId
        if (bucketId == null) {
            sendMessage(endpointId, P2PMessage.Error("NO_BUCKET", "No bucket selected"))
            _state.value = P2PState.Error("No bucket selected")
            return
        }

        // Verify bucket IDs match
        if (handshake.bucketId != bucketId) {
            sendMessage(endpointId, P2PMessage.Error("BUCKET_MISMATCH", "Bucket IDs do not match"))
            _state.value = P2PState.Error("Bucket mismatch")
            connectionsClient.disconnectFromEndpoint(endpointId)
            return
        }

        // Verify timestamp is within 30-second window (replay attack protection)
        val now = System.currentTimeMillis()
        val timestampAge = Math.abs(now - handshake.timestamp)
        if (timestampAge > HANDSHAKE_TIMESTAMP_TOLERANCE_MS) {
            sendMessage(endpointId, P2PMessage.Error("STALE_HANDSHAKE", "Handshake timestamp too old"))
            _state.value = P2PState.Error("Handshake rejected - stale timestamp (${timestampAge}ms old)")
            connectionsClient.disconnectFromEndpoint(endpointId)
            return
        }

        // Verify HMAC: proves the peer has the same DEK
        try {
            val expectedHmac = computeHandshakeHmac(bucketId, handshake.timestamp)
            if (handshake.hmac != expectedHmac) {
                sendMessage(endpointId, P2PMessage.Error("HMAC_MISMATCH", "HMAC verification failed"))
                _state.value = P2PState.Error("HMAC verification failed - device not in same bucket")
                connectionsClient.disconnectFromEndpoint(endpointId)
                return
            }
        } catch (e: Exception) {
            sendMessage(endpointId, P2PMessage.Error("HMAC_ERROR", "HMAC computation failed: ${e.message}"))
            _state.value = P2PState.Error("HMAC verification error: ${e.message}")
            connectionsClient.disconnectFromEndpoint(endpointId)
            return
        }

        // HMAC verified - proceed with sync
        peerLastSequence = handshake.lastSequence
        val endpointName = (_state.value as? P2PState.Connected)?.endpointName
            ?: (_state.value as? P2PState.Connecting)?.endpointName
            ?: endpointId
        _state.value = P2PState.Syncing(0f, endpointName)

        performSync(endpointId, bucketId, handshake.lastSequence)
    }

    private suspend fun handleOpsPayload(payload: P2PMessage.OpsPayload) {
        val bucketId = currentBucketId
        // Filter out ops with mismatched bucketId (cross-bucket injection protection)
        val validOps = if (bucketId != null) {
            payload.ops.filter { op ->
                if (op.bucketId != bucketId) {
                    Log.w(TAG, "Rejecting op with mismatched bucketId: expected=$bucketId, got=${op.bucketId}")
                    false
                } else {
                    true
                }
            }
        } else {
            payload.ops
        }

        val entities = validOps.map { p2pOpToEntity(it) }
        if (entities.isNotEmpty()) {
            opLogDao.insertOpLogEntries(entities)
            opsReceivedCount += entities.size
            Log.d(TAG, "Received and stored ${entities.size} ops (total: $opsReceivedCount)")
        }
    }

    private fun handleSyncComplete(syncComplete: P2PMessage.SyncComplete) {
        Log.d(TAG, "Peer reports sync complete: sent=${syncComplete.opsSent}, received=${syncComplete.opsReceived}")
        _state.value = P2PState.Completed(
            opsReceived = opsReceivedCount,
            opsSent = opsSentCount
        )
    }

    private fun handleError(endpointId: String, error: P2PMessage.Error) {
        Log.e(TAG, "Peer error: [${error.code}] ${error.message}")
        _state.value = P2PState.Error("Peer error: ${error.message}")
        connectionsClient.disconnectFromEndpoint(endpointId)
    }

    // ── Sync Logic ───────────────────────────────────────────────────────────

    private suspend fun performSync(endpointId: String, bucketId: String, peerLastSeq: Long) {
        try {
            val opsToSend = getOpsToSend(bucketId, peerLastSeq)
            val totalOps = opsToSend.size
            opsSentCount = 0

            if (totalOps == 0) {
                // Nothing to send; notify completion
                val endpointName = ((_state.value as? P2PState.Syncing)?.endpointName) ?: endpointId
                _state.value = P2PState.Syncing(1f, endpointName)
                sendMessage(
                    endpointId,
                    P2PMessage.SyncComplete(opsReceived = 0, opsSent = 0)
                )
                return
            }

            // Send ops in batches
            val batches = opsToSend.chunked(BATCH_SIZE)
            for ((index, batch) in batches.withIndex()) {
                val p2pOps = batch.map { entityToP2POp(it) }
                sendMessage(endpointId, P2PMessage.OpsPayload(p2pOps))
                opsSentCount += batch.size

                val progress = (index + 1).toFloat() / batches.size
                val endpointName = ((_state.value as? P2PState.Syncing)?.endpointName) ?: endpointId
                _state.value = P2PState.Syncing(progress, endpointName)
            }

            // Send completion message
            sendMessage(
                endpointId,
                P2PMessage.SyncComplete(opsReceived = opsReceivedCount, opsSent = opsSentCount)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sync failed", e)
            _state.value = P2PState.Error("Sync failed: ${e.message}")
            try {
                sendMessage(endpointId, P2PMessage.Error("SYNC_ERROR", "Sync failed: ${e.message}"))
            } catch (_: Exception) {
                // Best-effort error notification
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendMessage(endpointId: String, message: P2PMessage) {
        val bytes = serializeMessage(message)
        connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    private fun resetSyncCounters() {
        opsSentCount = 0
        opsReceivedCount = 0
        peerLastSequence = -1
    }

}
